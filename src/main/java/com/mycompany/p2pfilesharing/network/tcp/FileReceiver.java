package com.mycompany.p2pfilesharing.network.tcp;

import com.mycompany.p2pfilesharing.utils.HashUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP Server - Lắng nghe, nhận luồng byte và lưu xuống ổ cứng.
 * Hỗ trợ phân mảnh (Chunking), kiểm tra toàn vẹn (SHA-1/MD5) và tạm dừng/tiếp
 * tục (Resume/Pause).
 */
public class FileReceiver implements Runnable {

    // Cổng mặc định
    public static final int DEFAULT_PORT = 9000;
    // Kích thước mỗi mảnh dữ liệu (Chunk size: 64 KB)
    public static final int CHUNK_SIZE = 64 * 1024;

    // Interface lắng nghe sự kiện nhận file (dành cho GUI của Ngân kết nối)
    public interface FileReceiverListener {
        default void onStart(String fileName, String senderIp, long totalBytes, long startOffset) {
        }

        default void onProgress(String fileName, long bytesReceived, long totalBytes, int percent, double speedMBs) {
        }

        default void onPaused(String fileName, long currentBytes) {
        }

        default void onResumed(String fileName, long currentBytes) {
        }

        default void onCompleted(String fileName, File savedFile, long totalBytes) {
        }

        default void onStopped(String fileName, long bytesReceived) {
        }

        default void onError(String fileName, String errorMessage, Exception ex) {
        }
    }

    private int port;
    private File saveDirectory;
    private volatile boolean running = false;
    private volatile boolean paused = false;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private FileReceiverListener listener;
    private volatile Socket currentClientSocket;
    private volatile String currentReceivingFileName = "";
    private volatile long currentBytesReceived = 0;

    public FileReceiver() {
        this(DEFAULT_PORT, new File("./received_files"), null);
    }

    public FileReceiver(int port) {
        this(port, new File("./received_files"), null);
    }

    public FileReceiver(int port, File saveDirectory) {
        this(port, saveDirectory, null);
    }

    public FileReceiver(int port, File saveDirectory, FileReceiverListener listener) {
        this.port = port;
        this.saveDirectory = (saveDirectory != null) ? saveDirectory : new File("./received_files");
        this.listener = listener;
        if (!this.saveDirectory.exists()) {
            this.saveDirectory.mkdirs();
        }
    }

    public void setListener(FileReceiverListener listener) {
        this.listener = listener;
    }

    public void setSaveDirectory(File saveDirectory) {
        this.saveDirectory = saveDirectory;
        if (this.saveDirectory != null && !this.saveDirectory.exists()) {
            this.saveDirectory.mkdirs();
        }
    }

    public File getSaveDirectory() {
        return saveDirectory;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Bật Server nhận file trong một luồng nền.
     */
    public synchronized void start() {
        if (running)
            return;
        running = true;
        paused = false;
        serverThread = new Thread(this, "FileReceiver-Thread");
        serverThread.start();
    }

    /**
     * Tạm dừng tiến trình nhận file.
     */
    public void pause() {
        this.paused = true;
        System.out.println("[TCP RECEIVER] Đã tạm dừng nhận file: " + currentReceivingFileName);
        if (listener != null && !currentReceivingFileName.isEmpty()) {
            listener.onPaused(currentReceivingFileName, currentBytesReceived);
        }
    }

    /**
     * Tiếp tục tiến trình nhận file.
     */
    public void resume() {
        this.paused = false;
        System.out.println("[TCP RECEIVER] Tiếp tục nhận file: " + currentReceivingFileName);
        if (listener != null && !currentReceivingFileName.isEmpty()) {
            listener.onResumed(currentReceivingFileName, currentBytesReceived);
        }
    }

    /**
     * Dừng Server và đóng Socket.
     */
    public synchronized void stop() {
        running = false;
        paused = false;
        if (currentClientSocket != null && !currentClientSocket.isClosed()) {
            try {
                currentClientSocket.close();
            } catch (IOException ignored) {}
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi đóng ServerSocket: " + e.getMessage());
        }
        System.out.println("[TCP RECEIVER] Đã dừng server nhận file.");
    }

    @Override
    public void run() {
        running = true;
        try {
            if (!saveDirectory.exists()) {
                saveDirectory.mkdirs();
            }

            serverSocket = new ServerSocket(port);
            System.out.println("==================================================");
            System.out.println("[TCP RECEIVER] Server đã mở tại cổng " + port);
            System.out.println("[TCP RECEIVER] Thư mục lưu file: " + saveDirectory.getAbsolutePath());
            System.out.println("[TCP RECEIVER] Đang chờ client kết nối để gửi file...");
            System.out.println("==================================================");

            while (running) {
                try {
                    // Chấp nhận kết nối từ Client gửi file
                    Socket socket = serverSocket.accept();
                    String clientIp = socket.getInetAddress().getHostAddress();
                    System.out.println("\n[TCP RECEIVER] Client đã kết nối: " + socket);

                    // Xử lý việc nhận file từ socket vừa kết nối
                    handleReceiveFile(socket, clientIp);

                } catch (IOException e) {
                    if (running) {
                        System.err.println("[TCP RECEIVER] Lỗi khi nhận kết nối: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[TCP RECEIVER] Không thể khởi tạo ServerSocket: " + e.getMessage());
            }
        } finally {
            stop();
        }
    }

    /**
     * Xử lý luồng nhận dữ liệu file qua TCP Socket.
     */
    private void handleReceiveFile(Socket socket, String clientIp) {
        DataInputStream dis = null;
        DataOutputStream dos = null;
        RandomAccessFile raf = null;
        String fileName = "";

        try {
            currentClientSocket = socket;
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());

            // 1. Nhận thông tin bắt tay (Handshake) từ Client
            fileName = dis.readUTF();
            // Lọc tên file để tránh các ký tự đường dẫn bất thường
            fileName = new File(fileName).getName();
            currentReceivingFileName = fileName;

            long totalBytes = dis.readLong();
            String expectedHash = dis.readUTF();

            System.out.println("[TCP RECEIVER] Nhận yêu cầu gửi file:");
            System.out.println("   - Tên file   : " + fileName);
            System.out.println("   - Kích thước : " + totalBytes + " bytes");
            System.out.println("   - Hash gửi kèm: " + expectedHash);

            File targetFile = new File(saveDirectory, fileName);
            long startOffset = 0;

            // 2. Tạm dừng / Tiếp tục (Pause/Resume): Kiểm tra byte đã có trên đĩa
            if (targetFile.exists()) {
                long currentLen = targetFile.length();
                if (currentLen > 0 && currentLen < totalBytes) {
                    startOffset = currentLen;
                    System.out.println("[TCP RECEIVER] Phát hiện file dở dang. Tiếp tục tải từ byte: " + startOffset);
                } else if (currentLen >= totalBytes) {
                    // Nếu file đã đầy đủ trước đó thì ghi mới
                    targetFile.delete();
                    startOffset = 0;
                }
            }

            // Gửi vị trí byte offset phản hồi lại cho Client biết
            dos.writeLong(startOffset);
            dos.flush();

            if (listener != null) {
                listener.onStart(fileName, clientIp, totalBytes, startOffset);
            }

            // 3. Mở file bằng RandomAccessFile và nhảy đến vị trí offset để ghi nối tiếp
            raf = new RandomAccessFile(targetFile, "rw");
            if (startOffset == 0) {
                raf.setLength(0); // Đảm bảo làm sạch file cũ nếu ghi mới từ đầu
            } else {
                raf.seek(startOffset);
            }

            // 4. Phân mảnh (Chunking): Nhận từng mảnh byte nhỏ và ghi xuống đĩa
            byte[] buffer = new byte[CHUNK_SIZE];
            long bytesReceived = startOffset;
            currentBytesReceived = bytesReceived;
            long lastTime = System.currentTimeMillis();
            long lastBytes = bytesReceived;

            while (running && bytesReceived < totalBytes) {
                // Xử lý tạm dừng nếu có cờ pause
                while (paused && running) {
                    Thread.sleep(150);
                }

                int toRead = (int) Math.min(buffer.length, totalBytes - bytesReceived);
                int read = dis.read(buffer, 0, toRead);
                if (read == -1) {
                    break; // Kết nối bị ngắt
                }

                raf.write(buffer, 0, read);
                bytesReceived += read;
                currentBytesReceived = bytesReceived;

                // Tính toán tiến độ (%) và tốc độ (MB/s)
                long now = System.currentTimeMillis();
                if (now - lastTime >= 150 || bytesReceived >= totalBytes) {
                    int percent = (int) (bytesReceived * 100 / totalBytes);
                    double speedMBs = 0.0;
                    if (now > lastTime) {
                        speedMBs = ((bytesReceived - lastBytes) / 1024.0 / 1024.0) / ((now - lastTime) / 1000.0);
                    }
                    if (listener != null) {
                        listener.onProgress(fileName, bytesReceived, totalBytes, percent, speedMBs);
                    }
                    lastTime = now;
                    lastBytes = bytesReceived;
                }
            }

            raf.close();
            raf = null;

            // 5. Kiểm tra tính toàn vẹn (Checksum Hash)
            if (bytesReceived >= totalBytes) {
                System.out.println("[TCP RECEIVER] Đã nhận đủ " + bytesReceived + "/" + totalBytes + " bytes.");
                System.out.println("[TCP RECEIVER] Đang kiểm tra tính toàn vẹn file...");

                String actualHash = HashUtils.calculateFileSHA1(targetFile);
                System.out.println("   - Mã SHA-1 thực tế: " + actualHash);

                boolean isIntegrityValid = expectedHash.isEmpty() || HashUtils.checkIntegrity(expectedHash, actualHash);
                if (isIntegrityValid) {
                    System.out.println("[TCP RECEIVER] => KIỂM TRA TOÀN VẸN THÀNH CÔNG! (Hash trùng khớp)");
                    dos.writeUTF("SUCCESS");
                } else {
                    System.err.println("[TCP RECEIVER] => CẢNH BÁO: Mã hash không khớp, file có thể bị lỗi!");
                    dos.writeUTF("CORRUPTED");
                }
                dos.flush();

                if (listener != null) {
                    listener.onCompleted(fileName, targetFile, totalBytes);
                }
            } else {
                System.out.println("[TCP RECEIVER] Quá trình nhận file bị gián đoạn tại byte: " + bytesReceived);
                if (listener != null) {
                    listener.onStopped(fileName, bytesReceived);
                }
            }

        } catch (Exception e) {
            System.err.println("[TCP RECEIVER] Lỗi khi nhận file: " + e.getMessage());
            if (listener != null) {
                listener.onError(fileName, e.getMessage(), e);
            }
        } finally {
            currentClientSocket = null;
            currentReceivingFileName = "";
            currentBytesReceived = 0;
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Hàm main chạy trực tiếp Server nhận file (tương tự như EchoChatSingleServer
     * mẫu).
     */
    public static void main(String[] args) throws IOException {
        System.out.println("=== KHỞI ĐỘNG TCP FILE RECEIVER (NHIỆM VỤ 3 - HUÂN) ===");
        FileReceiver receiver = new FileReceiver(DEFAULT_PORT, new File("./received_files"));
        // Chạy trực tiếp trong luồng hiện tại để quan sát console
        receiver.run();
    }
}
