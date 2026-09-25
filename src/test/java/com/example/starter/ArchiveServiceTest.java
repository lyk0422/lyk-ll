package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.BalanceService;
import com.example.starter.service.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 归档终态：重复归档幂等成功，归档后禁止评估，迟到温度只记事件。
 */
class ArchiveServiceTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;

    private AdjustmentTask assessedTask() {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj", 1L, new BigDecimal("5"));
        clock.advance(Duration.ofHours(4));
        adjustmentService.assess(task.id(), "req-asm");
        return adjustmentService.getTask(task.id());
    }

    @Test
    void 归档为终态且重复归档幂等成功() {
        AdjustmentTask task = assessedTask();

        AdjustmentTask archived = adjustmentService.archive(task.id());
        assertThat(archived.status()).isEqualTo(AdjustmentTask.TaskStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();

        AdjustmentTask again = adjustmentService.archive(task.id());
        assertThat(again.status()).isEqualTo(AdjustmentTask.TaskStatus.ARCHIVED);
        assertThat(again.archivedAt()).isEqualTo(archived.archivedAt());
    }

    @Test
    void 归档后禁止再评估() {
        AdjustmentTask task = assessedTask();
        adjustmentService.archive(task.id());

        assertThatThrownBy(() -> adjustmentService.assess(task.id(), "req-asm-late"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 未评估任务不允许归档() {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj", 1L, new BigDecimal("5"));

        assertThatThrownBy(() -> adjustmentService.archive(task.id()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 归档后迟到温度只记迟到事件不入库() {
        AdjustmentTask task = assessedTask();
        adjustmentService.archive(task.id());
        Instant archivedAt = adjustmentService.getTask(task.id()).archivedAt();
        int readingsBefore = countOf("temperature_reading");

        // 采集时间早于归档时间：迟到数据
        BalanceService.ReadingResult late = balanceService.submitReadings(STATION_ID,
                List.of(new BalanceService.ReadingCommand(
                        1L, new BigDecimal("39.50"), archivedAt.minus(Duration.ofMinutes(5)))));
        assertThat(late.accepted()).isZero();
        assertThat(late.lateEvents()).isEqualTo(1);
        assertThat(countOf("temperature_reading")).isEqualTo(readingsBefore);
        assertThat(countOf("late_temperature_event")).isEqualTo(1);

        // 采集时间晚于归档时间：新一轮正常数据
        BalanceService.ReadingResult fresh = balanceService.submitReadings(STATION_ID,
                List.of(new BalanceService.ReadingCommand(
                        1L, new BigDecimal("41.00"), archivedAt.plus(Duration.ofMinutes(5)))));
        assertThat(fresh.accepted()).isEqualTo(1);
        assertThat(fresh.lateEvents()).isZero();
    }
}
