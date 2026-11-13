package com.mycompany.p2pfilesharing;

import com.mycompany.p2pfilesharing.model.Peer;
import com.mycompany.p2pfilesharing.network.tcp.FileReceiver;
import com.mycompany.p2pfilesharing.network.udp.BroadcastReceiver;
import com.mycompany.p2pfilesharing.network.udp.BroadcastSender;

public class P2pFileSharing {

    public static void main(String[] args) {

        // Cho phép nhận cấu hình từ dòng lệnh nếu có (thuận tiện test nhiều máy trên cùng PC)
        String peerName = (args.length > 0) ? args[0] : "MayTinhA";
        int tcpPort = (args.length > 1) ? Integer.parseInt(args[1]) : 9000;
        int udpPort = (args.length > 2) ? Integer.parseInt(args[2]) : 8888;

        System.out.println("==================================================");
        System.out.println("   KHỞI ĐỘNG ỨNG DỤNG P2P FILE SHARING");
        System.out.println("   - Tên thiết bị: " + peerName);
        System.out.println("   - Cổng TCP    : " + tcpPort);
        System.out.println("   - Cổng UDP    : " + udpPort);
        System.out.println("==================================================");

        // 1. Khởi tạo TCP File Receiver (lắng nghe nhận file đến từ các peer khác)
        FileReceiver fileReceiver = new FileReceiver(tcpPort);
        fileReceiver.start();

        // 2. Khởi tạo UDP Broadcast Receiver (khám phá danh sách peer trong mạng LAN)
        BroadcastReceiver receiver = new BroadcastReceiver(
                peerName,
                tcpPort,
                udpPort
        );

        // Nhận thông báo khi danh sách Peer thay đổi
        receiver.setOnPeerListChanged(peers -> {
            System.out.println("\n===== DANH SÁCH PEER ONLINE (" + peers.size() + ") =====");
            if (peers.isEmpty()) {
                System.out.println("Chưa phát hiện máy nào.");
            }
            for (Peer peer : peers) {
                System.out.println("Tên máy: " + peer.getPeerName()
                        + " | IP: " + peer.getIpAddress()
                        + " | TCP Port: " + peer.getTcpPort()
                        + " | Trạng thái: " + (peer.isOnline() ? "Online" : "Offline"));
            }
            System.out.println("----------------------------------------------");
        });

        // 3. Khởi tạo UDP Broadcast Sender (định kỳ phát tín hiệu giới thiệu bản thân)
        BroadcastSender sender = new BroadcastSender(
                peerName,
                tcpPort,
                udpPort
        );

        // Bật Receiver trước rồi bật Sender
        receiver.start();
        sender.start();

        System.out.println("P2P Discovery & TCP Receiver đã khởi động thành công.");

        // Dừng các luồng mạng an toàn khi ứng dụng kết thúc
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    System.out.println("\nĐang dừng các dịch vụ mạng...");
                    sender.stop();
                    receiver.stop();
                    fileReceiver.stop();
                    System.out.println("Đã dừng hoàn tất P2P File Sharing.");
                })
        );
    }
}
