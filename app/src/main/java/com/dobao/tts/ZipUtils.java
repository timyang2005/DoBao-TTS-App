package com.dobao.tts;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 解压工具类
 */
public class ZipUtils {

    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    /**
     * 解压 ZIP 文件到目标目录
     *
     * @param context  Context（用于 ContentResolver）
     * @param uri      ZIP 文件 URI
     * @param destDir  目标目录
     * @param callback 进度回调
     */
    public static void unzip(Context context, Uri uri, File destDir,
                             ProgressCallback callback) throws Exception {
        destDir.mkdirs();

        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedInputStream bis = new BufferedInputStream(is, 65536);
             ZipInputStream zis = new ZipInputStream(bis)) {

            ZipEntry entry;
            byte[] buffer = new byte[65536];
            int entryCount = 0;
            int processed = 0;

            // 先快速统计 entry 数量（估算进度用）
            // 注意：ContentResolver URI 不一定支持重复读，所以直接估算进度

            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                processed++;

                String entryName = entry.getName();

                // 防止 zip slip 攻击
                File destFile = new File(destDir, entryName);
                if (!destFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    throw new SecurityException("Zip Slip 攻击检测到: " + entryName);
                }

                if (entry.isDirectory()) {
                    destFile.mkdirs();
                } else {
                    // 确保父目录存在
                    File parent = destFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }

                    // 写入文件
                    try (FileOutputStream fos = new FileOutputStream(destFile);
                         OutputStream out = new java.io.BufferedOutputStream(fos, 65536)) {
                        int n;
                        while ((n = zis.read(buffer)) >= 0) {
                            out.write(buffer, 0, n);
                        }
                    }
                }

                zis.closeEntry();

                // 每 20 个文件回调一次进度
                if (processed % 20 == 0) {
                    String shortName = entryName.length() > 40
                            ? "..." + entryName.substring(entryName.length() - 37)
                            : entryName;
                    if (callback != null) {
                        // 无法精确知道总数，估算一个进度
                        int pct = Math.min(90, processed / 5);
                        callback.onProgress(pct, "解压: " + shortName);
                    }
                }
            }

            if (callback != null) {
                callback.onProgress(100, "解压完成，共 " + processed + " 个文件");
            }
        }
    }

    /**
     * 递归删除目录
     */
    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
