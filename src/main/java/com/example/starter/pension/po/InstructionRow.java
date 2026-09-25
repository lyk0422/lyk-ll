package com.example.starter.pension.po;

import java.time.OffsetDateTime;

/**
 * payout_instruction 表行对象：每期支付指令的首次执行记录。
 *
 * @param id            主键
 * @param requestId     指令幂等键
 * @param planId        计划 ID
 * @param installmentId 期次 ID
 * @param seqNo         期次序号
 * @param amountCents   指令金额，单位：分
 * @param fingerprint   指令指纹
 * @param success       首次执行是否成功
 * @param createdAt     指令创建时间（UTC）
 */
public record InstructionRow(
        long id,
        String requestId,
        long planId,
        long installmentId,
        int seqNo,
        long amountCents,
        String fingerprint,
        boolean success,
        OffsetDateTime createdAt) {
}
