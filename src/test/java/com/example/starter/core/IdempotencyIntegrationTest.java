package com.example.starter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.ConfirmCommandRequest;
import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.IdempotentEnvelope;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.support.TestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * requestId 幂等：同键同参回放首次结果，同键异参 409 且不改变执行参数。
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private FingerprintHasher hasher;

    private void seedValidLicense() {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    void sameKeySameParamsReturnsFirstResultAndReplayedFlag() {
        seedValidLicense();
        DeclarePlanRequest request = TestFixtures.declareRequest("idem-same", "0.4000");
        IdempotentEnvelope<PlanResponse> first = idempotencyService.execute(
                "DECLARE_PLAN", request.requestId(), hasher.sha256(request),
                PlanResponse.class, () -> planService.declare(request));
        assertThat(first.replayed()).isFalse();

        IdempotentEnvelope<PlanResponse> replay = idempotencyService.execute(
                "DECLARE_PLAN", request.requestId(), hasher.sha256(request),
                PlanResponse.class, () -> planService.declare(request));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.data().id()).isEqualTo(first.data().id());

        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM co_firing_plan WHERE declare_request_id = 'idem-same'",
                Integer.class);
        assertThat(planCount).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndKeepsFirstParams() {
        seedValidLicense();
        DeclarePlanRequest first = TestFixtures.declareRequest("idem-conflict", "0.4000");
        IdempotentEnvelope<PlanResponse> firstResult = idempotencyService.execute(
                "DECLARE_PLAN", first.requestId(), hasher.sha256(first),
                PlanResponse.class, () -> planService.declare(first));

        DeclarePlanRequest tampered = TestFixtures.declareRequest("idem-conflict", "0.3000");
        assertThatThrownBy(() -> idempotencyService.execute(
                "DECLARE_PLAN", tampered.requestId(), hasher.sha256(tampered),
                PlanResponse.class, () -> planService.declare(tampered)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));

        // 首次执行参数未被改变。
        PlanResponse stored = planService.get(firstResult.data().id());
        assertThat(stored.declaredHeatRatio()).isEqualByComparingTo(new BigDecimal("0.4000"));
        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM co_firing_plan WHERE declare_request_id = 'idem-conflict'",
                Integer.class);
        assertThat(planCount).isEqualTo(1);
    }

    @Test
    void confirmCommandSameKeyReplaysAndDifferentCommandConflicts() {
        seedValidLicense();
        Long planId = declareReviewAndExecute("idem-cmd");
        // 制造一次超标降掺指令。
        emissionService.ingest(planId, TestFixtures.singleReading("P1", "NOX",
                TestClockHelper.hour(9).plusMinutes(10), "450"));
        var evaluation = emissionService.evaluate(planId, TestClockHelper.hour(9));
        assertThat(evaluation.command()).isNotNull();
        Long commandId = evaluation.command().id();

        ConfirmCommandRequest confirm = new ConfirmCommandRequest("confirm-1");
        var first = idempotencyService.execute(
                "CONFIRM_COMMAND", confirm.requestId(),
                hasher.sha256(new ConfirmKey(planId, commandId)),
                com.example.starter.api.dto.CommandResponse.class,
                () -> commandService.confirm(planId, commandId, confirm.requestId()));
        assertThat(first.replayed()).isFalse();
        assertThat(first.data().status()).isEqualTo("CONFIRMED");

        var replay = idempotencyService.execute(
                "CONFIRM_COMMAND", confirm.requestId(),
                hasher.sha256(new ConfirmKey(planId, commandId)),
                com.example.starter.api.dto.CommandResponse.class,
                () -> commandService.confirm(planId, commandId, confirm.requestId()));
        assertThat(replay.replayed()).isTrue();
    }

    private Long declareReviewAndExecute(String requestId) {
        PlanResponse declared = planService.declare(TestFixtures.declareRequest(requestId, "0.4000"));
        Long id = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null)).id();
        return planService.execute(id).id();
    }

    /** 确认指纹载体（与控制器中的载体字段一致）。 */
    private record ConfirmKey(Long planId, Long commandId) {
    }
}
