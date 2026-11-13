package com.mycompany.p2pfilesharing.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Tiện ích băm dữ liệu (MD5 / SHA-1) phục vụ kiểm tra tính toàn vẹn của file và từng mảnh dữ liệu (chunks).
 * Nhiệm vụ 3 - Huân
 */
public class HashUtils {

    /**
     * Tính mã băm MD5 cho một mảng byte (thường dùng cho từng chunk dữ liệu).
     */
    public static String calculateMD5(byte[] data, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data, offset, length);
            return bytesToHex(md.digest());
        } catch (Exception e) {
            System.err.println("Lỗi tính MD5 chunk: " + e.getMessage());
            return "";
        }
    }

    /**
     * Tính mã băm MD5 cho toàn bộ mảng byte.
     */
    public static String calculateMD5(byte[] data) {
        if (data == null) return "";
        return calculateMD5(data, 0, data.length);
    }

    /**
     * Tính mã băm SHA-1 cho một mảng byte.
     */
    public static String calculateSHA1(byte[] data, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(data, offset, length);
            return bytesToHex(md.digest());
        } catch (Exception e) {
            System.err.println("Lỗi tính SHA-1 chunk: " + e.getMessage());
            return "";
        }
    }

    /**
     * Tính mã băm SHA-1 cho toàn bộ mảng byte.
     */
    public static String calculateSHA1(byte[] data) {
        if (data == null) return "";
        return calculateSHA1(data, 0, data.length);
    }

    /**
     * Tính mã băm MD5 cho toàn bộ file bằng luồng đọc byte.
     */
    public static String calculateFileMD5(File file) {
        return calculateFileHash(file, "MD5");
    }

    /**
     * Tính mã băm SHA-1 cho toàn bộ file bằng luồng đọc byte.
     */
    public static String calculateFileSHA1(File file) {
        return calculateFileHash(file, "SHA-1");
    }

    /**
     * Hàm dùng chung tính mã băm cho File theo thuật toán chỉ định (MD5 / SHA-1).
     */
    private static String calculateFileHash(File file, String algorithm) {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            return bytesToHex(md.digest());
        } catch (Exception e) {
            System.err.println("Lỗi tính " + algorithm + " file: " + e.getMessage());
            return "";
        }
    }

    /**
     * Chuyển mảng byte sau khi băm sang chuỗi hex định dạng dễ đọc.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * So sánh 2 mã băm để kiểm tra tính toàn vẹn (không phân biệt hoa thường).
     */
    public static boolean checkIntegrity(String expectedHash, String actualHash) {
        if (expectedHash == null || actualHash == null) {
            return false;
        }
        return expectedHash.equalsIgnoreCase(actualHash);
    }

    /**
     * Hàm main đơn giản để chạy thử nghiệm tính năng băm dữ liệu.
     */
    public static void main(String[] args) {
        System.out.println("=== KIỂM THỬ HASHUTILS (NHIỆM VỤ 3 - HUÂN) ===");
        String sampleText = "Xin chao, day la du lieu test P2P File Sharing!";
        byte[] chunk = sampleText.getBytes();

        String md5 = calculateMD5(chunk);
        String sha1 = calculateSHA1(chunk);

        System.out.println("Dữ liệu mẫu: " + sampleText);
        System.out.println("MD5 chunk  : " + md5);
        System.out.println("SHA-1 chunk: " + sha1);
        System.out.println("Kiểm tra toàn vẹn: " + checkIntegrity(md5, md5));
    }
}
