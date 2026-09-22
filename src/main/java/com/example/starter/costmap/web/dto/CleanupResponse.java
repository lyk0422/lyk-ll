package com.example.starter.costmap.web.dto;

/**
 * TTL 过期清理结果。
 */
public record CleanupResponse(String versionId, long atMs, int removedCells, int recomputedCells) {
}
