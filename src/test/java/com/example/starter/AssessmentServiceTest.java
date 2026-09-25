package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.Assessment;
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
 * 效果评估：稳定满 4 小时（恰满允许）、前后对比可复算、评估幂等。
 */
class AssessmentServiceTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;

    private AdjustmentTask executeAdjustment() {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        return adjustmentService.issueAdjustment(STATION_ID, "req-adj", 1L, new BigDecimal("5"));
    }

    @Test
    void 稳定恰满4小时允许评估() {
        AdjustmentTask task = executeAdjustment();
        clock.advance(Duration.ofHours(4));
        submitReadings(41.00, 41.50, 41.20, 41.00);

        Assessment assessment = adjustmentService.assess(task.id(), "req-asm");

        assertThat(assessment.beforeDiff()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(assessment.afterDiff()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(assessment.balanced()).isTrue();
        assertThat(assessment.detail()).contains("before").contains("after");
        assertThat(adjustmentService.getTask(task.id()).status())
                .isEqualTo(AdjustmentTask.TaskStatus.ASSESSED);
    }

    @Test
    void 稳定期差1分钟未满4小时拒绝评估() {
        AdjustmentTask task = executeAdjustment();
        clock.advance(Duration.ofHours(3).plusMinutes(59));

        assertThatThrownBy(() -> adjustmentService.assess(task.id(), "req-asm"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(adjustmentService.getTask(task.id()).status())
                .isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
    }

    @Test
    void 评估同键重放返回首次结果异参409() {
        AdjustmentTask task = executeAdjustment();
        clock.advance(Duration.ofHours(4));
        Assessment first = adjustmentService.assess(task.id(), "req-asm-idem");

        Assessment replay = adjustmentService.assess(task.id(), "req-asm-idem");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(countOf("assessment")).isEqualTo(1);

        // 归档释放站内执行中占位后，同一评估键用于其他任务属于同键异参
        adjustmentService.archive(task.id());
        AdjustmentTask other = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-2", 1L, new BigDecimal("5"));
        assertThatThrownBy(() -> adjustmentService.assess(other.id(), "req-asm-idem"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 已评估任务重复评估返回409() {
        AdjustmentTask task = executeAdjustment();
        clock.advance(Duration.ofHours(4));
        adjustmentService.assess(task.id(), "req-asm");

        assertThatThrownBy(() -> adjustmentService.assess(task.id(), "req-asm-new"))
                .isInstanceOf(ConflictException.class);
    }
}
