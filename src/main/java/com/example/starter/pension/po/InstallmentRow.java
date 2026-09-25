package com.example.starter.pension.po;

import com.example.starter.pension.domain.InstallmentStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * payout_installment 表行对象。
 *
 * @param id          主键
 * @param planId      所属计划 ID
 * @param seqNo       期次序号，从 1 开始
 * @param dueDate     到期日（月初 UTC）
 * @param amountCents 该期金额，单位：分
 * @param status      期次状态
 * @param attempts    出款尝试次数
 * @param paidAt      出款时间（UTC）；未出款为 null
 */
public record InstallmentRow(
        long id,
        long planId,
        int seqNo,
        LocalDate dueDate,
        long amountCents,
        InstallmentStatus status,
        int attempts,
        OffsetDateTime paidAt) {
}
