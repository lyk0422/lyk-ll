package com.example.starter.slam.web.dto;

import com.example.starter.slam.domain.GraphEdge;

import java.time.Instant;

/**
 * 边写入结果；deduped=true 表示同 ID 同内容重复写入，返回原记录。
 * REJECTED 状态表示回环约束未通过阈值校验，仅保存审计记录。
 */
public record EdgeResponse(
        String id,
        String type,
        String status,
        Integer graphVersion,
        boolean deduped,
        String rejectReason,
        Instant createdAt
) {
    public static EdgeResponse of(GraphEdge e, boolean deduped) {
        Integer gv = e.status() == com.example.starter.slam.model.EdgeStatus.VALID ? e.graphVersion() : null;
        return new EdgeResponse(e.id(), e.type().name(), e.status().name(), gv, deduped, e.rejectReason(), e.createdAt());
    }
}
