package com.mycompany.p2pfilesharing.model;

import java.util.ArrayList;
import java.util.List;

public class Peer {

    private String peerName;
    private String ipAddress;
    private int tcpPort;
    private boolean isOnline;
    private List<String> sharedFiles;

    // Thời điểm cuối cùng nhận được thông tin từ Peer
    private long lastSeenTime;

    public Peer(String peerName, String ipAddress, int tcpPort) {
        this.peerName = peerName;
        this.ipAddress = ipAddress;
        this.tcpPort = tcpPort;
        this.isOnline = true;
        this.sharedFiles = new ArrayList<>();
        this.lastSeenTime = System.currentTimeMillis();
    }

    // Cập nhật thời gian nhận được tín hiệu mới
    public void updateLastSeen() {
        this.lastSeenTime = System.currentTimeMillis();
        this.isOnline = true;
    }

    // Kiểm tra Peer có hết thời gian chờ hay không
    public boolean isExpired(long timeout) {
        return System.currentTimeMillis() - lastSeenTime > timeout;
    }

    // Đánh dấu Peer ngoại tuyến
    public void setOffline() {
        this.isOnline = false;
    }

    public String getPeerName() {
        return peerName;
    }

    public void setPeerName(String peerName) {
        this.peerName = peerName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public boolean isOnline() {
        return isOnline;
    }

    public void setOnline(boolean online) {
        this.isOnline = online;
    }

    public List<String> getSharedFiles() {
        return sharedFiles;
    }

    public void setSharedFiles(List<String> sharedFiles) {
        this.sharedFiles = sharedFiles;
    }

    public long getLastSeenTime() {
        return lastSeenTime;
    }

    @Override
    public String toString() {
        return peerName + " (" + ipAddress + ":" + tcpPort + ")";
    }
}