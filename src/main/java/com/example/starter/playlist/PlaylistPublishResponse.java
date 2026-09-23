package com.example.starter.playlist;

import java.util.List;

/**
 * 播单发布结果：新版本号、节目项数量、当前频道绑定版本。
 */
public record PlaylistPublishResponse(
        String channelId,
        int versionNo,
        int itemCount,
        Long publishedAtMs,
        List<PlaylistItem> items
) {
}
