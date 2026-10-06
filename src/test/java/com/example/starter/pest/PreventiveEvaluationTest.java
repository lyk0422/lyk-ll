package com.example.starter.pest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 整体预防性消杀评估：相邻库区 90 日内连续 2 次虫情触发，恰第 2 次触发。
 */
class PreventiveEvaluationTest extends BaseIntegrationTest {

    @Test
    void 相邻库区90日内恰第2次虫情触发评估_后续不重复触发() {
        var first = reportAndConfirm("A", "e1");
        assertThat(first.evaluationId()).isNull();

        clock.advance(Duration.ofDays(30));
        var second = reportAndConfirm("B", "e2");
        assertThat(second.evaluationId()).isNotNull();
        assertThat(count("preventive_evaluation")).isEqualTo(1);

        // 恰第 2 次触发：双方记录已消耗，相邻库区第 3 次虫情不再触发
        clock.advance(Duration.ofDays(10));
        var third = reportAndConfirm("C", "e3");
        assertThat(third.evaluationId()).isNull();
        assertThat(count("preventive_evaluation")).isEqualTo(1);

        // A 的处置链解除后再次虫情：前次记录已消耗，仍不触发
        liftChain("A", "e4-lift");
        var fourth = reportAndConfirm("A", "e4");
        assertThat(fourth.evaluationId()).isNull();
        assertThat(count("preventive_evaluation")).isEqualTo(1);
    }

    /**
     * 走完投放、14 天监测与双方确认，解除指定库区的进行中处置链。
     */
    private void liftChain(String zoneCode, String keyPrefix) {
        long chainId = jdbc.sql("SELECT chain_id FROM active_chain WHERE zone_code = :zoneCode")
                .param("zoneCode", zoneCode)
                .query(Long.class)
                .single();
        treatmentService.dispense(chainId, new com.example.starter.pest.api.Requests.DispenseRequest(
                keyPrefix + "-d", 1L, new java.math.BigDecimal("5"), "墙角", "张三", "李四"));
        clock.advance(Duration.ofDays(14));
        treatmentService.applyLift(chainId,
                new com.example.starter.pest.api.Requests.LiftApplyRequest(keyPrefix + "-a", "王五"));
        treatmentService.confirmLift(chainId,
                new com.example.starter.pest.api.Requests.LiftConfirmRequest(
                        keyPrefix + "-cd", "DISINFECTION", "消杀员"));
        treatmentService.confirmLift(chainId,
                new com.example.starter.pest.api.Requests.LiftConfirmRequest(
                        keyPrefix + "-cw", "WAREHOUSE", "库管员"));
    }

    @Test
    void 相邻库区虫情间隔超过90日不触发评估() {
        reportAndConfirm("A", "w1");

        clock.advance(Duration.ofDays(91));
        var second = reportAndConfirm("B", "w2");

        assertThat(second.evaluationId()).isNull();
        assertThat(count("preventive_evaluation")).isZero();
    }

    @Test
    void 非相邻库区虫情不触发评估() {
        reportAndConfirm("A", "n1");

        clock.advance(Duration.ofDays(10));
        var second = reportAndConfirm("D", "n2");

        assertThat(second.evaluationId()).isNull();
        assertThat(count("preventive_evaluation")).isZero();
    }
}
