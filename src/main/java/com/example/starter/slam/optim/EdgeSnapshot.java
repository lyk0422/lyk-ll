package com.example.starter.slam.optim;

import com.example.starter.slam.domain.EdgeType;

import java.util.List;

/**
 * 优化输入中单条有效边的不可变快照（仅 VALID 边入图）。
 *
 * @param id          边 ID
 * @param source      数据来源
 * @param version     数据版本
 * @param type        边类型
 * @param fromFrameId 起点关键帧 ID
 * @param toFrameId   终点关键帧 ID
 * @param measurement 相对位姿测量 [dx, dy, dtheta]
 * @param information 信息矩阵（3x3 按行展开 9 个值）
 */
public record EdgeSnapshot(
        String id,
        String source,
        long version,
        EdgeType type,
        String fromFrameId,
        String toFrameId,
        List<Double> measurement,
        List<Double> information
) {
}
