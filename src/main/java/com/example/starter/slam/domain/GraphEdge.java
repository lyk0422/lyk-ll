package com.example.starter.slam.domain;

import com.example.starter.slam.model.EdgeStatus;
import com.example.starter.slam.model.EdgeType;

import java.time.Instant;

/**
 * 位姿图边记录（里程计边或回环约束）。
 *
 * @param status           VALID 表示有效边；REJECTED 表示未通过可信度校验的回环审计记录
 * @param graphVersion     有效边写入后的图版本；被拒绝回环固定为 0（不触发图版本递增）
 * @param score            回环匹配分数；里程计边为 null
 * @param residual         回环初始残差；里程计边为 null
 * @param rejectReason     拒绝原因（审计用）；有效边为 null
 * @param createdAt        入库时间（UTC）
 */
public record GraphEdge(
        String id,
        EdgeType type,
        String source,
        int version,
        String fromKeyframeId,
        String toKeyframeId,
        double dx,
        double dy,
        double dtheta,
        String covarianceJson,
        Double score,
        Double residual,
        String requestId,
        String contentHash,
        EdgeStatus status,
        String rejectReason,
        int graphVersion,
        Instant createdAt
) {
}
