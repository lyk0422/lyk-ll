package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.waterlogging.api.ArchiveRequest;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PlanStatus;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.exception.BusinessRuleException;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.service.WaterLoggingService;

/**
 * requestId 幂等：同键同参返回首次结果；同键异参 409 且不改变泵站状态；
 * 未完成预降不允许归档。
 */
class IdempotencyTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Autowired
    private WaterLoggingService service;

    private WarningStartRequest warning(String requestId, BigDecimal target, BigDecimal gate) {
        return new WarningStartRequest(requestId, "ORANGE", "DONGWAN", "东湾河", target, T0, gate);
    }

    @Test
    void sameKeySameParamsReturnsFirstResultAndSameKeyDifferentParamsConflicts() {
        var first = service.startOnWarning(warning("WARN-IDEMP", new BigDecimal("3.00"),
                new BigDecimal("60.00")));
        assertThat(first.replay()).isFalse();
        Long firstPlanId = first.data().planId();

        // 同键同参重放：返回首次结果。
        var replay = service.startOnWarning(warning("WARN-IDEMP", new BigDecimal("3.00"),
                new BigDecimal("60.00")));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.data().planId()).isEqualTo(firstPlanId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pre_drain_plan WHERE request_id = 'WARN-IDEMP'", Integer.class))
                .isEqualTo(1);

        // 同键异参（目标位不同）：409。
        assertThatThrownBy(() -> service.startOnWarning(warning("WARN-IDEMP", new BigDecimal("3.50"),
                new BigDecimal("60.00"))))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        // 异参请求不改变任何状态：目标位仍为 3.00，泵站仍运行。
        BigDecimal storedTarget = jdbcTemplate.queryForObject(
                "SELECT target_level FROM pre_drain_plan WHERE request_id = 'WARN-IDEMP'",
                BigDecimal.class);
        assertThat(storedTarget).isEqualByComparingTo("3.00");
        Integer running = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pump_station WHERE basin_zone = 'DONGWAN' AND run_status = 'RUNNING'",
                Integer.class);
        assertThat(running).isEqualTo(2);
    }

    @Test
    void archivingDrainingPlanIsRejectedWithoutStateChange() {
        service.startOnWarning(warning("WARN-ARCH-GUARD", new BigDecimal("3.00"),
                new BigDecimal("60.00")));

        // 仍在预降中：拒绝归档。
        assertThatThrownBy(() -> service.archive(new ArchiveRequest(
                "ARCHIVE-GUARD", "WARN-ARCH-GUARD", T0.plusMinutes(30), "提前归档")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM pre_drain_plan WHERE request_id = 'WARN-ARCH-GUARD'", String.class))
                .isEqualTo(PlanStatus.DRAINING);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_archive", Integer.class))
                .isZero();
    }

    @Test
    void fingerprintCoversAllBusinessFields() {
        service.startOnWarning(warning("WARN-FP-1", new BigDecimal("3.00"), new BigDecimal("60.00")));
        // 仅闸门开度不同：指纹必须不同 → 409。
        assertThatThrownBy(() -> service.startOnWarning(
                new WarningStartRequest("WARN-FP-1", "ORANGE", "DONGWAN", "东湾河",
                        new BigDecimal("3.00"), T0, new BigDecimal("61.00"))))
                .isInstanceOf(ConflictException.class);
        // 仅预警时刻不同：指纹必须不同 → 409。
        assertThatThrownBy(() -> service.startOnWarning(
                new WarningStartRequest("WARN-FP-1", "ORANGE", "DONGWAN", "东湾河",
                        new BigDecimal("3.00"), T0.plusSeconds(1), new BigDecimal("60.00"))))
                .isInstanceOf(ConflictException.class);
        // 泵站保持联动运行，未受失败请求影响。
        Integer running = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pump_station WHERE basin_zone = 'DONGWAN' AND run_status = 'RUNNING'",
                Integer.class);
        assertThat(running).isEqualTo(2);
    }

    @Test
    void pumpStatusAfterIdempotentReplayUnchanged() {
        var first = service.startOnWarning(warning("WARN-REPLAY-STATE", new BigDecimal("3.00"),
                new BigDecimal("60.00")));
        assertThat(first.data().stations())
                .allSatisfy(station -> assertThat(station.runStatus()).isEqualTo(PumpAction.RUNNING));
        var replay = service.startOnWarning(warning("WARN-REPLAY-STATE", new BigDecimal("3.00"),
                new BigDecimal("60.00")));
        assertThat(replay.data().stations())
                .allSatisfy(station -> assertThat(station.runStatus()).isEqualTo(PumpAction.RUNNING));
        // 联动 START 台账仍只有两站各一条，重放不重复启机。
        Integer commands = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pump_command WHERE action = 'START'", Integer.class);
        assertThat(commands).isEqualTo(2);
    }
}
