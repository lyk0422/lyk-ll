package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调节令：步进边界、欠流/过流方向、幂等重放与 24 小时次数上限。
 */
class AdjustmentServiceTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;

    private void imbalancedReadings() {
        // U1 欠流（低于均值），U2 过流（高于均值）
        submitReadings(40.00, 42.50, 41.00, 41.00);
    }

    @Test
    void 单次恰10个百分点步进合法且欠流开大阀门() {
        imbalancedReadings();

        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-step-10", 1L, new BigDecimal("10"));

        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(task.classification()).isEqualTo(AdjustmentTask.ImbalanceType.UNDERFLOW);
        assertThat(task.oldOpening()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(task.newOpening()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("60.00"));
        // 开度写入与版本记录同事务：版本链第 1 版与当前开度一致
        var versions = adjustmentService.getValveVersions(1L);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).newOpening()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void 单次步进超过10个百分点被拒绝() {
        imbalancedReadings();

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-step-11", 1L, new BigDecimal("10.01")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(countOf("adjustment_task")).isZero();
    }

    @Test
    void 过流单元关小阀门且调节后开度越界被拒绝() {
        imbalancedReadings();
        jdbc.update("UPDATE valve SET opening_percent = 5.00 WHERE unit_id = 2");

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-overflow", 2L, new BigDecimal("10")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void 同键同参重放返回首次结果且不重复改开度() {
        imbalancedReadings();
        AdjustmentTask first = adjustmentService.issueAdjustment(
                STATION_ID, "req-idem", 1L, new BigDecimal("5"));

        AdjustmentTask replay = adjustmentService.issueAdjustment(
                STATION_ID, "req-idem", 1L, new BigDecimal("5"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThat(countOf("adjustment_task")).isEqualTo(1);
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 同键异参返回409且不改变阀门开度() {
        imbalancedReadings();
        adjustmentService.issueAdjustment(STATION_ID, "req-conflict", 1L, new BigDecimal("5"));

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-conflict", 1L, new BigDecimal("6")))
                .isInstanceOf(ConflictException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 同一单元24小时内恰3次合法第4次转人工复核() {
        for (int i = 1; i <= 3; i++) {
            imbalancedReadings();
            AdjustmentTask task = adjustmentService.issueAdjustment(
                    STATION_ID, "req-24h-" + i, 1L, new BigDecimal("5"));
            assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
            // 稳定满 4 小时后评估并归档，释放站内执行中占位
            clock.advance(Duration.ofHours(4));
            adjustmentService.assess(task.id(), "req-asm-" + i);
            adjustmentService.archive(task.id());
        }
        assertThat(countOf("adjustment_task")).isEqualTo(3);

        imbalancedReadings();
        AdjustmentTask fourth = adjustmentService.issueAdjustment(
                STATION_ID, "req-24h-4", 1L, new BigDecimal("5"));

        assertThat(fourth.status()).isEqualTo(AdjustmentTask.TaskStatus.MANUAL_REVIEW);
        assertThat(fourth.executedAt()).isNull();
        // 转人工复核不改变阀门开度
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("65.00"));
        assertThat(countOf("valve_opening_version")).isEqualTo(3);
    }

    @Test
    void 站内存在执行中任务时新调节令冲突() {
        imbalancedReadings();
        adjustmentService.issueAdjustment(STATION_ID, "req-active-1", 1L, new BigDecimal("5"));

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-active-2", 2L, new BigDecimal("5")))
                .isInstanceOf(ConflictException.class);
        assertThat(countOf("adjustment_task")).isEqualTo(1);
    }
}
