package com.example.starter.domain;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 油机主数据。
 *
 * @param id             主键
 * @param genCode        油机编号（业务唯一键）
 * @param genName        油机名称
 * @param status         IDLE 空闲 / DISPATCHED 在途 / GENERATING 发电中
 * @param currentOrderId 当前在途或发电中的调度单 ID；空闲为 null
 * @param createdAt      创建时刻，UTC
 */
public record Generator(
        Long id,
        String genCode,
        String genName,
        GeneratorStatus status,
        Long currentOrderId,
        Instant createdAt
) {

    public static RowMapper<Generator> rowMapper() {
        return (ResultSet rs, int rowNum) -> {
            long orderId = rs.getLong("current_order_id");
            Long currentOrderId = rs.wasNull() ? null : orderId;
            return new Generator(
                    rs.getLong("id"),
                    rs.getString("gen_code"),
                    rs.getString("gen_name"),
                    GeneratorStatus.valueOf(rs.getString("status")),
                    currentOrderId,
                    rs.getTimestamp("created_at").toInstant()
            );
        };
    }
}
