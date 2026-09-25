package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.dto.ApprovalResponse;
import com.example.starter.rt.dto.AuditResponse;
import com.example.starter.rt.dto.ClosureResponse;
import com.example.starter.rt.dto.FractionResponse;
import com.example.starter.rt.dto.LateFractionResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.VerificationResponse;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.service.WorkflowService;
import com.example.starter.rt.web.ApiException;

/**
 * 放疗计划工作流主流程与失败分支 H2 集成测试。
 */
class WorkflowLifecycleTest extends AbstractIntegrationTest {

    private static final String PATIENT = "P1";
    private static final String PLAN = "PLAN_A";
    private static final String TPL = "TPL1";

    @Autowired
    private WorkflowService workflow;

    /** 准备：患者 + 模板 + 一个剂量合法、勾稽一致的 DESIGN 计划 v1（恰等限值）。 */
    private long setupValidPlan() {
        patient(PATIENT, "测试患者甲");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        // 200cGy × 25 = 5000cGy；脊髓恰等 4500，膀胱恰等 40.00
        return createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25,
                4500L, new BigDecimal("40.00"));
    }

    private AuditResponse auditOk(String reqId) {
        RecordedResponse result = workflow.audit(PATIENT, PLAN, 1, reqId);
        assertThat(result.httpStatus()).isEqualTo(200);
        return (AuditResponse) result.body();
    }

    private ApprovalResponse approveOk(int versionNo, String reqId) {
        RecordedResponse result = workflow.approve(PATIENT, PLAN, versionNo, reqId);
        assertThat(result.httpStatus()).isEqualTo(200);
        return (ApprovalResponse) result.body();
    }

    @Test
    @DisplayName("约束恰等限值 + 总剂量勾稽一致：物理审核通过")
    void auditPassesAtExactLimit() {
        setupValidPlan();
        AuditResponse audit = auditOk("AUD-1");

        assertThat(audit.status()).isEqualTo(PlanStatus.REVIEWED.name());
        assertThat(audit.passed()).isTrue();
        assertThat(audit.totalDoseCheckOk()).isTrue();
        assertThat(audit.items()).hasSize(2);
        assertThat(audit.items()).allSatisfy(item -> assertThat(item.withinLimit()).isTrue());
        assertThat(audit.replayed()).isFalse();

        PlanVersionRow row = planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow();
        assertThat(row.status()).isEqualTo(PlanStatus.REVIEWED.name());
        assertThat(row.reviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("任一约束超限：物理审核 422 拒绝，版本停留 DESIGN")
    void auditRejectsOverLimit() {
        patient(PATIENT, "测试患者甲");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        // 脊髓 4501 超 4500 限值
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25,
                4501L, new BigDecimal("40.00"));

        RecordedResponse result = workflow.audit(PATIENT, PLAN, 1, "AUD-BAD");
        assertThat(result.httpStatus()).isEqualTo(422);
        RejectionBody body = (RejectionBody) result.body();
        assertThat(body.code()).isEqualTo("AUDIT_REJECTED");
        assertThat(body.details()).isNotEmpty();

        PlanVersionRow row = planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow();
        assertThat(row.status()).isEqualTo(PlanStatus.DESIGN.name());
        assertThat(workflow.recomputeAudit(PATIENT, PLAN, 1).auditStored()).isFalse();
    }

    @Test
    @DisplayName("总剂量勾稽不一致（分次剂量×次数≠处方）：422 拒绝")
    void auditRejectsTotalDoseMismatch() {
        patient(PATIENT, "测试患者甲");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        // 201 × 25 = 5025 ≠ 5000
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 201L, 25,
                4000L, new BigDecimal("30.00"));

        RecordedResponse result = workflow.audit(PATIENT, PLAN, 1, "AUD-MISMATCH");
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("AUDIT_REJECTED");
    }

    @Test
    @DisplayName("未审核先批准：422 拒绝")
    void approveRequiresReview() {
        setupValidPlan();
        RecordedResponse result = workflow.approve(PATIENT, PLAN, 1, "APV-EARLY");
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("PLAN_NOT_REVIEWED");
    }

    @Test
    @DisplayName("审核→批准→模体验证恰3%通过→分次连续执行→结案 全主流程")
    void fullHappyPathWithExactThreePercent() {
        setupValidPlan();
        auditOk("AUD-2");
        approveOk(1, "APV-2");

        // 恰 3%：计划200，实测206
        RecordedResponse verifyResult = workflow.verify(PATIENT, PLAN, 1, 200L, 206L, "VER-2");
        assertThat(verifyResult.httpStatus()).isEqualTo(200);
        VerificationResponse verify = (VerificationResponse) verifyResult.body();
        assertThat(verify.passed()).isTrue();
        assertThat(verify.deviationPct()).isEqualByComparingTo("3.0000");
        assertThat(verify.status()).isEqualTo(PlanStatus.VERIFIED.name());

        // 连续执行第 1、2 次
        RecordedResponse f1 = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-1");
        assertThat(f1.httpStatus()).isEqualTo(200);
        assertThat(((FractionResponse) f1.body()).fractionNo()).isEqualTo(1);
        RecordedResponse f2 = workflow.recordFraction(PATIENT, PLAN, 2, 200L, "FR-2");
        assertThat(f2.httpStatus()).isEqualTo(200);

        assertThat(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().status())
                .isEqualTo(PlanStatus.EXECUTING.name());
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(2);

        // 结案
        RecordedResponse close = workflow.close(PATIENT, PLAN, "CLS-1");
        assertThat(close.httpStatus()).isEqualTo(200);
        assertThat(((ClosureResponse) close.body()).status()).isEqualTo(PlanStatus.CLOSED.name());
        assertThat(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().status())
                .isEqualTo(PlanStatus.CLOSED.name());
    }

    @Test
    @DisplayName("模体偏差超过3%：422 不得执行，版本停留 APPROVED，失败留痕")
    void verifyOverThreePercentRejected() {
        setupValidPlan();
        auditOk("AUD-3");
        approveOk(1, "APV-3");

        RecordedResponse result = workflow.verify(PATIENT, PLAN, 1, 200L, 207L, "VER-3");
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code())
                .isEqualTo("VERIFICATION_DEVIATION_EXCEEDED");

        PlanVersionRow row = planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow();
        assertThat(row.status()).isEqualTo(PlanStatus.APPROVED.name());
        assertThat(row.verifiedAt()).isNull();
        // 失败也进入验证链
        assertThat(workflow.verificationChain(PATIENT, PLAN, 1)).hasSize(1);
    }

    @Test
    @DisplayName("未通过模体验证先执行分次：422 拒绝")
    void fractionRequiresVerification() {
        setupValidPlan();
        auditOk("AUD-4");
        approveOk(1, "APV-4");

        RecordedResponse result = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-EARLY");
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("FIRST_VERIFICATION_REQUIRED");
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).isEmpty();
    }

    @Test
    @DisplayName("跳次执行：409 拒绝且告警；随后补齐正确分次号成功")
    void skipFractionRejectedWithAlert() {
        setupVerifiedPlan();

        RecordedResponse skip = workflow.recordFraction(PATIENT, PLAN, 2, 200L, "FR-SKIP");
        assertThat(skip.httpStatus()).isEqualTo(409);
        RejectionBody body = (RejectionBody) skip.body();
        assertThat(body.code()).isEqualTo("FRACTION_SKIP_DETECTED");
        assertThat(body.alert()).isTrue();
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).isEmpty();

        // 补齐第 1 次成功
        RecordedResponse first = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-FIRST");
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }

    @Test
    @DisplayName("分次号自然键去重：重复第1次 409，且不产生新分次")
    void duplicateFractionNumberRejected() {
        setupVerifiedPlan();
        assertThat(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-D1").httpStatus())
                .isEqualTo(200);

        RecordedResponse dup = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-D2");
        assertThat(dup.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) dup.body()).code()).isEqualTo("FRACTION_ALREADY_RECORDED");
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }

    @Test
    @DisplayName("同键同参重放返回首次结果；同键异参返回409且不产生新分次")
    void idempotencyReplayAndConflict() {
        setupVerifiedPlan();

        RecordedResponse first = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-IDEM");
        assertThat(first.httpStatus()).isEqualTo(200);

        // 同键同参重放：replayed=true，台账仍只有 1 条
        RecordedResponse replay = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-IDEM");
        assertThat(replay.httpStatus()).isEqualTo(200);
        java.util.Map<?, ?> replayBody = (java.util.Map<?, ?>) replay.body();
        assertThat(replayBody.get("replayed")).isEqualTo(true);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);

        // 同键异参：409，不产生新分次
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> workflow.recordFraction(PATIENT, PLAN, 2, 200L, "FR-IDEM"));
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT");
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }

    @Test
    @DisplayName("审核同键重放返回首次通过结果")
    void auditReplayReturnsFirstResult() {
        setupValidPlan();
        auditOk("AUD-IDEM");
        RecordedResponse replay = workflow.audit(PATIENT, PLAN, 1, "AUD-IDEM");
        assertThat(replay.httpStatus()).isEqualTo(200);
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) replay.body();
        assertThat(body.get("replayed")).isEqualTo(true);
    }

    @Test
    @DisplayName("结案终态：重复结案幂等成功；结案后迟到分次只登记迟到事件")
    void closureTerminalAndLateFraction() {
        setupVerifiedPlan();
        workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-CLS-1");
        workflow.close(PATIENT, PLAN, "CLS-IDEM");

        // 重复结案：不同 requestId 也幂等成功
        RecordedResponse again = workflow.close(PATIENT, PLAN, "CLS-IDEM-2");
        assertThat(again.httpStatus()).isEqualTo(200);
        assertThat(((ClosureResponse) again.body()).status()).isEqualTo(PlanStatus.CLOSED.name());

        // 结案后迟到分次：200 但只登记迟到事件
        RecordedResponse late = workflow.recordFraction(PATIENT, PLAN, 2, 200L, "FR-LATE");
        assertThat(late.httpStatus()).isEqualTo(200);
        LateFractionResponse lateBody = (LateFractionResponse) late.body();
        assertThat(lateBody.result()).isEqualTo("LATE_EVENT_RECORDED");
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1); // 台账不增加
        assertThat(workflow.lateFractions(PATIENT, PLAN)).hasSize(1);
    }

    @Test
    @DisplayName("同一患者同时刻只允许一个执行中计划：另一计划批准 409")
    void onlyOneActivePlanPerPatient() {
        setupValidPlan();
        auditOk("AUD-UNI");
        approveOk(1, "APV-UNI");

        // 为同一患者建另一个计划并审核通过
        createPlanVersion(PATIENT, "PLAN_B", 1, TPL, 6000L, 200L, 30,
                3000L, new BigDecimal("20.00"));
        workflow.audit(PATIENT, "PLAN_B", 1, "AUD-B");
        RecordedResponse reject = workflow.approve(PATIENT, "PLAN_B", 1, "APV-B");
        assertThat(reject.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) reject.body()).code()).isEqualTo("ANOTHER_PLAN_ACTIVE");
    }

    private void setupVerifiedPlan() {
        setupValidPlan();
        auditOk("AUD-V");
        approveOk(1, "APV-V");
        RecordedResponse verify = workflow.verify(PATIENT, PLAN, 1, 200L, 200L, "VER-V");
        assertThat(verify.httpStatus()).isEqualTo(200);
    }
}
