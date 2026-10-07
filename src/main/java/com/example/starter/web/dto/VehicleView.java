package com.example.starter.web.dto;

import java.util.List;

/**
 * 车辆清单响应：车单创建、撤销、逐车核销均返回该冻结视图。
 *
 * @param batchNo       屠宰批次号
 * @param vehicleNo     合成车号
 * @param version       车单乐观版本：新建为 1，撤销/核销成功后加 1，旧版操作返回 409
 * @param status        车单状态：RESERVED 已预占 / DEPARTED 已发车出厂 / REVOKED 已撤销
 * @param frozenHeads   冻结头只标识集合（创建时快照，字典序），发车后不可改
 * @param headCount     冻结头只数
 * @param createdAt     车单创建时间（UTC ISO-8601）
 * @param departedAt    发车出厂时间（UTC ISO-8601），NULL 表示未发车
 * @param revokedAt     撤销时间（UTC ISO-8601），NULL 表示未撤销
 * @param batchStatus   操作后批次状态
 * @param batchReleased 逐车核销后批次是否已达最终出厂状态（全部合格头只恰核销完）
 */
public record VehicleView(
        String batchNo,
        String vehicleNo,
        int version,
        String status,
        List<String> frozenHeads,
        int headCount,
        String createdAt,
        String departedAt,
        String revokedAt,
        String batchStatus,
        boolean batchReleased) {
}
