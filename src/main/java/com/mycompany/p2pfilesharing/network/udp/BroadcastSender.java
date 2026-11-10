package com.mycompany.p2pfilesharing.network.udp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class BroadcastSender implements Runnable {

    // Cổng UDP dùng để gửi thông tin khám phá Peer
    private final int udpPort;

    // Cổng TCP của chương trình để các máy khác kết nối
    private final int tcpPort;

    // Tên máy tính hiện tại
    private final String peerName;

    // Thời gian giữa hai lần gửi
    private static final long BROADCAST_INTERVAL = 3000;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread senderThread;

    public BroadcastSender(String peerName, int tcpPort, int udpPort) {
        this.peerName = peerName;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.running = false;
    }

    // Khởi động tiến trình gửi Broadcast
    public void start() {
        if (running) {
            return;
        }

        running = true;
        senderThread = new Thread(this, "UDP-Broadcast-Sender");
        senderThread.start();
    }

    @Override
    public void run() {

        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);

            // Địa chỉ Broadcast trong mạng
            InetAddress broadcastAddress =
                    InetAddress.getByName("255.255.255.255");

            while (running) {

                // Lấy địa chỉ IP của máy hiện tại
                String localIp = getLocalIpAddress();

                // Tạo thông điệp nhận diện Peer
                String message = "PEER|"
                        + peerName + "|"
                        + localIp + "|"
                        + tcpPort;

                byte[] data = message.getBytes(
                        StandardCharsets.UTF_8
                );

                // Đóng gói dữ liệu thành DatagramPacket
                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        broadcastAddress,
                        udpPort
                );

                // Gửi gói tin UDP Broadcast
                socket.send(packet);

                System.out.println(
                        "[UDP SENDER] Đã gửi: " + message
                );

                // Chờ trước khi gửi lần tiếp theo
                Thread.sleep(BROADCAST_INTERVAL);
            }

        } catch (Exception e) {
            if (running) {
                System.err.println(
                        "[UDP SENDER] Lỗi: " + e.getMessage()
                );
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // Lấy địa chỉ IP của máy tính
    private String getLocalIpAddress() {

        try (DatagramSocket tempSocket = new DatagramSocket()) {

            tempSocket.connect(
                    InetAddress.getByName("8.8.8.8"),
                    10002
            );

            return tempSocket.getLocalAddress().getHostAddress();

        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // Dừng tiến trình gửi
    public void stop() {
        running = false;

        if (socket != null) {
            socket.close();
        }

        if (senderThread != null) {
            senderThread.interrupt();
        }
    }
}