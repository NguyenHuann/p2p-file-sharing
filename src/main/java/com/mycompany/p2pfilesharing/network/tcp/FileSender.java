package com.mycompany.p2pfilesharing.network.tcp;

import com.mycompany.p2pfilesharing.utils.HashUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;

/**
 * TCP Client - Đọc file từ ổ cứng và đẩy lên mạng qua Socket.
 * Hỗ trợ phân mảnh (Chunking), băm kiểm tra toàn vẹn (SHA-1/MD5) và tạm dừng/tiếp tục (Resume/Pause).
 * Nhiệm vụ 3 - Huân (Code đơn giản, sáng sủa theo mẫu Socket truyền thống)
 */
public class FileSender implements Runnable {

    // Kích thước mỗi mảnh gửi qua mạng (Chunk size: 64 KB)
    public static final int CHUNK_SIZE = 64 * 1024;

    // Interface lắng nghe sự kiện gửi file (dành cho GUI của Ngân kết nối)
    public interface FileSenderListener {
        default void onStart(String fileName, long totalBytes, long startOffset) {}
        default void onProgress(String fileName, long bytesSent, long totalBytes, int percent, double speedMBs) {}
        default void onPaused(String fileName, long currentBytes) {}
        default void onResumed(String fileName, long currentBytes) {}
        default void onCompleted(String fileName, long totalBytes) {}
        default void onStopped(String fileName, long bytesSent) {}
        default void onError(String fileName, String errorMessage, Exception ex) {}
    }

    private final String targetIp;
    private final int targetPort;
    private final File fileToSend;
    private final String senderPeerName;
    private FileSenderListener listener;

    private volatile boolean running = false;
    private volatile boolean paused = false;

    private Socket socket;
    private Thread workerThread;
    private long bytesSent = 0;
    private long totalBytes = 0;

    public FileSender(String targetIp, int targetPort, File fileToSend) {
        this(targetIp, targetPort, fileToSend, "Huân-Peer", null);
    }

    public FileSender(String targetIp, int targetPort, File fileToSend, String senderPeerName) {
        this(targetIp, targetPort, fileToSend, senderPeerName, null);
    }

    public FileSender(String targetIp, int targetPort, File fileToSend, String senderPeerName, FileSenderListener listener) {
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.fileToSend = fileToSend;
        this.senderPeerName = (senderPeerName != null) ? senderPeerName : "Huân-Peer";
        this.listener = listener;
        if (fileToSend != null && fileToSend.exists()) {
            this.totalBytes = fileToSend.length();
        }
    }

    public void setListener(FileSenderListener listener) {
        this.listener = listener;
    }

    /**
     * Bắt đầu gửi file trong một luồng mới.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        paused = false;
        workerThread = new Thread(this, "FileSender-Thread-" + (fileToSend != null ? fileToSend.getName() : "test"));
        workerThread.start();
    }

    /**
     * Tạm dừng gửi file.
     */
    public void pause() {
        this.paused = true;
        System.out.println("[TCP SENDER] Đã tạm dừng gửi: " + (fileToSend != null ? fileToSend.getName() : ""));
        if (listener != null && fileToSend != null) {
            listener.onPaused(fileToSend.getName(), bytesSent);
        }
    }

    /**
     * Tiếp tục gửi file sau khi tạm dừng.
     */
    public void resume() {
        this.paused = false;
        System.out.println("[TCP SENDER] Tiếp tục gửi: " + (fileToSend != null ? fileToSend.getName() : ""));
        if (listener != null && fileToSend != null) {
            listener.onResumed(fileToSend.getName(), bytesSent);
        }
    }

    /**
     * Dừng và hủy phiên gửi file.
     */
    public synchronized void stop() {
        running = false;
        paused = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
        System.out.println("[TCP SENDER] Đã hủy gửi: " + (fileToSend != null ? fileToSend.getName() : ""));
        if (listener != null && fileToSend != null) {
            listener.onStopped(fileToSend.getName(), bytesSent);
        }
    }

    @Override
    public void run() {
        if (fileToSend == null || !fileToSend.exists() || !fileToSend.isFile()) {
            System.err.println("[TCP SENDER] File không tồn tại hoặc không hợp lệ!");
            if (listener != null) {
                listener.onError("Unknown", "File không hợp lệ", null);
            }
            return;
        }

        running = true;
        totalBytes = fileToSend.length();
        String fileName = fileToSend.getName();

        System.out.println("==================================================");
        System.out.println("[TCP SENDER] Đang kết nối tới server: " + targetIp + ":" + targetPort);
        System.out.println("[TCP SENDER] Chuẩn bị gửi file: " + fileName + " (" + totalBytes + " bytes)");
        System.out.println("==================================================");

        DataOutputStream dos = null;
        DataInputStream dis = null;
        RandomAccessFile raf = null;

        try {
            // 1. Tạo kết nối Socket TCP
            socket = new Socket(targetIp, targetPort);
            System.out.println("[TCP SENDER] Kết nối thành công: " + socket);

            dos = new DataOutputStream(socket.getOutputStream());
            dis = new DataInputStream(socket.getInputStream());

            // 2. Kiểm tra tính toàn vẹn: Tính mã băm SHA-1 của file trước khi gửi
            System.out.println("[TCP SENDER] Đang băm dữ liệu file (SHA-1)...");
            String fileHash = HashUtils.calculateFileSHA1(fileToSend);
            System.out.println("   - Mã SHA-1 file: " + fileHash);

            // 3. Gửi thông tin bắt tay (Handshake) sang Server
            dos.writeUTF(fileName);
            dos.writeLong(totalBytes);
            dos.writeUTF(fileHash);
            dos.flush();

            // 4. Nhận phản hồi offset từ Server (hỗ trợ Resume nếu tải dở)
            long startOffset = dis.readLong();
            if (startOffset < 0 || startOffset > totalBytes) {
                startOffset = 0;
            }
            bytesSent = startOffset;
            System.out.println("[TCP SENDER] Vị trí bắt đầu truyền: byte " + startOffset + "/" + totalBytes);

            if (listener != null) {
                listener.onStart(fileName, totalBytes, startOffset);
            }

            // 5. Mở file bằng RandomAccessFile và nhảy tới vị trí startOffset
            raf = new RandomAccessFile(fileToSend, "r");
            if (startOffset > 0) {
                raf.seek(startOffset);
            }

            // 6. Phân mảnh (Chunking): Đọc từng mảnh byte nhỏ và đẩy qua socket
            byte[] buffer = new byte[CHUNK_SIZE];
            long lastTime = System.currentTimeMillis();
            long lastBytes = bytesSent;

            while (running && bytesSent < totalBytes) {
                // Xử lý tạm dừng nếu đang bật pause
                while (paused && running) {
                    Thread.sleep(150);
                }

                if (!running) break;

                int toRead = (int) Math.min(buffer.length, totalBytes - bytesSent);
                int read = raf.read(buffer, 0, toRead);
                if (read == -1) break;

                dos.write(buffer, 0, read);
                bytesSent += read;

                // Cập nhật tiến độ định kỳ
                long now = System.currentTimeMillis();
                if (now - lastTime >= 150 || bytesSent >= totalBytes) {
                    int percent = (int) (bytesSent * 100 / totalBytes);
                    double speedMBs = 0.0;
                    if (now > lastTime) {
                        speedMBs = ((bytesSent - lastBytes) / 1024.0 / 1024.0) / ((now - lastTime) / 1000.0);
                    }
                    if (listener != null) {
                        listener.onProgress(fileName, bytesSent, totalBytes, percent, speedMBs);
                    }
                    lastTime = now;
                    lastBytes = bytesSent;
                }
            }

            dos.flush();
            raf.close();
            raf = null;

            // 7. Chờ xác nhận tính toàn vẹn từ Server
            if (running && bytesSent >= totalBytes) {
                String response = dis.readUTF();
                if ("SUCCESS".equals(response)) {
                    System.out.println("[TCP SENDER] => TRUYỀN FILE THÀNH CÔNG VÀ TOÀN VẸN: " + fileName);
                    if (listener != null) {
                        listener.onCompleted(fileName, totalBytes);
                    }
                } else {
                    System.err.println("[TCP SENDER] => Máy nhận thông báo file lỗi: " + response);
                    if (listener != null) {
                        listener.onError(fileName, "Máy nhận báo lỗi: " + response, null);
                    }
                }
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
                try { raf.close(); } catch (IOException ignored) {}
            }
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ignored) {}
            }
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

    public File getFileToSend() {
        return fileToSend;
    }

    /**
     * Hàm main chạy trực tiếp Client gửi file (tương tự như EchoChatClient mẫu).
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("=== KHỞI ĐỘNG TCP FILE SENDER (NHIỆM VỤ 3 - HUÂN) ===");

        // Tạo 1 file test nhỏ nếu chưa có
        File testFile = new File("test_send.txt");
        if (!testFile.exists()) {
            try (FileWriter writer = new FileWriter(testFile)) {
                for (int i = 1; i <= 500; i++) {
                    writer.write("Dong du lieu test thu " + i + " cho du an P2P File Sharing - Nhiem vu 3 cua Huan\n");
                }
            }
            System.out.println("Đã tự động tạo file mẫu: " + testFile.getAbsolutePath());
        }

        // Kết nối tới Server tại 127.0.0.1 cổng 9000
        FileSender sender = new FileSender("127.0.0.1", 9000, testFile);
        // Chạy trực tiếp để quan sát console
        sender.run();
    }
}
