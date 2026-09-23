package com.example.starter.slam.po;

import com.example.starter.slam.domain.EdgeStatus;
import com.example.starter.slam.domain.EdgeType;

import java.time.Instant;

/**
 * 边持久化对象（含 VALID 有效边与 REJECTED 审计记录）。
 *
 * @param id            全局唯一边 ID
 * @param requestId     客户端幂等键（唯一）
 * @param graphId       所属图 ID
 * @param source        数据来源
 * @param version       数据版本（同时用于选择版本化回环阈值）
 * @param type          边类型 ODOMETRY / LOOP
 * @param fromFrameId   起点关键帧 ID
 * @param toFrameId     终点关键帧 ID
 * @param measurementJson 相对位姿测量 JSON
 * @param informationJson 信息矩阵 JSON（3x3 按行展开）
 * @param score         回环匹配分数；里程计边为 null
 * @param residual      回环初始残差；里程计边为 null
 * @param status        VALID 进入有效图；REJECTED 仅审计
 * @param rejectReason  拒绝原因；VALID 为 null
 * @param contentHash   规范化内容摘要，用于同 ID/同 requestId 改参冲突检测
 * @param createdAt     创建时间（UTC）
 */
public record EdgePO(
        String id,
        String requestId,
        String graphId,
        String source,
        long version,
        EdgeType type,
        String fromFrameId,
        String toFrameId,
        String measurementJson,
        String informationJson,
        Double score,
        Double residual,
        EdgeStatus status,
        String rejectReason,
        String contentHash,
        Instant createdAt
) {
}
