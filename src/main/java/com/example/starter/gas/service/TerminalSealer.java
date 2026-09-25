package com.example.starter.gas.service;

import com.example.starter.gas.ApiException;
import com.example.starter.gas.domain.VoucherRow;
import com.example.starter.gas.repo.VoucherRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 终态封账器：逾期争议触发的自动封账须在独立事务提交，
 * 不随外层“拒绝争议”的回滚而撤销。
 */
@Component
public class TerminalSealer {

    private final Clock clock;
    private final VoucherRepository voucherRepository;

    public TerminalSealer(Clock clock, VoucherRepository voucherRepository) {
        this.clock = clock;
        this.voucherRepository = voucherRepository;
    }

    /**
     * 在独立新事务中条件封账：仅 EFFECTIVE/DISPUTED 可置 SEALED。
     * 返回封账后的凭证状态（已封账则保持 SEALED）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String sealIfOpenInNewTransaction(String voucherNo) {
        voucherRepository.sealIfOpen(voucherNo, clock.instant());
        VoucherRow latest = voucherRepository.findByNo(voucherNo);
        if (!VoucherRow.STATUS_SEALED.equals(latest.status())) {
            throw ApiException.conflict("SEAL_REJECTED",
                    "凭证当前状态不能封账: " + latest.status());
        }
        return latest.status();
    }
}
