package com.mycompany.p2pfilesharing;

import com.mycompany.p2pfilesharing.network.udp.BroadcastReceiver;
import com.mycompany.p2pfilesharing.network.udp.BroadcastSender;
import com.mycompany.p2pfilesharing.model.Peer;

public class P2pFileSharing {

    public static void main(String[] args) {

        String peerName = "MayTinhA";

        // Cổng TCP dành cho việc truyền file sau này
        int tcpPort = 9000;

        // Cổng UDP dành cho việc khám phá Peer
        int udpPort = 8888;

        // Khởi tạo Receiver
        BroadcastReceiver receiver =
                new BroadcastReceiver(
                        peerName,
                        tcpPort,
                        udpPort
                );

        // Nhận thông báo khi danh sách Peer thay đổi
        receiver.setOnPeerListChanged(peers -> {

            System.out.println("\n===== DANH SÁCH PEER =====");

            if (peers.isEmpty()) {
                System.out.println("Chưa phát hiện máy nào.");
            }

            for (Peer peer : peers) {
                System.out.println(
                        "Tên máy: " + peer.getPeerName()
                );

                System.out.println(
                        "IP: " + peer.getIpAddress()
                );

                System.out.println(
                        "TCP Port: " + peer.getTcpPort()
                );

                System.out.println(
                        "Trạng thái: "
                                + (peer.isOnline()
                                ? "Online" : "Offline")
                );

                System.out.println("-------------------------");
            }
        });

        // Khởi tạo Sender
        BroadcastSender sender =
                new BroadcastSender(
                        peerName,
                        tcpPort,
                        udpPort
                );

        // Bật Receiver trước
        receiver.start();

        // Sau đó bật Sender
        sender.start();

        System.out.println("P2P Discovery đã khởi động.");

        // Dừng các luồng khi chương trình kết thúc
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    sender.stop();
                    receiver.stop();

                    System.out.println(
                            "Đã dừng P2P Discovery."
                    );
                })
        );
    }
}