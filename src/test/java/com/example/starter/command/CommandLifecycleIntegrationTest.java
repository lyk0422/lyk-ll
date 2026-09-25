package com.example.starter.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.HourEvaluationResponse;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.core.ApiException;
import com.example.starter.core.TestClockHelper;
import com.example.starter.support.TestFixtures;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 降掺指令链：时限内确认、恰满 30 分钟未确认自动升级并强制保底、扫描升级。
 */
class CommandLifecycleIntegrationTest extends AbstractIntegrationTest {

    private Long seedExecutingPlan(String requestId) {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        PlanResponse declared = planService.declare(TestFixtures.declareRequest(requestId, "0.4000"));
        Long id = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null)).id();
        planService.execute(id);
        // 评估 09 时，指令下发时刻固定为 10:00，确认时限恰为 10:30。
        clock.setTime(TestClockHelper.hour(10));
        return id;
    }

    private CommandResponse triggerCommand(Long planId, int hour, String value) {
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(hour).plusMinutes(20), value));
        HourEvaluationResponse evaluation = emissionService.evaluate(planId, TestClockHelper.hour(hour));
        assertThat(evaluation.command()).isNotNull();
        return evaluation.command();
    }

    @Test
    void confirmBeforeDeadlineSucceeds() {
        Long planId = seedExecutingPlan("cmd-ok");
        CommandResponse command = triggerCommand(planId, 9, "450");
        // 下发后 29 分 59 秒确认：未到恰满 30 分钟，允许确认。
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 29, 59));
        CommandResponse confirmed = commandService.confirm(planId, command.id(), "confirm-early");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedAt()).isEqualTo(LocalDateTime.of(2026, 9, 25, 10, 29, 59));
        assertThat(confirmed.escalatedAt()).isNull();
        // 时限内确认只执行步长降掺，不到保底。
        assertThat(planService.get(planId).currentHeatRatio()).isEqualByComparingTo("0.3000");
    }

    @Test
    void confirmExactlyAtDeadlineEscalatesAndForcesFloorRatio() {
        Long planId = seedExecutingPlan("cmd-timeout");
        CommandResponse command = triggerCommand(planId, 9, "450");
        // 恰满 30 分钟（10:30:00）仍未确认：自动升级，确认被拒。
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 30, 0));
        assertThatThrownBy(() -> commandService.confirm(planId, command.id(), "confirm-late"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超时升级");
        // 升级已独立提交，不受确认失败回滚影响。
        CommandResponse escalated = jdbc.queryForObject(
                "SELECT * FROM reduction_command WHERE id = ?",
                CommandRowMapper.INSTANCE, command.id());
        assertThat(escalated.status()).isEqualTo("ESCALATED");
        assertThat(escalated.escalatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 25, 10, 30, 0));
        // 强制降掺至保底比例 0.1，而非步长目标 0.3。
        assertThat(planService.get(planId).currentHeatRatio()).isEqualByComparingTo("0.1000");
    }

    @Test
    void sweepTimeoutsEscalatesOnlyDueCommands() {
        Long planId = seedExecutingPlan("cmd-sweep");
        CommandResponse command = triggerCommand(planId, 9, "450");
        // 10:29:59 扫描：未到时，不升级。
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 29, 59));
        assertThat(commandService.sweepTimeouts()).isZero();
        String status = jdbc.queryForObject(
                "SELECT status FROM reduction_command WHERE id = ?", String.class, command.id());
        assertThat(status).isEqualTo("ISSUED");
        // 10:30:00 扫描：恰满时限，升级 1 条。
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 30, 0));
        assertThat(commandService.sweepTimeouts()).isEqualTo(1);
        status = jdbc.queryForObject(
                "SELECT status FROM reduction_command WHERE id = ?", String.class, command.id());
        assertThat(status).isEqualTo("ESCALATED");
        // 重复扫描幂等：已升级不再计数。
        assertThat(commandService.sweepTimeouts()).isZero();
    }

    @Test
    void escalatedCommandCannotBeConfirmedAfterwards() {
        Long planId = seedExecutingPlan("cmd-escalated");
        CommandResponse command = triggerCommand(planId, 9, "450");
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 30, 0));
        commandService.sweepTimeouts();
        // 再推进时间，确认已升级指令仍被拒绝。
        clock.setTime(LocalDateTime.of(2026, 9, 25, 11, 0, 0));
        assertThatThrownBy(() -> commandService.confirm(planId, command.id(), "confirm-after"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超时升级");
    }
}
