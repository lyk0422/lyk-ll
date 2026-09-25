package com.example.starter.waterlogging.po;

import java.time.LocalDateTime;

/**
 * 失联派单台账 loss_contact_order 映射。
 *
 * @param lastReadingTime 失联前最后一条数据时刻；从无数据时为空
 * @param openFlag        开放单时等于站点编码，闭环后为空
 */
public record LossContactOrder(
        Long id,
        String stationCode,
        LocalDateTime lastReadingTime,
        LocalDateTime declaredAt,
        String status,
        String openFlag,
        String requestId,
        LocalDateTime createdAt) {
}
