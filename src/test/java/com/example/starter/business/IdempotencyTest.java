package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.ExecutePlanRequest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.api.dto.PlanSubmitRequest;
import com.example.starter.api.dto.SealRequest;
import com.example.starter.api.dto.SwitchPlanRequest;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.service.CementService;
import com.example.starter.support.TestFixtures;

/**
 * requestId 幂等：同键同参返回首次结果，同键异参 409 且不改变执行参数。
 */
class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private CementService service;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private PlanSubmitRequest baseRequest(String requestId, String rate) {
        return TestFixtures.submit(requestId, KILN, VALID_LICENSE, rate,
                DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel()));
    }

    @Test
    void same_key_same_params_returns_first_result() {
        PlanResultResponse first = service.submit(baseRequest("REQ-IDEM-1", "0.4000"));
        PlanResultResponse replay = service.submit(baseRequest("REQ-IDEM-1", "0.4000"));
        assertThat(replay.planId()).isEqualTo(first.planId());
        assertThat(replay.planNo()).isEqualTo(first.planNo());
        assertThat(replay.status()).isEqualTo("APPROVED");
        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM co_firing_plan WHERE request_id = 'REQ-IDEM-1'",
                Integer.class);
        assertThat(planCount).isEqualTo(1);
    }

    @Test
    void same_key_different_params_conflicts_without_changing_plan() {
        PlanResultResponse first = service.submit(baseRequest("REQ-IDEM-2", "0.4000"));
        PlanSubmitRequest mutated = baseRequest("REQ-IDEM-2", "0.3000");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(mutated));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.IDEMPOTENT_CONFLICT);

        // 首次执行参数未被改变
        PlanStatusResponse status = service.status(first.planNo());
        assertThat(status.substitutionRate()).isEqualByComparingTo("0.4000");
        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM co_firing_plan WHERE request_id = 'REQ-IDEM-2'",
                Integer.class);
        assertThat(planCount).isEqualTo(1);
    }

    @Test
    void switch_same_key_same_params_is_idempotent() {
        // 旧方案计划 00:00~12:00 执行中；新方案计划 12:00~23:59，时段不重叠，审核通过待命
        PlanResultResponse p1 = service.submit(TestFixtures.submit("REQ-SW-A", KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(12, 0),
                List.of(TestFixtures.normalFuel())));
        service.execute(new ExecutePlanRequest(p1.planNo()));
        PlanResultResponse p2 = service.submit(TestFixtures.submit("REQ-SW-B", KILN,
                VALID_LICENSE, "0.2000", DAY.atTime(12, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));
        assertThat(p2.accepted()).isTrue();

        SwitchPlanRequest sw = new SwitchPlanRequest("REQ-SWITCH-1", KILN, p2.planNo());
        PlanStatusResponse first = service.switchPlan(sw);
        assertThat(first.status()).isEqualTo("EXECUTING");
        assertThat(first.planNo()).isEqualTo(p2.planNo());
        PlanStatusResponse replay = service.switchPlan(sw);
        assertThat(replay.planId()).isEqualTo(first.planId());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM co_firing_plan WHERE plan_no = ?",
                String.class, p1.planNo())).isEqualTo("STOPPED");
        // 窑占用唯一指向新方案
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_plan WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
    }

    @Test
    void same_key_different_switch_target_conflicts() {
        PlanResultResponse p1 = service.submit(TestFixtures.submit("REQ-SWC-A", KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(12, 0),
                List.of(TestFixtures.normalFuel())));
        service.execute(new ExecutePlanRequest(p1.planNo()));
        PlanResultResponse p2 = service.submit(TestFixtures.submit("REQ-SWC-B", KILN,
                VALID_LICENSE, "0.2000", DAY.atTime(12, 0), DAY.atTime(18, 0),
                List.of(TestFixtures.normalFuel())));
        PlanResultResponse p3 = service.submit(TestFixtures.submit("REQ-SWC-C", KILN,
                VALID_LICENSE, "0.1500", DAY.atTime(18, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));

        service.switchPlan(new SwitchPlanRequest("REQ-SWC-1", KILN, p2.planNo()));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.switchPlan(
                new SwitchPlanRequest("REQ-SWC-1", KILN, p3.planNo())));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        // 当前执行方案仍是 p2，未被 p3 改变
        assertThat(service.status(p2.planNo()).status()).isEqualTo("EXECUTING");
        assertThat(service.status(p3.planNo()).status()).isEqualTo("APPROVED");
    }

    @Test
    void seal_is_idempotent_terminal_and_late_readings_only_record_events() {
        PlanResultResponse p = service.submit(baseRequest("REQ-SEAL-1", "0.4000"));
        service.execute(new ExecutePlanRequest(p.planNo()));
        service.ingestReadings(TestFixtures.readings(p.planNo(),
                TestFixtures.reading("P1", DAY.atTime(8, 15), "400", "100", "10")));

        SealRequest seal = new SealRequest("REQ-SEAL-DO", p.planNo(), DAY);
        var first = service.seal(seal);
        assertThat(first.sealed()).isTrue();
        var replay = service.seal(seal);
        assertThat(replay.sealed()).isTrue();
        assertThat(replay.actualSubstitutionRate())
                .isEqualByComparingTo(first.actualSubstitutionRate());

        assertThat(service.status(p.planNo()).status()).isEqualTo("SEALED");

        // 封存后迟到读数：只记迟到事件，不落排放读数、不改日结
        var ack = service.ingestReadings(TestFixtures.readings(p.planNo(),
                TestFixtures.reading("P1", DAY.atTime(9, 15), "999", "999", "999")));
        assertThat(ack.late()).isEqualTo(1);
        assertThat(ack.inserted()).isZero();
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_reading_event WHERE plan_id = ?",
                Integer.class, p.planId());
        assertThat(lateEvents).isEqualTo(1);
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM emission_reading WHERE plan_id = ?",
                Integer.class, p.planId());
        assertThat(readingCount).isEqualTo(1);
        var summaryAfterLate = service.seal(seal);
        assertThat(summaryAfterLate.exceededPeriods()).isEmpty();
    }
}
