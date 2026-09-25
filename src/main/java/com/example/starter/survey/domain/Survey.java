package com.example.starter.survey.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一次检验记录（计划→申请→登轮→发证流转主体）。
 *
 * @param id                 主键
 * @param requestId          申请幂等键
 * @param shipId             船舶主键
 * @param type               检验类型
 * @param status             流转状态
 * @param cycleAnchor        本次周期锚点日（年度为当年周年日；UTC）
 * @param windowStart        申请时快照的窗口首日（含，UTC）
 * @param windowEnd          申请时快照的窗口末日（含，UTC；批准展期后另记 extensionUntil）
 * @param extensionUntil     展期后窗口/有效期末日（含，UTC），未展期为 null
 * @param appliedAt          受理时刻（UTC）
 * @param boardedAt          登轮检验完成时刻（UTC），未完成为 null
 * @param certifiedAt        发证时刻（UTC），未发证为 null
 * @param fingerprint        业务指纹（requestId + 全部业务字段）
 */
public record Survey(
        Long id,
        String requestId,
        Long shipId,
        SurveyType type,
        SurveyStatus status,
        LocalDate cycleAnchor,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate extensionUntil,
        Instant appliedAt,
        Instant boardedAt,
        Instant certifiedAt,
        String fingerprint
) {

    /** 是否仍处于进行中（未到发证终态）。 */
    public boolean inProgress() {
        return status != SurveyStatus.CERTIFIED;
    }
}
