package com.example.starter.slam.web.dto;

import com.example.starter.slam.domain.Keyframe;

import java.time.Instant;

/**
 * 关键帧写入结果；deduped=true 表示同 ID 同内容重复写入，返回原记录。
 */
public record KeyframeResponse(
        String id,
        String source,
        int version,
        int graphVersion,
        boolean deduped,
        Instant createdAt
) {
    public static KeyframeResponse of(Keyframe k, boolean deduped) {
        return new KeyframeResponse(k.id(), k.source(), k.version(), k.graphVersion(), deduped, k.createdAt());
    }
}
