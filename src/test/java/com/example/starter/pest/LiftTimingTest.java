package com.example.starter.pest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import com.example.starter.common.ApiException;
import com.example.starter.pest.api.Requests.DispenseRequest;
import com.example.starter.pest.api.Requests.LiftApplyRequest;
import com.example.starter.pest.api.Requests.LiftConfirmRequest;
import com.example.starter.pest.api.Requests.OutboundRequest;
import com.example.starter.pest.api.Requests.TraceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 监测期与解除：恰满 14 天无新增虫迹方可申请解除，解除须消杀与库房双方确认。
 */
class LiftTimingTest extends BaseIntegrationTest {

    @Test
    void 恰满14天方可申请解除_双方确认后解除并恢复出库() {
        long chainId = reportAndConfirm("A", "l1").chainId();
        treatmentService.dispense(chainId,
                new DispenseRequest("l1-d", 1L, new BigDecimal("10"), "墙角", "张三", "李四"));

        // 第 13 天：监测期未满，拒绝申请
        clock.advance(Duration.ofDays(13));
        assertThatThrownBy(() -> treatmentService.applyLift(chainId, new LiftApplyRequest("l1-a1", "王五")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 恰满第 14 天：允许申请
        clock.advance(Duration.ofDays(1));
        var apply = treatmentService.applyLift(chainId, new LiftApplyRequest("l1-a2", "王五"));
        assertThat(apply.status()).isEqualTo("LIFT_REQUESTED");

        // 单方确认不解除
        var first = treatmentService.confirmLift(chainId,
                new LiftConfirmRequest("l1-c1", "DISINFECTION", "消杀员"));
        assertThat(first.lifted()).isFalse();
        assertThat(treatmentService.getChain(chainId).status()).isEqualTo("LIFT_REQUESTED");

        // 双方确认后解除，库区恢复出库
        var second = treatmentService.confirmLift(chainId,
                new LiftConfirmRequest("l1-c2", "WAREHOUSE", "库管员"));
        assertThat(second.lifted()).isTrue();
        assertThat(treatmentService.getChain(chainId).status()).isEqualTo("LIFTED");

        var outbound = outboundService.register(new OutboundRequest("l1-o", "A", "档案盒-1"));
        assertThat(outbound.id()).isPositive();
    }

    @Test
    void 监测期新增虫迹重新起算14天() {
        long chainId = reportAndConfirm("B", "l2").chainId();
        treatmentService.dispense(chainId,
                new DispenseRequest("l2-d", 1L, new BigDecimal("10"), "墙角", "张三", "李四"));

        // 第 10 天发现新增虫迹，监测期自该时刻重新起算
        clock.advance(Duration.ofDays(10));
        var trace = treatmentService.registerTrace(chainId, new TraceRequest("l2-t", "张三"));
        assertThat(trace.monitoringStart()).isEqualTo(T0.plus(Duration.ofDays(10)));

        // 虫迹后第 13 天（累计第 23 天）：仍不可申请
        clock.advance(Duration.ofDays(13));
        assertThatThrownBy(() -> treatmentService.applyLift(chainId, new LiftApplyRequest("l2-a1", "王五")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 虫迹后恰满第 14 天（累计第 24 天）：可申请
        clock.advance(Duration.ofDays(1));
        var apply = treatmentService.applyLift(chainId, new LiftApplyRequest("l2-a2", "王五"));
        assertThat(apply.status()).isEqualTo("LIFT_REQUESTED");
    }

    @Test
    void 未申请解除时不可确认() {
        long chainId = reportAndConfirm("A", "l3").chainId();
        treatmentService.dispense(chainId,
                new DispenseRequest("l3-d", 1L, new BigDecimal("10"), "墙角", "张三", "李四"));

        assertThatThrownBy(() -> treatmentService.confirmLift(chainId,
                new LiftConfirmRequest("l3-lc", "DISINFECTION", "消杀员")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
