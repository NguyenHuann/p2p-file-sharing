package com.mycompany.p2pfilesharing.network.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileReceiver implements Runnable {

    public interface FileReceiverListener {
        // Khi phát hiện kết nối gửi file mới */
        default void onTransferStarted(String fileName, String senderPeerName, String senderIp, long totalBytes, long startOffset) {}

        // Cập nhật tiến trình nhận dữ liệu */
        default void onProgress(String fileName, long bytesReceived, long totalBytes, int percent, double speedMBs) {}

        // Khi phiên nhận file bị tạm dừng */
        default void onPaused(String fileName, long currentBytes) {}

        // Khi phiên nhận file được tiếp tục */
        default void onResumed(String fileName, long currentBytes) {}

        // Khi nhận file hoàn tất thành công và lưu vào ổ đĩa */
        default void onCompleted(String fileName, File savedFile, long totalBytes) {}

        // Khi phiên nhận file bị dừng / hủy */
        default void onStopped(String fileName, long bytesReceived) {}

        // Khi xảy ra lỗi */
        default void onError(String fileName, String errorMessage, Exception ex) {}
    }

    private static final int BUFFER_SIZE = 64 * 1024; // Phân mảnh đọc 64 KB

    private final int port;
    private volatile File saveDirectory;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    // Quản lý các phiên nhận file đang hoạt động */
    private final Map<String, ReceiveSession> activeSessions = new ConcurrentHashMap<>();
    private FileReceiverListener listener;

    public FileReceiver(int port) {
        this(port, new File(System.getProperty("user.home") + File.separator + "P2PShare"), null);
    }

    public FileReceiver(int port, File saveDirectory) {
        this(port, saveDirectory, null);
    }

    public FileReceiver(int port, File saveDirectory, FileReceiverListener listener) {
        this.port = port;
        this.saveDirectory = (saveDirectory != null) ? saveDirectory : new File(System.getProperty("user.home") + File.separator + "P2PShare");
        this.listener = listener;
        ensureSaveDirectoryExists();
    }

    public void setListener(FileReceiverListener listener) {
        this.listener = listener;
    }

    public void setSaveDirectory(File saveDirectory) {
        if (saveDirectory != null) {
            this.saveDirectory = saveDirectory;
            ensureSaveDirectoryExists();
        }
    }

    public File getSaveDirectory() {
        return saveDirectory;
    }

    public int getPort() {
        return port;
    }

    private void ensureSaveDirectoryExists() {
        if (saveDirectory != null && !saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }
    }

    // Khởi động Server TCP lắng nghe file đến.
    public synchronized void start() {
        if (running) {
            return;
        }
        ensureSaveDirectoryExists();
        running = true;
        serverThread = new Thread(this, "TCP-FileReceiver-Server-" + port);
        serverThread.start();
    }

    // Dừng Server TCP và tất cả các phiên nhận đang hoạt động.
     
    public synchronized void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        // Dừng tất cả các phiên nhận file
        for (ReceiveSession session : activeSessions.values()) {
            session.stop();
        }
        activeSessions.clear();

        threadPool.shutdownNow();

        if (serverThread != null) {
            serverThread.interrupt();
        }
        System.out.println("[TCP RECEIVER] Đã dừng máy chủ nhận file tại cổng " + port);
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("[TCP RECEIVER] Đang lắng nghe nhận file tại cổng TCP " + port + ", thư mục lưu: " + saveDirectory.getAbsolutePath());

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    String remoteIp = clientSocket.getInetAddress().getHostAddress();
                    System.out.println("[TCP RECEIVER] Chấp nhận kết nối từ: " + remoteIp);

                    // Xử lý từng kết nối gửi file trong một luồng riêng
                    threadPool.submit(new ClientHandler(clientSocket));
                } catch (SocketException se) {
                    if (!running) {
                        break; // Server socket bị đóng có chủ đích
                    }
                    System.err.println("[TCP RECEIVER] Lỗi Socket: " + se.getMessage());
                } catch (IOException ioe) {
                    if (running) {
                        System.err.println("[TCP RECEIVER] Lỗi khi chấp nhận kết nối: " + ioe.getMessage());
                    }
                }
            }

        } catch (IOException e) {
            if (running) {
                System.err.println("[TCP RECEIVER] Không thể khởi động Server Socket tại cổng " + port + ": " + e.getMessage());
            }
        } finally {
            running = false;
        }
    }

    // Tạm dừng một phiên nhận file theo tên file.
     
    public void pauseTransfer(String fileName) {
        ReceiveSession session = activeSessions.get(fileName);
        if (session != null) {
            session.pause();
        }
    }

    // Tiếp tục một phiên nhận file theo tên file.
     
    public void resumeTransfer(String fileName) {
        ReceiveSession session = activeSessions.get(fileName);
        if (session != null) {
            session.resume();
        }
    }

    // Dừng hẳn một phiên nhận file theo tên file.
     
    public void stopTransfer(String fileName) {
        ReceiveSession session = activeSessions.remove(fileName);
        if (session != null) {
            session.stop();
        }
    }

      // Lấy danh sách tên file đang trong quá trình nhận.
     
    public List<String> getActiveTransfers() {
        return new ArrayList<>(activeSessions.keySet());
    }

    // Lớp đại diện cho một phiên nhận file cụ thể.
     
    public class ReceiveSession {
        private final String fileName;
        private final String senderPeerName;
        private final String senderIp;
        private final long totalBytes;
        private volatile long bytesReceived;
        private volatile boolean paused = false;
        private volatile boolean stopped = false;
        private final Object pauseLock = new Object();
        private final Socket socket;
        private RandomAccessFile raf;

        public ReceiveSession(String fileName, String senderPeerName, String senderIp, long totalBytes, Socket socket) {
            this.fileName = fileName;
            this.senderPeerName = senderPeerName;
            this.senderIp = senderIp;
            this.totalBytes = totalBytes;
            this.socket = socket;
        }

        public void setRaf(RandomAccessFile raf) {
            this.raf = raf;
        }

        public void setBytesReceived(long bytesReceived) {
            this.bytesReceived = bytesReceived;
        }

        public void pause() {
            if (stopped || paused) return;
            paused = true;
            System.out.println("[TCP RECEIVER] Tạm dừng nhận file: " + fileName);
            if (listener != null) {
                listener.onPaused(fileName, bytesReceived);
            }
        }

        public void resume() {
            if (stopped || !paused) return;
            synchronized (pauseLock) {
                paused = false;
                pauseLock.notifyAll();
            }
            System.out.println("[TCP RECEIVER] Tiếp tục nhận file: " + fileName);
            if (listener != null) {
                listener.onResumed(fileName, bytesReceived);
            }
        }

        public void stop() {
            stopped = true;
            synchronized (pauseLock) {
                paused = false;
                pauseLock.notifyAll();
            }
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}
            System.out.println("[TCP RECEIVER] Hủy nhận file: " + fileName);
            if (listener != null) {
                listener.onStopped(fileName, bytesReceived);
            }
        }

        public boolean isPaused() {
            return paused;
        }

        public boolean isStopped() {
            return stopped;
        }

        public Object getPauseLock() {
            return pauseLock;
        }

        public String getFileName() {
            return fileName;
        }

        public long getBytesReceived() {
            return bytesReceived;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public String getSenderPeerName() {
            return senderPeerName;
        }

        public String getSenderIp() {
            return senderIp;
        }
    }

    // Xử lý một luồng gửi dữ liệu từ một client kết nối tới.
 
    private class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            String remoteIp = socket.getInetAddress().getHostAddress();
            RandomAccessFile raf = null;
            File partFile = null;
            String fileName = "unknown";
            ReceiveSession session = null;

            try {
                DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                // 1. Đọc Header bắt tay
                String magic = dis.readUTF();
                if (!"FILE_HEADER".equals(magic)) {
                    System.err.println("[TCP RECEIVER] Gói tin không hợp lệ từ " + remoteIp + ": " + magic);
                    dos.writeUTF("REJECT");
                    dos.flush();
                    return;
                }

                String senderPeerName = dis.readUTF();
                fileName = dis.readUTF();
                long totalBytes = dis.readLong();
                String expectedHash = dis.readUTF();

                ensureSaveDirectoryExists();

                // Sử dụng file tạm .part để hỗ trợ ghi nối tiếp (Resume)
                partFile = new File(saveDirectory, fileName + ".part");
                long startOffset = 0;

                // 2. Kiểm tra nếu file .part đã tồn tại trước đó để tiếp tục tải (Breakpoint Resume)
                if (partFile.exists() && partFile.isFile()) {
                    long existingLength = partFile.length();
                    if (existingLength > 0 && existingLength < totalBytes) {
                        startOffset = existingLength;
                        System.out.println("[TCP RECEIVER] Tìm thấy file dở dang (" + startOffset + "/" + totalBytes + " bytes). Tiếp tục nhận từ offset: " + startOffset);
                    } else if (existingLength >= totalBytes) {
                        // File tạm đã đủ hoặc lớn hơn kích cỡ file yêu cầu -> xóa làm lại
                        partFile.delete();
                        startOffset = 0;
                    }
                }

                // 3. Phản hồi cho máy gửi biết sẵn sàng và bắt đầu từ offset nào
                dos.writeUTF("READY");
                dos.writeLong(startOffset);
                dos.flush();

                // 4. Tạo và lưu phiên nhận
                session = new ReceiveSession(fileName, senderPeerName, remoteIp, totalBytes, socket);
                session.setBytesReceived(startOffset);
                activeSessions.put(fileName, session);

                if (listener != null) {
                    listener.onTransferStarted(fileName, senderPeerName, remoteIp, totalBytes, startOffset);
                }

                // 5. Mở file bằng RandomAccessFile ở chế độ "rw" và nhảy đến offset
                raf = new RandomAccessFile(partFile, "rw");
                session.setRaf(raf);
                if (startOffset > 0) {
                    raf.seek(startOffset);
                }

                byte[] buffer = new byte[BUFFER_SIZE];
                long bytesReceived = startOffset;
                long lastProgressTime = System.currentTimeMillis();
                long lastBytesForSpeed = bytesReceived;

                // 6. Nhận các mảnh dữ liệu và ghi vào file
                while (!session.isStopped() && bytesReceived < totalBytes) {
                    // Kiểm tra trạng thái Pause
                    synchronized (session.getPauseLock()) {
                        while (session.isPaused() && !session.isStopped()) {
                            session.getPauseLock().wait();
                        }
                    }

                    if (session.isStopped()) {
                        break;
                    }

                    int toRead = (int) Math.min(buffer.length, totalBytes - bytesReceived);
                    int read = dis.read(buffer, 0, toRead);
                    if (read == -1) {
                        throw new EOFException("Kết nối bị ngắt đột ngột từ máy gửi!");
                    }

                    raf.write(buffer, 0, read);
                    bytesReceived += read;
                    session.setBytesReceived(bytesReceived);

                    // Báo cáo tiến trình định kỳ
                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime >= 120 || bytesReceived >= totalBytes) {
                        long timeDelta = now - lastProgressTime;
                        double speedMBs = 0.0;
                        if (timeDelta > 0) {
                            speedMBs = ((bytesReceived - lastBytesForSpeed) / 1024.0 / 1024.0) / (timeDelta / 1000.0);
                        }
                        int percent = (totalBytes > 0) ? (int) (bytesReceived * 100 / totalBytes) : 100;

                        if (listener != null) {
                            listener.onProgress(fileName, bytesReceived, totalBytes, percent, speedMBs);
                        }

                        lastProgressTime = now;
                        lastBytesForSpeed = bytesReceived;
                    }
                }

                // 7. Hoàn tất nhận file
                if (!session.isStopped() && bytesReceived >= totalBytes) {
                    raf.close();
                    raf = null;

                    // Đổi tên file từ .part thành tên file chính thức
                    File finalFile = resolveDestinationFile(saveDirectory, fileName);
                    boolean renamed = partFile.renameTo(finalFile);
                    if (!renamed) {
                        // Thử sao chép nếu đổi tên thất bại
                        copyFile(partFile, finalFile);
                        partFile.delete();
                    }

                    // Kiểm tra tính toàn vẹn (Checksum SHA-256) nếu bên gửi có gửi hash
                    boolean hashMatched = true;
                    if (expectedHash != null && !expectedHash.trim().isEmpty()) {
                        String actualHash = calculateSHA256(finalFile);
                        if (!expectedHash.equalsIgnoreCase(actualHash)) {
                            hashMatched = false;
                            System.err.println("[TCP RECEIVER] CẢNH BÁO: Mã băm không khớp! Mong muốn: " + expectedHash + ", Thực tế: " + actualHash);
                        }
                    }

                    // Gửi xác nhận COMPLETE về máy gửi
                    dos.writeUTF("COMPLETE");
                    dos.flush();

                    System.out.println("[TCP RECEIVER] Nhận file thành công: " + finalFile.getName() + " (" + totalBytes + " bytes)" + (hashMatched ? " [Toàn vẹn]" : " [Cảnh báo hash]"));

                    if (listener != null) {
                        listener.onProgress(fileName, totalBytes, totalBytes, 100, 0.0);
                        listener.onCompleted(fileName, finalFile, totalBytes);
                    }
                }

            } catch (InterruptedException ie) {
                System.err.println("[TCP RECEIVER] Phiên nhận file " + fileName + " bị ngắt.");
                if (listener != null && session != null && !session.isStopped()) {
                    listener.onStopped(fileName, session.getBytesReceived());
                }
            } catch (EOFException eofe) {
                System.err.println("[TCP RECEIVER] Kết nối bị đóng sớm từ máy gửi cho file " + fileName + ": " + eofe.getMessage());
                if (listener != null && session != null && !session.isStopped()) {
                    listener.onStopped(fileName, session.getBytesReceived());
                }
            } catch (Exception e) {
                if (session == null || !session.isStopped()) {
                    System.err.println("[TCP RECEIVER] Lỗi khi nhận file " + fileName + ": " + e.getMessage());
                    if (listener != null) {
                        listener.onError(fileName, e.getMessage(), e);
                    }
                }
            } finally {
                if (session != null) {
                    activeSessions.remove(session.getFileName());
                }
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (IOException ignored) {}
                }
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    /**
     * Xác định tên file đích để tránh ghi đè file cũ (vd: file.txt -> file (1).txt).
     */
    private static File resolveDestinationFile(File dir, String fileName) {
        File target = new File(dir, fileName);
        if (!target.exists()) {
            return target;
        }

        String baseName = fileName;
        String extension = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = fileName.substring(0, dotIdx);
            extension = fileName.substring(dotIdx);
        }

        int count = 1;
        while (target.exists()) {
            target = new File(dir, baseName + " (" + count + ")" + extension);
            count++;
        }
        return target;
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             RandomAccessFile out = new RandomAccessFile(dest, "rw")) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
    }

    /**
     * Tính mã băm SHA-256 của file đã lưu để đối chiếu tính toàn vẹn.
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
}
