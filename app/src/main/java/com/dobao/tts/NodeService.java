package com.dobao.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NodeService - 前台服务，负责：
 * 1. 下载/解压 Node.js 运行时（arm64）
 * 2. 解压 node_modules.tar.gz
 * 3. 启动 node app/src/server.js
 * 4. 实时输出日志到 UI
 */
public class NodeService extends Service {

    private static final String TAG = "NodeService";
    private static final String CHANNEL_ID = "dobao_tts_channel";
    private static final int NOTIF_ID = 1001;

    public static final String EXTRA_WORK_DIR = "work_dir";

    // Node.js ARM64 官方二进制下载地址
    // 优先使用打包在 assets 的本地版本，assets 没有则在线下载
    private static final String NODE_DOWNLOAD_URL =
            "https://nodejs.org/dist/v20.18.0/node-v20.18.0-linux-arm64.tar.gz";
    private static final String NODE_VERSION = "v20.18.0";

    private final IBinder binder = new LocalBinder();
    private LogCallback logCallback;
    private Process nodeProcess;
    private boolean running = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 端口匹配模式
    private static final Pattern PORT_PATTERN = Pattern.compile(
            "(?:port|端口|listening|started|running).{0,30}(\\d{4,5})",
            Pattern.CASE_INSENSITIVE
    );

    public interface LogCallback {
        void onLog(String text, int color);
        void onStatusChanged(boolean running);
        void onPortDetected(int port);
    }

    public class LocalBinder extends Binder {
        public NodeService getService() {
            return NodeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("TTS 服务启动中..."));

        if (intent != null && intent.hasExtra(EXTRA_WORK_DIR)) {
            String workDir = intent.getStringExtra(EXTRA_WORK_DIR);
            executor.submit(() -> startNodeProcess(new File(workDir)));
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 启动 Node.js 进程主流程
     */
    private void startNodeProcess(File workDir) {
        log("[系统] 工作目录: " + workDir.getAbsolutePath() + "\n", Color.CYAN);

        // Step 1: 确保 Node.js 可用
        File nodeBin = ensureNodeBinary();
        if (nodeBin == null) {
            log("[✗] 无法获取 Node.js 运行时\n", Color.RED);
            notifyStatus(false);
            return;
        }
        log("[✓] Node.js: " + nodeBin.getAbsolutePath() + "\n", Color.GREEN);

        // Step 2: 解压 node_modules（如果尚未解压）
        File appDir = new File(workDir, "app");
        if (!extractNodeModules(appDir)) {
            log("[✗] node_modules 准备失败\n", Color.RED);
            notifyStatus(false);
            return;
        }

        // Step 3: 启动 server.js
        File serverJs = new File(appDir, "src/server.js");
        if (!serverJs.exists()) {
            log("[✗] 找不到 " + serverJs.getAbsolutePath() + "\n", Color.RED);
            notifyStatus(false);
            return;
        }

        log("\n[系统] 正在启动 Node.js TTS 服务...\n", Color.CYAN);
        log("==========================================\n", Color.parseColor("#444444"));
        log("    DoBao-TTS\n", Color.parseColor("#00FF41"));
        log("==========================================\n", Color.parseColor("#444444"));

        try {
            List<String> command = new ArrayList<>();
            command.add(nodeBin.getAbsolutePath());
            command.add(serverJs.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true); // stderr 合并到 stdout
            pb.environment().put("NODE_ENV", "production");
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("PATH",
                    nodeBin.getParent() + ":" + System.getenv("PATH"));

            nodeProcess = pb.start();
            running = true;
            notifyStatus(true);
            updateNotification("TTS 服务运行中");

            // 实时读取输出
            executor.submit(() -> readProcessOutput(nodeProcess));

            // 等待进程结束
            int exitCode = nodeProcess.waitFor();
            running = false;
            notifyStatus(false);

            if (exitCode == 143) {
                log("\n[系统] 服务已正常停止 (SIGTERM)\n", Color.YELLOW);
            } else if (exitCode != 0) {
                log("\n[✗] 服务退出，退出码: " + exitCode + "\n", Color.RED);
            } else {
                log("\n[系统] 服务已停止\n", Color.YELLOW);
            }
            updateNotification("TTS 服务已停止");

        } catch (Exception e) {
            log("[✗] 启动失败: " + e.getMessage() + "\n", Color.RED);
            Log.e(TAG, "startNodeProcess error", e);
            running = false;
            notifyStatus(false);
        }
    }

    /**
     * 确保 Node.js 二进制可用
     */
    private File ensureNodeBinary() {
        // 先检查已解压的 node
        File nodeDir = new File(getFilesDir(), "node-runtime");
        File nodeBin = new File(nodeDir, "bin/node");

        if (nodeBin.exists() && nodeBin.canExecute()) {
            log("[✓] Node.js 已就绪: " + NODE_VERSION + "\n", Color.GREEN);
            return nodeBin;
        }

        // 检查 assets 中是否有 node 二进制（单文件，体积大，可选）
        File assetsNode = extractNodeFromAssets();
        if (assetsNode != null) return assetsNode;

        // 在线下载 Node.js
        log("[系统] 正在下载 Node.js " + NODE_VERSION + "...\n", Color.YELLOW);
        log("[系统] 这只需要一次，请耐心等待\n", Color.GRAY);

        if (!nodeDir.exists()) nodeDir.mkdirs();

        try {
            // 使用 Android 内置的 HttpURLConnection 下载
            java.net.URL url = new java.net.URL(NODE_DOWNLOAD_URL);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.connect();

            long total = conn.getContentLengthLong();
            File tarGz = new File(getCacheDir(), "node.tar.gz");

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tarGz)) {
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int n;
                long lastLog = 0;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    // 每 5MB 打印一次进度
                    if (downloaded - lastLog > 5 * 1024 * 1024) {
                        long pct = total > 0 ? (downloaded * 100 / total) : -1;
                        if (pct >= 0) {
                            log("[下载] " + (downloaded / 1024 / 1024) + "MB / "
                                    + (total / 1024 / 1024) + "MB (" + pct + "%)\n", Color.GRAY);
                        } else {
                            log("[下载] " + (downloaded / 1024 / 1024) + "MB\n", Color.GRAY);
                        }
                        lastLog = downloaded;
                    }
                }
            }
            conn.disconnect();
            log("[✓] 下载完成，正在解压...\n", Color.GREEN);

            // 解压 tar.gz
            extractTarGz(tarGz, nodeDir);
            tarGz.delete();

            // node 解压后在 node-v20.18.0-linux-arm64/bin/node
            File extractedBin = new File(nodeDir,
                    "node-" + NODE_VERSION + "-linux-arm64/bin/node");
            if (!extractedBin.exists()) {
                // 尝试找
                extractedBin = findFile(nodeDir, "node");
            }
            if (extractedBin != null && extractedBin.exists()) {
                extractedBin.setExecutable(true, false);
                // 移动到标准位置
                File binDir = new File(nodeDir, "bin");
                binDir.mkdirs();
                File dest = new File(binDir, "node");
                if (!extractedBin.getAbsolutePath().equals(dest.getAbsolutePath())) {
                    // 直接用原路径
                    extractedBin.setExecutable(true, false);
                    return extractedBin;
                }
                return dest;
            }

        } catch (Exception e) {
            log("[✗] 下载 Node.js 失败: " + e.getMessage() + "\n", Color.RED);
            log("[提示] 请检查网络连接后重试\n", Color.YELLOW);
            Log.e(TAG, "download node error", e);
        }
        return null;
    }

    private File extractNodeFromAssets() {
        // 如果 assets 中有 node 单文件（打包方式），直接用
        try {
            getAssets().open("node"); // 尝试打开
            File dest = new File(getFilesDir(), "node-runtime/bin/node");
            dest.getParentFile().mkdirs();
            if (!dest.exists()) {
                try (InputStream in = getAssets().open("node");
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
                dest.setExecutable(true, false);
                log("[✓] Node.js 已从内置资源加载\n", Color.GREEN);
            }
            return dest;
        } catch (Exception ignored) {
            // assets 中没有 node，正常
        }
        return null;
    }

    /**
     * 解压 tar.gz（使用系统 tar 命令）
     */
    private void extractTarGz(File tarGz, File destDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf",
                tarGz.getAbsolutePath(), "-C", destDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 忽略解压日志
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new Exception("tar 解压失败，退出码: " + exit);
        }
    }

    /**
     * 解压 node_modules.tar.gz（首次启动）
     */
    private boolean extractNodeModules(File appDir) {
        File nodeModules = new File(appDir, "node_modules");
        if (nodeModules.exists() && nodeModules.list() != null
                && nodeModules.list().length > 0) {
            log("[✓] npm 依赖已就绪\n", Color.GREEN);
            return true;
        }

        File tarGz = new File(appDir, "node_modules.tar.gz");
        if (!tarGz.exists()) {
            log("[✗] 未找到 node_modules.tar.gz\n", Color.RED);
            return false;
        }

        log("[系统] 首次启动，正在解压 npm 依赖（可能需要几分钟）...\n", Color.YELLOW);
        nodeModules.mkdirs();

        try {
            // 检查 tar 包完整性
            ProcessBuilder checkPb = new ProcessBuilder("tar", "-tzf", tarGz.getAbsolutePath());
            checkPb.redirectErrorStream(true);
            Process checkP = checkPb.start();
            checkP.waitFor();

            // 解压到 node_modules 目录
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "-xzf", tarGz.getAbsolutePath(),
                    "-C", nodeModules.getAbsolutePath(),
                    "--no-same-permissions", "--no-same-owner"
            );
            pb.directory(appDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 实时输出解压日志
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    count++;
                    if (count % 500 == 0) {
                        final int c = count;
                        log("[解压] 已处理 " + c + " 个文件...\n", Color.GRAY);
                    }
                }
            }

            int exit = p.waitFor();
            if (nodeModules.list() != null && nodeModules.list().length > 0) {
                log("[✓] npm 依赖解压完成\n", Color.GREEN);
                // 删除 tar.gz 节省空间
                tarGz.delete();
                log("[✓] 已清理压缩包\n", Color.parseColor("#888888"));
                return true;
            } else {
                log("[✗] 解压失败 (退出码: " + exit + ")\n", Color.RED);
                return false;
            }
        } catch (Exception e) {
            log("[✗] 解压 node_modules 失败: " + e.getMessage() + "\n", Color.RED);
            Log.e(TAG, "extract node_modules error", e);
            return false;
        }
    }

    /**
     * 实时读取进程输出并转发到 UI
     */
    private void readProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String logLine = line + "\n";
                int color = detectLineColor(line);
                log(logLine, color);

                // 检测端口
                Matcher m = PORT_PATTERN.matcher(line);
                if (m.find()) {
                    try {
                        int port = Integer.parseInt(m.group(1));
                        if (port > 1024 && port < 65535 && logCallback != null) {
                            logCallback.onPortDetected(port);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            if (running) {
                log("[系统] 读取输出异常: " + e.getMessage() + "\n", Color.RED);
            }
        }
    }

    /**
     * 根据日志内容判断颜色
     */
    private int detectLineColor(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("error") || lower.contains("错误") || lower.contains("✗")) {
            return Color.RED;
        } else if (lower.contains("warn") || lower.contains("警告")) {
            return Color.YELLOW;
        } else if (lower.contains("success") || lower.contains("成功")
                || lower.contains("started") || lower.contains("running")
                || lower.contains("✓") || lower.contains("ready")) {
            return Color.GREEN;
        } else if (lower.contains("info") || lower.contains("listen")
                || lower.contains("port") || lower.contains("端口")) {
            return Color.CYAN;
        } else {
            return Color.parseColor("#00FF41"); // 默认绿色终端色
        }
    }

    public void stopNode() {
        if (nodeProcess != null) {
            nodeProcess.destroy();
            nodeProcess = null;
        }
        running = false;
        notifyStatus(false);
    }

    private void log(String text, int color) {
        Log.d(TAG, text.trim());
        if (logCallback != null) {
            logCallback.onLog(text, color);
        }
    }

    private void notifyStatus(boolean isRunning) {
        running = isRunning;
        if (logCallback != null) {
            logCallback.onStatusChanged(isRunning);
        }
    }

    private File findFile(File dir, String name) {
        if (dir.listFiles() == null) return null;
        for (File f : dir.listFiles()) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                File found = findFile(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== 通知相关 ====================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.channel_desc));
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DoBao TTS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopNode();
        executor.shutdownNow();
    }
}
