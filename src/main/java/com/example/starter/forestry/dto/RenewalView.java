package com.example.starter.forestry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 迹地更新义务视图。
 *
 * @param id                  更新义务 ID
 * @param applicationId       关联申请单 ID
 * @param operatorId          经营主体 ID
 * @param status              更新状态：PENDING / REPLANTING / PASSED / FAILED
 * @param deadline            首次更新验收截止日（翌年 12 月 31 日，UTC）
 * @param replantDeadline     补植截止日（首次验收不合格日 + 6 个自然月月末，UTC），未进入补植为空
 * @param qualifiedRate       最近一次验收合格率
 * @param acceptedAt          首次验收日期（UTC），未验收为空
 * @param replantAcceptedAt   补植验收日期（UTC），未补植验收为空
 */
public record RenewalView(
        String id,
        String applicationId,
        String operatorId,
        String status,
        LocalDate deadline,
        LocalDate replantDeadline,
        BigDecimal qualifiedRate,
        LocalDate acceptedAt,
        LocalDate replantAcceptedAt
) {
}
