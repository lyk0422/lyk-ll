package com.example.starter.blast.api;

import com.example.starter.blast.domain.ApplicationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 作业申请视图。
 *
 * @param id               作业申请主键
 * @param mineCode         矿区编码
 * @param zoneCode         爆区编码
 * @param applicant        申请人
 * @param plannedTime      计划起爆时刻（UTC）
 * @param chargePerDelayKg 单段药量（kg）
 * @param status           当前生命周期状态
 */
public record ApplicationResponse(
        Long id,
        String mineCode,
        String zoneCode,
        String applicant,
        Instant plannedTime,
        BigDecimal chargePerDelayKg,
        ApplicationStatus status) {
}
