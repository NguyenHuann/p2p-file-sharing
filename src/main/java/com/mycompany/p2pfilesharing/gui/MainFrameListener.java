package com.mycompany.p2pfilesharing.gui;

import java.io.File;
import java.util.List;

/**
 * Cầu nối giữa GIAO DIỆN và phần MẠNG (UDP/TCP).
 *
 * Người làm network chỉ cần implements interface này rồi gọi
 * {@code mainFrame.setListener(...)}; giao diện sẽ gọi các hàm bên dưới khi người dùng thao tác.
 * Tất cả method đều có sẵn thân rỗng (default) nên chỉ cần override cái nào cần dùng.
 */
public interface MainFrameListener {

    /** Người dùng chọn / kéo thả file hoặc thư mục vào giao diện. */
    default void onFilesSelected(List<File> files) { }

    /**
     * Người dùng bấm "Chia sẻ file" khi đã chọn file và đã chọn 1 máy trong bảng.
     * Thư mục thì bên TCP/Utils tự nén (ZipUtils) trước khi gửi.
     */
    default void onSendRequested(List<File> files, String peerName, String peerIp, int peerPort) { }

    /** Bấm nút tạm dừng / tiếp tục ở ô truyền tải số {@code slot} (1 hoặc 2). */
    default void onPauseResume(int slot) { }

    /** Bấm nút dừng ở ô truyền tải số {@code slot} (1 hoặc 2). */
    default void onStop(int slot) { }

    /** Bấm nút làm mới danh sách peer. */
    default void onRefreshPeers() { }
}
