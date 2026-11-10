package com.mycompany.p2pfilesharing.network.udp;

import com.mycompany.p2pfilesharing.model.Peer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class BroadcastReceiver implements Runnable {

    // Cổng UDP nhận thông tin từ các Peer
    private final int udpPort;

    // Tên máy hiện tại
    private final String ownPeerName;

    // Cổng TCP của máy hiện tại
    private final int ownTcpPort;

    // Danh sách các Peer đang được phát hiện
    private final Map<String, Peer> peerMap =
            new ConcurrentHashMap<>();

    // Thời gian tối đa không nhận được tín hiệu
    private static final long PEER_TIMEOUT = 15000;

    private volatile boolean running;

    private DatagramSocket socket;
    private Thread receiverThread;

    // Callback để thông báo khi danh sách Peer thay đổi
    private Consumer<List<Peer>> onPeerListChanged;

    public BroadcastReceiver(
            String ownPeerName,
            int ownTcpPort,
            int udpPort
    ) {
        this.ownPeerName = ownPeerName;
        this.ownTcpPort = ownTcpPort;
        this.udpPort = udpPort;
        this.running = false;
    }

    // Cho phép giao diện nhận danh sách Peer mới
    public void setOnPeerListChanged(
            Consumer<List<Peer>> listener
    ) {
        this.onPeerListChanged = listener;
    }

    // Khởi động tiến trình nhận Broadcast
    public void start() {

        if (running) {
            return;
        }

        running = true;

        receiverThread = new Thread(
                this,
                "UDP-Broadcast-Receiver"
        );

        receiverThread.start();
    }

    @Override
    public void run() {

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);

            socket.bind(
                    new java.net.InetSocketAddress(udpPort)
            );

            // Giới hạn thời gian chờ nhận dữ liệu
            socket.setSoTimeout(1000);

            System.out.println(
                    "[UDP RECEIVER] Đang lắng nghe tại cổng "
                            + udpPort
            );

            while (running) {

                try {
                    byte[] buffer = new byte[1024];

                    DatagramPacket packet =
                            new DatagramPacket(
                                    buffer,
                                    buffer.length
                            );

                    // Nhận gói tin UDP
                    socket.receive(packet);

                    String message = new String(
                            packet.getData(),
                            packet.getOffset(),
                            packet.getLength(),
                            StandardCharsets.UTF_8
                    );

                    // Xử lý thông tin Peer vừa nhận
                    processMessage(
                            message,
                            packet.getAddress()
                    );

                } catch (java.net.SocketTimeoutException e) {
                    // Không nhận được gói tin trong 1 giây.
                    // Tiếp tục vòng lặp để kiểm tra Peer hết hạn.
                }

                // Kiểm tra và loại bỏ Peer đã mất kết nối
                removeExpiredPeers();
            }

        } catch (SocketException e) {

            if (running) {
                System.err.println(
                        "[UDP RECEIVER] Lỗi Socket: "
                                + e.getMessage()
                );
            }

        } catch (Exception e) {

            if (running) {
                System.err.println(
                        "[UDP RECEIVER] Lỗi: "
                                + e.getMessage()
                );
            }

        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // Phân tích thông điệp nhận được
    private void processMessage(
            String message,
            InetAddress senderAddress
    ) {

        try {

            String[] parts = message.split("\\|", -1);

            // Kiểm tra cấu trúc thông điệp
            if (parts.length != 4) {
                return;
            }

            if (!"PEER".equals(parts[0])) {
                return;
            }

            String peerName = parts[1].trim();
            String advertisedIp = parts[2].trim();
            int tcpPort = Integer.parseInt(parts[3].trim());

            if (peerName.isEmpty()
                    || tcpPort < 1
                    || tcpPort > 65535) {
                return;
            }

            // Dùng IP nguồn của gói UDP để xác định máy gửi
            String ipAddress =
                    senderAddress.getHostAddress();

            // Bỏ qua thông điệp của chính máy hiện tại
            if (peerName.equals(ownPeerName)
                    && tcpPort == ownTcpPort) {
                return;
            }

            // Tạo khóa nhận diện Peer
            String peerKey = ipAddress + ":" + tcpPort;

            Peer peer = peerMap.get(peerKey);

            if (peer == null) {

                // Peer mới được phát hiện
                peer = new Peer(
                        peerName,
                        ipAddress,
                        tcpPort
                );

                peerMap.put(peerKey, peer);

                System.out.println(
                        "[DISCOVERY] Phát hiện Peer mới: "
                                + peer
                );

            } else {

                // Peer đã tồn tại, cập nhật thông tin
                peer.setPeerName(peerName);
                peer.setIpAddress(ipAddress);
                peer.setTcpPort(tcpPort);
                peer.updateLastSeen();
            }

            notifyPeerListChanged();

        } catch (NumberFormatException e) {

            System.err.println(
                    "[DISCOVERY] Cổng TCP không hợp lệ."
            );

        } catch (Exception e) {

            System.err.println(
                    "[DISCOVERY] Không thể xử lý thông điệp: "
                            + e.getMessage()
            );
        }
    }

    // Xóa Peer không gửi tín hiệu trong thời gian quy định
    private void removeExpiredPeers() {

        boolean changed = false;

        for (Map.Entry<String, Peer> entry
                : peerMap.entrySet()) {

            Peer peer = entry.getValue();

            if (peer.isExpired(PEER_TIMEOUT)) {

                if (peerMap.remove(
                        entry.getKey(),
                        peer
                )) {

                    peer.setOffline();

                    System.out.println(
                            "[DISCOVERY] Peer đã mất kết nối: "
                                    + peer
                    );

                    changed = true;
                }
            }
        }

        if (changed) {
            notifyPeerListChanged();
        }
    }

    // Lấy danh sách Peer hiện tại
    public List<Peer> getPeers() {
        return new ArrayList<>(peerMap.values());
    }

    // Thông báo danh sách mới cho giao diện
    private void notifyPeerListChanged() {

        if (onPeerListChanged != null) {

            List<Peer> currentPeers = getPeers();

            onPeerListChanged.accept(currentPeers);
        }
    }

    // Dừng tiến trình nhận
    public void stop() {

        running = false;

        if (socket != null) {
            socket.close();
        }

        if (receiverThread != null) {
            receiverThread.interrupt();
        }
    }
}