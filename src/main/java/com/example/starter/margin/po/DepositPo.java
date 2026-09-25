package com.example.starter.margin.po;

import com.example.starter.margin.domain.DepositResult;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 追加保证金入账记录持久化对象。requestId 为主键（幂等键），指纹覆盖 requestId+accountNo+amount+currency。
 *
 * @param requestId      幂等键
 * @param accountNo      账户号
 * @param amount         入账金额，单位元
 * @param currency       币种
 * @param result         ACCEPTED/REPLAYED/APPLIED_TO_REMAINING/REJECTED_CLOSED
 * @param replayed       是否重放返回
 * @param ratioAfter     入账重算后的维持担保比例；账户已无负债时为 null
 * @param noticeResolved 是否解除未结清追保通知
 * @param jobCancelled   是否撤销待发强平
 * @param depositedAt    入账时间（UTC）
 */
public record DepositPo(
        String requestId,
        String accountNo,
        BigDecimal amount,
        String currency,
        DepositResult result,
        boolean replayed,
        BigDecimal ratioAfter,
        boolean noticeResolved,
        boolean jobCancelled,
        Instant depositedAt
) {
}
