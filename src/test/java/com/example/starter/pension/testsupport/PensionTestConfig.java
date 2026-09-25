package com.example.starter.pension.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.pension.dto.PaymentRequest;
import com.example.starter.pension.service.PayoutService;
import com.example.starter.pension.support.UtcClock;

/**
 * 测试公共配置：固定 UTC 时钟（2026-09-25）、表清理工具与事务回滚探针。
 */
@TestConfiguration
public class PensionTestConfig {

    /** 测试固定“当前时刻”：2026-09-25T10:00:00Z。 */
    public static final Instant FIXED_NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Bean
    @Primary
    public UtcClock fixedUtcClock() {
        return new UtcClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    /**
     * 清空全部业务表；并发测试不能依赖测试事务回滚，需显式清理。
     */
    public static void cleanup(JdbcTemplate jdbc) {
        List<String> tables = List.of(
                "account_entry", "payment_attempt", "plan_installment", "plan_contribution",
                "payout_plan", "idempotency_record", "contribution", "pension_member");
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : tables) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    /**
     * 事务原子性探针：在同一事务内执行末期出款（含自动结清与划回）后故意抛出异常，
     * 调用方断言全部写入随事务回滚，验证“个人出款与未归属划回同事务”。
     */
    public static class TransactionProbe {

        private final PayoutService payoutService;

        public TransactionProbe(PayoutService payoutService) {
            this.payoutService = payoutService;
        }

        @Transactional
        public void payLastThenThrow(PaymentRequest request) {
            payoutService.pay(request);
            throw new IllegalStateException("探针故意失败，事务必须整体回滚");
        }
    }

    @Bean
    public TransactionProbe transactionProbe(PayoutService payoutService) {
        return new TransactionProbe(payoutService);
    }
}
