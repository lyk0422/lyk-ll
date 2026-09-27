package com.example.starter.dao;

import java.time.Instant;

/**
 * 受控方案的每人积分来源冻结快照行。
 *
 * @param id                    自增主键
 * @param diveRequestId         所属受控潜次
 * @param planVersion           所属受控方案版本
 * @param diverCode             被冻结来源的潜水员工号
 * @param sourceDiveRequestId   积分来源潜次（首潜为 null）
 * @param sourcePlanVersion     积分来源潜次的方案版本（首潜为 null）
 * @param sourceSurfacedAtUtc   冻结的最近确认出水时刻（首潜为 null）
 * @param sourceResidualPoints  冻结的来源出水残留积分（消减前；首潜为 null）
 * @param frozenPoints          方案时刻推导的下潜前积分（已消减，最低 0）
 * @param ndlMinutes            方案深度对应的合成免减压表分钟数
 * @param allowedMinutes        该潜水员允许分钟数 = NDL - 当前积分
 */
public record ResidualSnapshotRow(
        Long id,
        String diveRequestId,
        int planVersion,
        String diverCode,
        String sourceDiveRequestId,
        Integer sourcePlanVersion,
        Instant sourceSurfacedAtUtc,
        Integer sourceResidualPoints,
        Integer sourceAccountVersion,
        int frozenPoints,
        int ndlMinutes,
        int allowedMinutes,
        Instant createdAtUtc) {
}
