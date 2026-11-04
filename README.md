## Cấu trúc thư mục

```text
p2p-file-sharing/
└── src/
    └── com/
        └── mycompany/
            └── p2pfilesharing/
                ├── P2pFileSharing.java         // Điểm neo khởi chạy ứng dụng (chứa hàm main)
                ├── gui/                        // Nhiệm vụ của Ngân
                │   └── MainFrame.java          // Giao diện chính kéo thả bằng JFrame
                ├── model/                      // Dùng chung
                │   └── Peer.java               // Cấu trúc dữ liệu định danh (Tên, IP, Port)
                ├── network/
                │   ├── tcp/                    // Nhiệm vụ của Huân
                │   │   ├── FileSender.java     // Phân mảnh file và gửi qua Socket
                │   │   └── FileReceiver.java   // Ghi file (RandomAccessFile) và xử lý Pause/Resume
                │   └── udp/                    // Nhiệm vụ của Quân
                │       ├── BroadcastSender.java// Phát tín hiệu nhận diện vào mạng LAN
                │       └── BroadcastReceiver.java// Lắng nghe mạng và cập nhật thiết bị online
                └── utils/                      // Nhiệm vụ của Hoàng
                    ├── ZipUtils.java           // Xử lý nén/giải nén để gửi nguyên thư mục
                    └── HashUtils.java          // Băm MD5/SHA-1 để kiểm tra tính toàn vẹn

```
