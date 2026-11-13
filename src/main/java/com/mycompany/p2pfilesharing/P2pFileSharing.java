package com.mycompany.p2pfilesharing;

import com.mycompany.p2pfilesharing.gui.MainFrameListener;
import com.mycompany.p2pfilesharing.gui.MainFrameUI;
import com.mycompany.p2pfilesharing.model.Peer;
import com.mycompany.p2pfilesharing.network.tcp.FileReceiver;
import com.mycompany.p2pfilesharing.network.tcp.FileSender;
import com.mycompany.p2pfilesharing.network.udp.BroadcastReceiver;
import com.mycompany.p2pfilesharing.network.udp.BroadcastSender;
import com.mycompany.p2pfilesharing.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * Lớp điều phối trung tâm (Wiring Logic) của toàn bộ dự án P2P File Sharing:
 * - Khởi tạo Giao diện (GUI - Ngân)
 * - Khởi tạo Khám phá mạng LAN (UDP Broadcast - Quân)
 * - Khởi tạo Máy chủ & Khách truyền tải file (TCP Transfer - Huân)
 * - Xử lý nén/giải nén thư mục (ZipUtils - Hoàng)
 */
public class P2pFileSharing {

    // Tham chiếu phiên gửi/nhận đang hoạt động để điều khiển Tạm dừng / Tiếp tục / Hủy
    private static volatile FileSender activeSender;
    private static volatile FileReceiver activeReceiver;

    public static void main(String[] args) {

        // 1. Cấu hình thiết bị ban đầu (có thể nhận qua tham số args để tiện test)
        String peerName = (args.length > 0) ? args[0] : "PC-" + (System.currentTimeMillis() % 1000);
        int tcpPort = (args.length > 1) ? Integer.parseInt(args[1]) : 9000;
        int udpPort = (args.length > 2) ? Integer.parseInt(args[2]) : 8888;

        File defaultSaveDir = new File(System.getProperty("user.home"), "P2PShare");
        if (!defaultSaveDir.exists()) {
            defaultSaveDir.mkdirs();
        }

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        // 2. Khởi tạo giao diện Swing (MainFrameUI - Không dùng dữ liệu demo ảo)
        MainFrameUI ui = new MainFrameUI();
        ui.setVisible(true);
        ui.addLog("Khởi động hệ thống P2P thành công. Tên máy: " + peerName + ", Cổng TCP: " + tcpPort);

        // 3. Khởi tạo TCP File Receiver (lắng nghe file đến và hiển thị lên tiến trình Slot 2 trên GUI)
        activeReceiver = new FileReceiver(tcpPort, defaultSaveDir, new FileReceiver.FileReceiverListener() {
            @Override
            public void onStart(String fileName, String senderIp, long totalBytes, long startOffset) {
                SwingUtilities.invokeLater(() -> {
                    ui.addLog("Đang nhận file từ " + senderIp + ": " + fileName);
                    int percent = (totalBytes > 0) ? (int) (startOffset * 100 / totalBytes) : 0;
                    ui.showTransfer(2, fileName, false, percent,
                            MainFrameUI.formatSize(startOffset) + " / " + MainFrameUI.formatSize(totalBytes), senderIp);
                });
            }

            @Override
            public void onProgress(String fileName, long bytesReceived, long totalBytes, int percent, double speedMBs) {
                SwingUtilities.invokeLater(() -> {
                    String info = MainFrameUI.formatSize(bytesReceived) + " / " + MainFrameUI.formatSize(totalBytes)
                            + String.format(" (%.1f MB/s)", speedMBs);
                    ui.showTransfer(2, fileName, false, percent, info, "Đang nhận...");
                });
            }

            @Override
            public void onCompleted(String fileName, File savedFile, long totalBytes) {
                SwingUtilities.invokeLater(() -> {
                    ui.addLog("Nhận file thành công và kiểm tra toàn vẹn khớp: " + fileName);
                    ui.addTransferRecord(fileName, "Máy gửi", MainFrameUI.formatSize(totalBytes), "Hoàn tất");
                    ui.clearTransfer(2);
                });
            }

            @Override
            public void onStopped(String fileName, long bytesReceived) {
                SwingUtilities.invokeLater(() -> {
                    ui.addLog("Đã dừng nhận file: " + fileName);
                    ui.clearTransfer(2);
                });
            }

            @Override
            public void onError(String fileName, String errorMessage, Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    ui.addLog("Lỗi nhận file " + fileName + ": " + errorMessage);
                    ui.clearTransfer(2);
                });
            }
        });
        activeReceiver.start();

        // 4. Khởi tạo UDP Broadcast Receiver (lắng nghe danh sách Peer trong mạng và đẩy vào bảng GUI)
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver(peerName, tcpPort, udpPort);
        broadcastReceiver.setOnPeerListChanged(peers -> {
            SwingUtilities.invokeLater(() -> {
                ui.clearPeers();
                for (Peer p : peers) {
                    if (p.isOnline()) {
                        ui.addOrUpdatePeer(p.getPeerName(), p.getIpAddress(), p.getTcpPort(), "Online");
                    }
                }
            });
        });
        broadcastReceiver.start();

        // 5. Khởi tạo UDP Broadcast Sender (phát tín hiệu nhận diện định kỳ ra mạng LAN)
        BroadcastSender broadcastSender = new BroadcastSender(peerName, tcpPort, udpPort);
        broadcastSender.start();

        // 6. Lắp ráp logic sự kiện từ Giao diện (MainFrameListener)
        ui.setListener(new MainFrameListener() {
            @Override
            public void onSendRequested(List<File> files, String targetPeerName, String peerIp, int targetPort) {
                if (files == null || files.isEmpty()) {
                    ui.addLog("Chưa chọn file nào để gửi.");
                    return;
                }

                // Chạy tác vụ gửi file trong luồng riêng để tránh làm đơ giao diện Swing
                new Thread(() -> {
                    for (File originalFile : files) {
                        File fileToSend = originalFile;

                        // Nếu là thư mục: tự động nén thành file .zip trước khi truyền (Nhiệm vụ 4 - Hoàng)
                        if (originalFile.isDirectory()) {
                            ui.addLog("Đang nén thư mục: " + originalFile.getName() + "...");
                            File zipFile = new File(originalFile.getParentFile(), originalFile.getName() + ".zip");
                            try {
                                ZipUtils.zipFolder(originalFile, zipFile);
                                fileToSend = zipFile;
                                ui.addLog("Nén xong thành công: " + zipFile.getName());
                            } catch (IOException ex) {
                                ui.addLog("Lỗi nén thư mục: " + ex.getMessage());
                                continue;
                            }
                        }

                        // Khởi tạo luồng TCP Client gửi file (Nhiệm vụ 3 - Huân)
                        activeSender = new FileSender(peerIp, targetPort, fileToSend, peerName, new FileSender.FileSenderListener() {
                            @Override
                            public void onStart(String fileName, long totalBytes, long startOffset) {
                                SwingUtilities.invokeLater(() -> {
                                    ui.addLog("Bắt đầu gửi tới " + targetPeerName + ": " + fileName);
                                    int percent = (totalBytes > 0) ? (int) (startOffset * 100 / totalBytes) : 0;
                                    ui.showTransfer(1, fileName, true, percent,
                                            MainFrameUI.formatSize(startOffset) + " / " + MainFrameUI.formatSize(totalBytes), targetPeerName);
                                });
                            }

                            @Override
                            public void onProgress(String fileName, long bytesSent, long totalBytes, int percent, double speedMBs) {
                                SwingUtilities.invokeLater(() -> {
                                    String info = MainFrameUI.formatSize(bytesSent) + " / " + MainFrameUI.formatSize(totalBytes)
                                            + String.format(" (%.1f MB/s)", speedMBs);
                                    ui.showTransfer(1, fileName, true, percent, info, targetPeerName);
                                });
                            }

                            @Override
                            public void onCompleted(String fileName, long totalBytes) {
                                SwingUtilities.invokeLater(() -> {
                                    ui.addLog("Đã gửi thành công: " + fileName);
                                    ui.addTransferRecord(fileName, targetPeerName, MainFrameUI.formatSize(totalBytes), "Thành công");
                                    ui.clearTransfer(1);
                                });
                            }

                            @Override
                            public void onPaused(String fileName, long currentBytes) {
                                SwingUtilities.invokeLater(() -> ui.addLog("Đã tạm dừng gửi: " + fileName));
                            }

                            @Override
                            public void onResumed(String fileName, long currentBytes) {
                                SwingUtilities.invokeLater(() -> ui.addLog("Tiếp tục gửi: " + fileName));
                            }

                            @Override
                            public void onStopped(String fileName, long bytesSent) {
                                SwingUtilities.invokeLater(() -> {
                                    ui.addLog("Đã dừng gửi: " + fileName);
                                    ui.clearTransfer(1);
                                });
                            }

                            @Override
                            public void onError(String fileName, String errorMessage, Exception ex) {
                                SwingUtilities.invokeLater(() -> {
                                    ui.addLog("Lỗi khi gửi " + fileName + ": " + errorMessage);
                                    ui.clearTransfer(1);
                                });
                            }
                        });

                        // Tiến hành gửi file thật qua Socket TCP
                        activeSender.run();
                        activeSender = null;
                    }
                }, "P2P-FileSender-Thread").start();
            }

            @Override
            public void onPauseResume(int slot) {
                if (slot == 1 && activeSender != null) {
                    if (activeSender.isPaused()) {
                        activeSender.resume();
                    } else {
                        activeSender.pause();
                    }
                } else if (slot == 2 && activeReceiver != null) {
                    if (activeReceiver.isPaused()) {
                        activeReceiver.resume();
                    } else {
                        activeReceiver.pause();
                    }
                }
            }

            @Override
            public void onStop(int slot) {
                if (slot == 1 && activeSender != null) {
                    activeSender.stop();
                } else if (slot == 2 && activeReceiver != null) {
                    activeReceiver.stop();
                }
            }

            @Override
            public void onRefreshPeers() {
                ui.addLog("Đang làm mới danh sách peer...");
            }
        });

        // 7. Đóng các socket an toàn khi tắt cửa sổ hoặc thoát chương trình
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Đang tắt các dịch vụ mạng...");
            broadcastSender.stop();
            broadcastReceiver.stop();
            if (activeReceiver != null) {
                activeReceiver.stop();
            }
            if (activeSender != null) {
                activeSender.stop();
            }
        }));
    }
}
