package com.mycompany.p2pfilesharing.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Tiện ích nén và giải nén thư mục phục vụ chia sẻ toàn bộ thư mục qua mạng (ZipUtils).
 * Nhiệm vụ 4 - Hoàng
 */
public class ZipUtils {

    /**
     * Nén một thư mục (hoặc file) thành file .zip.
     *
     * @param sourceFolder Thư mục nguồn cần nén
     * @param zipFile File .zip đích tạo ra
     */
    public static void zipFolder(File sourceFolder, File zipFile) throws IOException {
        if (!sourceFolder.exists()) {
            throw new IOException("Thư mục nguồn không tồn tại: " + sourceFolder.getAbsolutePath());
        }

        // Tạo thư mục cha nếu chưa có
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            compress(sourceFolder, sourceFolder.getName(), zos);
        }
    }

    private static void compress(File fileToZip, String baseName, ZipOutputStream zos) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }

        if (fileToZip.isDirectory()) {
            String entryName = baseName.endsWith("/") ? baseName : baseName + "/";
            zos.putNextEntry(new ZipEntry(entryName));
            zos.closeEntry();

            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File child : children) {
                    compress(child, entryName + child.getName(), zos);
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(fileToZip)) {
                ZipEntry zipEntry = new ZipEntry(baseName);
                zos.putNextEntry(zipEntry);
                byte[] bytes = new byte[8192];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zos.write(bytes, 0, length);
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * Giải nén file .zip vào thư mục đích.
     *
     * @param zipFile File .zip cần giải nén
     * @param destDir Thư mục đích nhận các file giải nén
     */
    public static void unzip(File zipFile, File destDir) throws IOException {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Không thể tạo thư mục: " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Không thể tạo thư mục cha: " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    // Bảo vệ tránh lỗ hổng Zip Slip (ghi đè file ngoài thư mục đích)
    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());
        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Phát hiện đường dẫn không an toàn trong file zip: " + zipEntry.getName());
        }
        return destFile;
    }
}
