package com.example.starter.costmap.web.dto;

import java.util.List;

/**
 * 过期清理结果。
 *
 * @param removedVersions 被删除的过期障碍物版本数量
 * @param recomputeRegions 实际重算区域（被删声明区域含膨胀边界的并集，可能合并）
 * @param liveSeq 清理后动态视图版本序号（无实际变更时保持不变）
 */
public record PurgeResponse(
        int removedVersions,
        List<RegionDto> recomputeRegions,
        long liveSeq) {
}
