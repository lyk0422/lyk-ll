package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandActionRequest;
import com.example.starter.api.dto.ExecutePlanRequest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.service.CementService;
import com.example.starter.support.TestFixtures;

/**
 * 降掺确认的 requestId 幂等：同键同参回放首次结果；同键异参 409；未下发不能确认。
 */
class CommandIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private CementService service;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private PlanStatusResponse prepareExceedingPlan(String reqId) {
        PlanResultResponse p = service.submit(TestFixtures.submit(reqId, KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));
        PlanStatusResponse plan = service.execute(new ExecutePlanRequest(p.planNo()));
        service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(8, 15), "460", null, null)));
        service.issueCommand(new CommandActionRequest(reqId + "-ISSUE", plan.planNo(),
                DAY.atTime(8, 0).toString()));
        return plan;
    }

    @Test
    void confirm_same_key_same_params_replays_first_result() {
        PlanStatusResponse plan = prepareExceedingPlan("REQ-CIDEM-1");
        var req = new CommandActionRequest("REQ-CIDEM-C1", plan.planNo(),
                DAY.atTime(8, 0).toString());
        var first = service.confirmCommand(req);
        assertThat(first.status()).isEqualTo("CONFIRMED");
        var replay = service.confirmCommand(req);
        assertThat(replay.status()).isEqualTo("CONFIRMED");
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.confirmedAt()).isEqualTo(first.confirmedAt());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reduction_command WHERE plan_id = ? AND status = 'CONFIRMED'",
                Integer.class, plan.planId())).isEqualTo(1);
    }

    @Test
    void confirm_same_key_different_params_conflicts() {
        PlanStatusResponse plan = prepareExceedingPlan("REQ-CIDEM-2");
        var req = new CommandActionRequest("REQ-CIDEM-C2", plan.planNo(),
                DAY.atTime(8, 0).toString());
        service.confirmCommand(req);
        var mutated = new CommandActionRequest("REQ-CIDEM-C2", plan.planNo(),
                DAY.atTime(9, 0).toString());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmCommand(mutated));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.IDEMPOTENT_CONFLICT);
    }

    @Test
    void cannot_confirm_before_issue() {
        PlanResultResponse p = service.submit(TestFixtures.submit("REQ-CIDEM-3", KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));
        service.execute(new ExecutePlanRequest(p.planNo()));
        service.ingestReadings(TestFixtures.readings(p.planNo(),
                TestFixtures.reading("P1", DAY.atTime(8, 15), "460", null, null)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirmCommand(
                new CommandActionRequest("REQ-CIDEM-C3", p.planNo(), DAY.atTime(8, 0).toString())));
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.ILLEGAL_STATE);
    }
}
