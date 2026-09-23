package com.example.starter.costmap.web.dto;

import java.util.List;

/**
 * 区域差异查询结果：某快照固化视图与当前查询视图在给定区域内的单元格级差异。
 *
 * @param mapId       地图 ID
 * @param snapshotId  参与比较的快照 ID
 * @param region      查询区域（含边界）
 * @param snapshotSeq 快照序号
 * @param liveSeq     当前视图版本序号
 * @param entries     所有代价不同的单元格
 */
public record RegionDiffResponse(
        String mapId,
        String snapshotId,
        RegionDto region,
        long snapshotSeq,
        long liveSeq,
        List<DiffEntry> entries) {
}
