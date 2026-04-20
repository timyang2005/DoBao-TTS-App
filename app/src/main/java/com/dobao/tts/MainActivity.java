package com.dobao.tts;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * DoBao TTS 主界面
 * 功能：导入ZIP包、启动/停止TTS服务、显示终端输出
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "dobao_tts_prefs";
    private static final String KEY_INSTALL_DIR = "install_dir";

    // UI 组件
    private TextView tvTerminal;
    private TextView tvStatus;
    private TextView tvPackageInfo;
    private TextView tvPortInfo;
    private View statusDot;
    private Button btnImport;
    private Button btnStart;
    private Button btnStop;
    private Button btnClearLog;
    private Button btnOpenBrowser;
    private ScrollView scrollView;

    // 服务绑定
    private NodeService nodeService;
    private boolean serviceBound = false;

    // 日志内容（SpannableStringBuilder 支持彩色文字）
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 安装目录
    private File installDir;
    private SharedPreferences prefs;

    // 文件选择器 launcher
    private ActivityResultLauncher<Intent> filePickerLauncher;

    // 服务连接
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            NodeService.LocalBinder localBinder = (NodeService.LocalBinder) binder;
            nodeService = localBinder.getService();
            serviceBound = true;

            // 注册日志回调
            nodeService.setLogCallback(new NodeService.LogCallback() {
                @Override
                public void onLog(String text, int color) {
                    mainHandler.post(() -> appendLog(text, color));
                }

                @Override
                public void onStatusChanged(boolean running) {
                    mainHandler.post(() -> updateServiceStatus(running));
                }

                @Override
                public void onPortDetected(int port) {
                    mainHandler.post(() -> updatePortInfo(port));
                }
            });

            // 同步当前状态
            updateServiceStatus(nodeService.isRunning());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            nodeService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews();
        initFilePicker();
        restoreInstallDir();
        bindNodeService();

        // 处理从外部 Intent 传入的 ZIP
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void initViews() {
        tvTerminal = findViewById(R.id.tvTerminal);
        tvStatus = findViewById(R.id.tvStatus);
        tvPackageInfo = findViewById(R.id.tvPackageInfo);
        tvPortInfo = findViewById(R.id.tvPortInfo);
        statusDot = findViewById(R.id.statusDot);
        btnImport = findViewById(R.id.btnImport);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser);
        scrollView = findViewById(R.id.scrollView);

        btnImport.setOnClickListener(v -> openFilePicker());
        btnStart.setOnClickListener(v -> startTtsService());
        btnStop.setOnClickListener(v -> stopTtsService());
        btnClearLog.setOnClickListener(v -> clearLog());
        btnOpenBrowser.setOnClickListener(v -> openBrowser());
    }

    private void initFilePicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            importZipFile(uri);
                        }
                    }
                }
        );
    }

    private void openFilePicker() {
        // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要文件权限")
                        .setMessage("DoBao TTS 需要访问外部存储来导入安装包。\n\n请在设置中授予「所有文件访问权限」。")
                        .setPositiveButton("去设置", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("仍然继续", (d, w) -> launchFilePicker())
                        .show();
                return;
            }
        }
        launchFilePicker();
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 也接受通配类型
        Intent chooser = Intent.createChooser(intent, "选择 DoBao-TTS ZIP 安装包");
        filePickerLauncher.launch(chooser);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri data = intent.getData();
        if ((Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action)) && data != null) {
            importZipFile(data);
        }
    }

    private void importZipFile(Uri uri) {
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("正在导入");
        dialog.setMessage("解压安装包中，请稍候...");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(100);
        dialog.setCancelable(false);
        dialog.show();

        appendLog("[系统] 开始导入安装包...\n", Color.CYAN);
        appendLog("[系统] URI: " + uri.toString() + "\n", Color.GRAY);

        new Thread(() -> {
            try {
                // 目标安装目录
                File targetDir = new File(getFilesDir(), "dobao-tts");
                if (targetDir.exists()) {
                    ZipUtils.deleteRecursive(targetDir);
                }
                targetDir.mkdirs();

                // 解压
                ZipUtils.unzip(this, uri, targetDir, (progress, message) -> {
                    mainHandler.post(() -> {
                        dialog.setProgress(progress);
                        dialog.setMessage(message);
                    });
                });

                installDir = targetDir;
                prefs.edit().putString(KEY_INSTALL_DIR, targetDir.getAbsolutePath()).apply();

                mainHandler.post(() -> {
                    dialog.dismiss();
                    updatePackageInfo(targetDir);
                    btnStart.setEnabled(true);
                    appendLog("[✓] 安装包导入成功！路径: " + targetDir.getAbsolutePath() + "\n", Color.GREEN);
                    appendLog("[提示] 点击「▶ 启动」按钮运行 TTS 服务\n", Color.YELLOW);
                    Toast.makeText(this, "导入成功！", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    dialog.dismiss();
                    appendLog("[✗] 导入失败: " + e.getMessage() + "\n", Color.RED);
                    Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void restoreInstallDir() {
        String path = prefs.getString(KEY_INSTALL_DIR, null);
        if (path != null) {
            File dir = new File(path);
            if (dir.exists() && isValidInstall(dir)) {
                installDir = dir;
                updatePackageInfo(dir);
                btnStart.setEnabled(true);
                appendLog("[系统] 检测到已安装的 TTS 包: " + path + "\n", Color.CYAN);
            } else {
                appendLog("[系统] 未找到安装包，请导入 ZIP\n", Color.GRAY);
            }
        } else {
            appendLog("[系统] 欢迎使用 DoBao TTS！\n", Color.CYAN);
            appendLog("[系统] 请点击「导入 ZIP」按钮导入安装包\n", Color.GRAY);
        }
    }

    private boolean isValidInstall(File dir) {
        // 递归搜索 server.js（适配不同 ZIP 包结构）
        return findServerJs(dir) != null;
    }

    private File findWorkDir(File baseDir) {
        // 先检查直接路径
        File direct = new File(baseDir, "app/src/server.js");
        if (direct.exists()) return baseDir;

        // 递归搜索 server.js
        File serverJs = findServerJs(baseDir);
        if (serverJs != null) {
            // workDir 是 server.js 所在的目录（向上两级，通常是项目根目录）
            // 先尝试 server.js 的父目录作为 workDir
            File parent = serverJs.getParentFile();
            if (parent != null) {
                // 如果 server.js 在 src/ 下，workDir 是 src 的父目录（即 app/ 或项目根）
                // 如果 server.js 直接在根目录，workDir 就是根目录
                File grandParent = parent.getParentFile();
                if (grandParent != null) {
                    // 检查 grandParent 下是否有 node_modules
                    File nm = new File(grandParent, "node_modules");
                    if (nm.exists()) return grandParent;
                    // 检查 parent 下是否有 node_modules
                    nm = new File(parent, "node_modules");
                    if (nm.exists()) return parent;
                }
                // 默认返回 server.js 的父目录
                return parent;
            }
        }
        return null;
    }

    /**
     * 递归搜索 server.js 文件
     */
    private File findServerJs(File dir) {
        if (dir == null || !dir.exists()) return null;
        File direct = new File(dir, "server.js");
        if (direct.exists()) return direct;
        if (dir.isDirectory() && dir.listFiles() != null) {
            for (File sub : dir.listFiles()) {
                if (sub.isDirectory() && !sub.getName().equals("node_modules")
                        && !sub.getName().startsWith(".")) {
                    File found = findServerJs(sub);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private void updatePackageInfo(File dir) {
        String info = "已导入: " + dir.getName();
        // 显示找到的 server.js 路径
        File serverJs = findServerJs(dir);
        if (serverJs != null) {
            File workDir = findWorkDir(dir);
            if (workDir != null) {
                info += "\n工作目录: " + workDir.getName();
            }
        } else {
            info += "\n[!] 未找到 server.js";
        }
        tvPackageInfo.setText(info);
        tvPackageInfo.setTextColor(Color.parseColor("#00C853"));
    }

    private void startTtsService() {
        if (installDir == null) {
            Toast.makeText(this, "请先导入安装包", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isValidInstall(installDir)) {
            appendLog("[✗] 安装包结构不正确，请重新导入\n", Color.RED);
            return;
        }

        appendLog("\n[系统] 正在启动 TTS 服务...\n", Color.CYAN);

        // 找到实际工作目录（处理子目录嵌套）
        File workDir = findWorkDir(installDir);
        if (workDir == null) {
            appendLog("[✗] 无法找到服务目录\n", Color.RED);
            return;
        }

        Intent intent = new Intent(this, NodeService.class);
        intent.putExtra(NodeService.EXTRA_WORK_DIR, workDir.getAbsolutePath());
        startForegroundService(intent);

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
    }

    private void stopTtsService() {
        if (serviceBound && nodeService != null) {
            nodeService.stopNode();
        }
        Intent intent = new Intent(this, NodeService.class);
        stopService(intent);

        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        appendLog("\n[系统] 服务已停止\n", Color.YELLOW);
    }

    private void bindNodeService() {
        Intent intent = new Intent(this, NodeService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void updateServiceStatus(boolean running) {
        if (running) {
            tvStatus.setText("运行中");
            tvStatus.setTextColor(Color.parseColor("#00C853"));
            statusDot.setBackgroundResource(R.drawable.status_dot_green);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else {
            tvStatus.setText("未运行");
            tvStatus.setTextColor(Color.parseColor("#888888"));
            statusDot.setBackgroundResource(R.drawable.status_dot);
            btnStart.setEnabled(installDir != null);
            btnStop.setEnabled(false);
            btnOpenBrowser.setVisibility(View.GONE);
        }
    }

    private void updatePortInfo(int port) {
        tvPortInfo.setText("服务运行在 http://localhost:" + port + "  (局域网: " + getLocalIp() + ":" + port + ")");
        tvPortInfo.setTextColor(Color.parseColor("#00FF41"));
        btnOpenBrowser.setVisibility(View.VISIBLE);
        btnOpenBrowser.setTag(port);
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private void openBrowser() {
        Object tag = btnOpenBrowser.getTag();
        int port = tag instanceof Integer ? (int) tag : 3000;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:" + port));
        startActivity(intent);
    }

    public void appendLog(String text, int color) {
        int start = logBuffer.length();
        logBuffer.append(text);
        logBuffer.setSpan(
                new ForegroundColorSpan(color),
                start,
                logBuffer.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        tvTerminal.setText(logBuffer);
        // 自动滚动到底部
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void clearLog() {
        logBuffer.clear();
        logBuffer.clearSpans();
        tvTerminal.setText("");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
