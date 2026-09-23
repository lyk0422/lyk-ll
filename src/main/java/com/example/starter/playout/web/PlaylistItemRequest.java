package com.example.starter.playout.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 播单发布请求节目项：itemId 唯一，半开时段 [start,end)。
 *
 * @param itemId         节目项唯一标识
 * @param materialDigest 素材摘要
 * @param start          开始时间(UTC)
 * @param end            结束时间(UTC)
 */
public record PlaylistItemRequest(
        @NotBlank String itemId,
        @NotBlank String materialDigest,
        @NotNull Instant start,
        @NotNull Instant end) {
}
