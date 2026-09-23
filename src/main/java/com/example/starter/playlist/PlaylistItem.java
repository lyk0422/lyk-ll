package com.example.starter.playlist;

/**
 * 播单节目项：版本内唯一 itemId，半开时段 [startMs, endMs)。
 */
public record PlaylistItem(
        String itemId,
        String mediaDigest,
        long startMs,
        long endMs
) {
}
