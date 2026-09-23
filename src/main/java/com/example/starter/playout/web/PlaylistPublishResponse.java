package com.example.starter.playout.web;

import java.time.Instant;
import java.util.List;

/**
 * 播单发布结果。
 *
 * @param channel     频道标识
 * @param version     版本号
 * @param versionId   系统生成的版本ID
 * @param publishedAt 发布时间(UTC)
 * @param items       已接受的节目项
 */
public record PlaylistPublishResponse(
        String channel,
        String version,
        long versionId,
        Instant publishedAt,
        List<ItemView> items) {

    /**
     * 节目项视图。
     */
    public record ItemView(String itemId, String materialDigest, Instant start, Instant end) {
    }
}
