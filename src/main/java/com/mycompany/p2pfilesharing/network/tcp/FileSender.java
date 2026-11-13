package com.mycompany.p2pfilesharing.network.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;

public class FileSender implements Runnable {

    // Giao diện lắng nghe sự kiện truyền file */
    public interface FileSenderListener {
        // Khi bắt đầu kết nối / phiên truyền file */
        default void onStart(String fileName, long totalBytes, long startOffset) {}

        // Cập nhật tiến trình truyền dữ liệu */
        default void onProgress(String fileName, long bytesSent, long totalBytes, int percent, double speedMBs) {}

        // Khi phiên truyền file bị tạm dừng */
        default void onPaused(String fileName, long currentBytes) {}

        // Khi phiên truyền file được tiếp tục */
        default void onResumed(String fileName, long currentBytes) {}

        // Khi phiên truyền file hoàn tất thành công */
        default void onCompleted(String fileName, long totalBytes) {}

        // Khi phiên truyền file bị dừng / hủy */
        default void onStopped(String fileName, long bytesSent) {}

        // Khi xảy ra lỗi trong quá trình truyền */
        default void onError(String fileName, String errorMessage, Exception ex) {}
    }

    private static final int BUFFER_SIZE = 64 * 1024; // Phân mảnh 64 KB mỗi lần gửi
    private static final int CONNECT_TIMEOUT = 10000;  // 10 giây thời gian chờ kết nối

    private final String targetIp;
    private final int targetPort;
    private final File fileToSend;
    private final String senderPeerName;
    private FileSenderListener listener;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();

    private Socket socket;
    private Thread workerThread;
    private long bytesSent = 0;
    private long totalBytes = 0;

    public FileSender(String targetIp, int targetPort, File fileToSend, String senderPeerName) {
        this(targetIp, targetPort, fileToSend, senderPeerName, null);
    }

    public FileSender(String targetIp, int targetPort, File fileToSend, String senderPeerName, FileSenderListener listener) {
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.fileToSend = fileToSend;
        this.senderPeerName = (senderPeerName != null && !senderPeerName.trim().isEmpty())
                ? senderPeerName.trim()
                : "UnknownPeer";
        this.listener = listener;
        if (fileToSend != null) {
            this.totalBytes = fileToSend.length();
        }
    }

    public void setListener(FileSenderListener listener) {
        this.listener = listener;
    }

    
    // Khởi động tiến trình gửi file trong một luồng mới.
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        paused = false;
        workerThread = new Thread(this, "TCP-FileSender-" + (fileToSend != null ? fileToSend.getName() : "unnamed"));
        workerThread.start();
    }

    // Tạm dừng gửi file.
 
    public void pause() {
        if (!running || paused) {
            return;
        }
        paused = true;
        System.out.println("[TCP SENDER] Đã tạm dừng gửi: " + (fileToSend != null ? fileToSend.getName() : ""));
        if (listener != null && fileToSend != null) {
            listener.onPaused(fileToSend.getName(), bytesSent);
        }
    }

    // Tiếp tục gửi file sau khi tạm dừng.
  
    public void resume() {
        if (!running || !paused) {
            return;
        }
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        System.out.println("[TCP SENDER] Tiếp tục gửi: " + (fileToSend != null ? fileToSend.getName() : ""));
        if (listener != null && fileToSend != null) {
            listener.onResumed(fileToSend.getName(), bytesSent);
        }
    }

    
    // Hủy / Dừng hẳn việc gửi file và đóng kết nối.
     
    public void stop() {
        running = false;
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        closeSocket();
        if (workerThread != null) {
            workerThread.interrupt();
        }
        System.out.println("[TCP SENDER] Đã hủy gửi: " + (fileToSend != null ? fileToSend.getName() : ""));
        if (listener != null && fileToSend != null) {
            listener.onStopped(fileToSend.getName(), bytesSent);
        }
    }

    @Override
    public void run() {
        if (fileToSend == null || !fileToSend.exists() || !fileToSend.isFile()) {
            String msg = "File không tồn tại hoặc không hợp lệ: " + (fileToSend != null ? fileToSend.getAbsolutePath() : "null");
            System.err.println("[TCP SENDER] " + msg);
            if (listener != null) {
                listener.onError(fileToSend != null ? fileToSend.getName() : "unknown", msg, null);
            }
            running = false;
            return;
        }

        totalBytes = fileToSend.length();
        String fileName = fileToSend.getName();

        System.out.println("[TCP SENDER] Bắt đầu kết nối tới " + targetIp + ":" + targetPort + " để gửi file: " + fileName + " (" + totalBytes + " bytes)");

        RandomAccessFile raf = null;
        DataOutputStream dos = null;
        DataInputStream dis = null;

        try {
            // 1. Kết nối Socket TCP
            socket = new Socket();
            socket.connect(new InetSocketAddress(targetIp, targetPort), CONNECT_TIMEOUT);

            dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // 2. Tính mã băm SHA-256 (nếu file < 200MB để tránh chờ lâu, nếu lớn hơn có thể để trống hoặc băm nhanh)
            String fileHash = "";
            if (totalBytes <= 200L * 1024 * 1024) {
                fileHash = calculateSHA256(fileToSend);
            }

            // 3. Bắt tay (Handshake) gửi Header
            dos.writeUTF("FILE_HEADER");
            dos.writeUTF(senderPeerName);
            dos.writeUTF(fileName);
            dos.writeLong(totalBytes);
            dos.writeUTF(fileHash);
            dos.flush();

            // 4. Nhận phản hồi từ Receiver
            String response = dis.readUTF();
            if (!"READY".equals(response)) {
                throw new IOException("Máy nhận từ chối nhận file (Phản hồi: " + response + ")");
            }

            // Offset nhận về: nếu Receiver đã có 1 phần file, bắt đầu từ offset đó (Resume)
            long startOffset = dis.readLong();
            if (startOffset < 0 || startOffset > totalBytes) {
                startOffset = 0;
            }

            bytesSent = startOffset;
            System.out.println("[TCP SENDER] Máy nhận chấp nhận. Bắt đầu truyền từ byte " + startOffset + "/" + totalBytes);

            if (listener != null) {
                listener.onStart(fileName, totalBytes, startOffset);
            }

            // 5. Mở file bằng RandomAccessFile và seek đến startOffset
            raf = new RandomAccessFile(fileToSend, "r");
            if (startOffset > 0) {
                raf.seek(startOffset);
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            long lastProgressTime = System.currentTimeMillis();
            long lastBytesForSpeed = bytesSent;

            // 6. Phân mảnh và gửi dữ liệu qua Socket
            while (running && bytesSent < totalBytes) {
                // Kiểm tra trạng thái tạm dừng (Pause)
                synchronized (pauseLock) {
                    while (paused && running) {
                        pauseLock.wait();
                    }
                }

                if (!running) {
                    break;
                }

                int toRead = (int) Math.min(buffer.length, totalBytes - bytesSent);
                int read = raf.read(buffer, 0, toRead);
                if (read == -1) {
                    break;
                }

                dos.write(buffer, 0, read);
                bytesSent += read;

                // Cập nhật tiến độ định kỳ (khoảng 100-150ms một lần để tránh nghẽn UI)
                long now = System.currentTimeMillis();
                if (now - lastProgressTime >= 120 || bytesSent >= totalBytes) {
                    long timeDelta = now - lastProgressTime;
                    double speedMBs = 0.0;
                    if (timeDelta > 0) {
                        speedMBs = ((bytesSent - lastBytesForSpeed) / 1024.0 / 1024.0) / (timeDelta / 1000.0);
                    }
                    int percent = (totalBytes > 0) ? (int) (bytesSent * 100 / totalBytes) : 100;

                    if (listener != null) {
                        listener.onProgress(fileName, bytesSent, totalBytes, percent, speedMBs);
                    }

                    lastProgressTime = now;
                    lastBytesForSpeed = bytesSent;
                }
            }

            dos.flush();

            // 7. Nhận xác nhận hoàn tất từ Receiver
            if (running && bytesSent >= totalBytes) {
                String confirm = dis.readUTF();
                if ("COMPLETE".equals(confirm)) {
                    System.out.println("[TCP SENDER] Truyền file thành công: " + fileName);
                    if (listener != null) {
                        listener.onProgress(fileName, totalBytes, totalBytes, 100, 0.0);
                        listener.onCompleted(fileName, totalBytes);
                    }
                } else {
                    throw new IOException("Máy nhận phản hồi lỗi: " + confirm);
                }
            }

        } catch (InterruptedException ie) {
            System.err.println("[TCP SENDER] Luồng gửi file bị ngắt: " + ie.getMessage());
            if (listener != null && running) {
                listener.onStopped(fileName, bytesSent);
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("[TCP SENDER] Lỗi khi gửi file: " + e.getMessage());
                if (listener != null) {
                    listener.onError(fileName, e.getMessage(), e);
                }
            }
        } finally {
            running = false;
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {}
            }
            closeSocket();
        }
    }

    private void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Tính mã băm SHA-256 của file để kiểm tra tính toàn vẹn.
     */
    public static String calculateSHA256(File file) {
        if (file == null || !file.exists()) {
            return "";
        }
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) {
                digest.update(buf, 0, r);
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public int getProgressPercent() {
        return (totalBytes > 0) ? (int) (bytesSent * 100 / totalBytes) : 0;
    }

    public File getFileToSend() {
        return fileToSend;
    }
}
