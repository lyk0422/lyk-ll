package com.example.starter.costmap.web.dto;

/**
 * 障碍物更新结果。
 *
 * @param replayed 是否为同参幂等重放（true 时未再次应用）
 * @param seq 应用后地图版本的逻辑序号（重放时为历史序号）
 * @param rxMin 本次增量重算区域最小列（含）
 * @param ryMin 本次增量重算区域最小行（含）
 * @param rxMax 本次增量重算区域最大列（含）
 * @param ryMax 本次增量重算区域最大行（含）
 * @param recomputedCells 实际重算的格点数量
 */
public record UpdateResponse(
        String requestId,
        String versionId,
        boolean replayed,
        long seq,
        int rxMin,
        int ryMin,
        int rxMax,
        int ryMax,
        int recomputedCells) {
}
