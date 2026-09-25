package com.example.starter.pension.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 单期支付指令执行结果。
 *
 * @param requestId   指令幂等键
 * @param planNo      计划号
 * @param seqNo       期次序号
 * @param amountCents 出款金额，单位：分
 * @param success     本次返回的出款结果；重放返回首次结果
 * @param replayed    true 表示为同键重放，未产生新指令
 * @param paidAt      出款时间（UTC）；失败时为 null
 * @param dueDate     该期到期日
 */
public record PaymentResultResponse(
        String requestId,
        String planNo,
        int seqNo,
        long amountCents,
        boolean success,
        boolean replayed,
        OffsetDateTime paidAt,
        LocalDate dueDate) {
}
