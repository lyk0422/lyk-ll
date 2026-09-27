package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaEvent;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受控调节：完整铅封拒绝、解封窗口/额度/版本约束、失败不消费、超时不回拨。
 */
class ControlledAdjustmentTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;
    @Autowired
    private SealService sealService;

    private void imbalancedReadings() {
        // U1 欠流（低于均值），调节方向为开大阀门
        submitReadings(40.00, 42.50, 41.00, 41.00);
    }

    private UnsealOrder sealedAndApproved(Instant start, Instant end, int quota) {
        sealService.registerSeal(1L, "req-seal-1", "SEAL-0001", 0, "张三");
        return sealService.approveUnseal(1L, "req-unseal-1", "SEAL-0001", 0,
                "李四", "王五", start, end, quota);
    }

    /** 评估并归档当前任务，释放站内执行中占位。 */
    private void assessAndArchive(AdjustmentTask task, String requestId) {
        clock.advance(Duration.ofHours(4));
        adjustmentService.assess(task.id(), requestId);
        adjustmentService.archive(task.id());
    }

    @Test
    void 完整铅封下调节令被拒绝() {
        sealService.registerSeal(1L, "req-seal-1", "SEAL-0001", 0, "张三");
        imbalancedReadings();

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-sealed", 1L, new BigDecimal("5"), 0))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();
    }

    @Test
    void 受控调节在窗口与额度内成功并消费额度() {
        UnsealOrder order = sealedAndApproved(
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(10)), 2);

        // 第一次：开度 50 → 55，版本 1，额度 1/2
        imbalancedReadings();
        AdjustmentTask first = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-1", 1L, new BigDecimal("5"), 0);
        assertThat(first.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        UnsealOrder afterFirst = sealService.getUnsealOrder(order.id());
        assertThat(afterFirst.quotaUsed()).isEqualTo(1);
        assertThat(afterFirst.expectedValveVersion()).isEqualTo(1);
        assertThat(afterFirst.version()).isEqualTo(2);
        assessAndArchive(first, "req-asm-1");

        // 第二次：开度 55 → 60，版本 2，额度 2/2
        imbalancedReadings();
        AdjustmentTask second = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-2", 1L, new BigDecimal("5"), 1);
        assertThat(second.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(openingOf(1L)).isEqualByComparingTo("60.00");
        assessAndArchive(second, "req-asm-2");

        // 第三次：额度用尽 → 422，不消费、不改开度
        imbalancedReadings();
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-3", 1L, new BigDecimal("5"), 2))
                .isInstanceOf(UnprocessableException.class);
        UnsealOrder afterThird = sealService.getUnsealOrder(order.id());
        assertThat(afterThird.quotaUsed()).isEqualTo(2);
        assertThat(openingOf(1L)).isEqualByComparingTo("60.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        // 额度轨迹：授予 + 两次消费
        assertThat(sealService.getQuotaEvents(order.id()))
                .extracting(UnsealQuotaEvent::eventType)
                .containsExactly(UnsealQuotaEvent.EventType.GRANT,
                        UnsealQuotaEvent.EventType.CONSUME,
                        UnsealQuotaEvent.EventType.CONSUME);
    }

    @Test
    void 解封窗口为半开区间恰到终点失效() {
        Instant start = T0.plus(Duration.ofHours(1));
        Instant end = T0.plus(Duration.ofHours(3));
        sealedAndApproved(start, end, 2);

        // 窗口未开始 → 422
        imbalancedReadings();
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-before", 1L, new BigDecimal("5"), 0))
                .isInstanceOf(UnprocessableException.class);

        // 恰到起点生效
        clock.set(start);
        imbalancedReadings();
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-start", 1L, new BigDecimal("5"), 0);
        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assessAndArchive(task, "req-asm-start");

        // 恰到终点失效 → 422
        clock.set(end);
        imbalancedReadings();
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-end", 1L, new BigDecimal("5"), 1))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 解封超时仅禁止新调节不回拨已改开度() {
        UnsealOrder order = sealedAndApproved(
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);
        imbalancedReadings();
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-1", 1L, new BigDecimal("5"), 0);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        // 稳定满 4 小时后评估归档，时钟越过窗口终点（T0+2h）
        assessAndArchive(task, "req-asm-1");

        // 窗口过期后：已改开度保持，新的调节被拒绝
        imbalancedReadings();
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-2", 1L, new BigDecimal("5"), 1))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(sealService.getUnsealOrder(order.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 受控调节须携带正确开度版本() {
        sealedAndApproved(T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);
        imbalancedReadings();

        // 缺失 expectedVersion → 422
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-null", 1L, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        // 版本不符 → 409
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-wrong", 1L, new BigDecimal("5"), 7))
                .isInstanceOf(ConflictException.class);
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
    }

    @Test
    void 受控调节仍遵守10个百分点步进上限() {
        sealedAndApproved(T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);
        imbalancedReadings();

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-step", 1L, new BigDecimal("10.01"), 0))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getUnsealOrder(1L).quotaUsed()).isZero();
    }

    @Test
    void 受控调节失败不消费额度且不占键() {
        UnsealOrder order = sealedAndApproved(
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);
        // 外部将开度推到 98，欠流开大 5% 将越界 → 422
        jdbc.update("UPDATE valve SET opening_percent = 98.00 WHERE unit_id = 1");
        imbalancedReadings();

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-oob", 1L, new BigDecimal("5"), 0))
                .isInstanceOf(UnprocessableException.class);
        // 失败不消费：额度、轨迹、开度、任务均无变化
        assertThat(sealService.getUnsealOrder(order.id()).quotaUsed()).isZero();
        assertThat(sealService.getQuotaEvents(order.id())).hasSize(1);
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();

        // 失败不占键：修复越界后同键同参可成功
        jdbc.update("UPDATE valve SET opening_percent = 50.00 WHERE unit_id = 1");
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-oob", 1L, new BigDecimal("5"), 0);
        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(sealService.getUnsealOrder(order.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 同键重放不重复消费额度() {
        UnsealOrder order = sealedAndApproved(
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);
        imbalancedReadings();
        AdjustmentTask first = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-idem", 1L, new BigDecimal("5"), 0);

        AdjustmentTask replay = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-idem", 1L, new BigDecimal("5"), 0);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(sealService.getUnsealOrder(order.id()).quotaUsed()).isEqualTo(1);
        assertThat(sealService.getQuotaEvents(order.id()))
                .extracting(UnsealQuotaEvent::eventType)
                .containsExactly(UnsealQuotaEvent.EventType.GRANT,
                        UnsealQuotaEvent.EventType.CONSUME);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 受控阀门仍遵守24小时最多3次且转人工不消费额度() {
        sealedAndApproved(T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(30)), 2);
        // 第 1、2 次：消耗了解封单 1 的全部额度
        imbalancedReadings();
        AdjustmentTask t1 = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-1", 1L, new BigDecimal("5"), 0);
        assessAndArchive(t1, "req-asm-1");
        imbalancedReadings();
        AdjustmentTask t2 = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-2", 1L, new BigDecimal("5"), 1);
        assessAndArchive(t2, "req-asm-2");

        // 撤销旧单并批准新单（额度 2），第 3 次调节成功
        sealService.revokeUnseal(1L, "req-revoke-1", 3);
        UnsealOrder order2 = sealService.approveUnseal(1L, "req-unseal-2", "SEAL-0001", 2,
                "李四", "王五", T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(30)), 2);
        imbalancedReadings();
        AdjustmentTask t3 = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-3", 1L, new BigDecimal("5"), 2);
        assessAndArchive(t3, "req-asm-3");

        // 第 4 次：24 小时内已 3 次 → 转人工复核，不消费新单额度
        imbalancedReadings();
        AdjustmentTask t4 = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-4", 1L, new BigDecimal("5"), 3);
        assertThat(t4.status()).isEqualTo(AdjustmentTask.TaskStatus.MANUAL_REVIEW);
        assertThat(t4.executedAt()).isNull();
        assertThat(sealService.getUnsealOrder(order2.id()).quotaUsed()).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo("65.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(3);
    }

    @Test
    void 撤销后调节被拒绝且已改开度不回拨() {
        UnsealOrder order = sealedAndApproved(
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(10)), 2);
        imbalancedReadings();
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-1", 1L, new BigDecimal("5"), 0);
        assessAndArchive(task, "req-asm-1");

        sealService.revokeUnseal(order.id(), "req-revoke-1", 2);

        imbalancedReadings();
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-2", 1L, new BigDecimal("5"), 1))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 无台账阀门不携带版本仍按原规则工作() {
        // 阀门 2 无铅封台账
        imbalancedReadings();
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-plain", 2L, new BigDecimal("5"));

        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(task.classification()).isEqualTo(AdjustmentTask.ImbalanceType.OVERFLOW);
        assertThat(openingOf(2L)).isEqualByComparingTo("45.00");
    }
}
