package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.repository.PlanRepository.ActivePlanRow;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.service.WorkflowService;

/**
 * 计划换版与旧版本失效的原子性 H2 测试：同一事务内完成换版，不得新旧版本同时可执行。
 */
class PlanRevisionTest extends AbstractIntegrationTest {

    private static final String PATIENT = "P2";
    private static final String PLAN = "PLAN_R";
    private static final String TPL = "TPL2";

    @Autowired
    private WorkflowService workflow;

    @Test
    @DisplayName("换版批准：旧版本同事务即刻失效，守卫指向新版本，新旧版本不会同时可执行")
    void revisionInvalidatesOldVersionAtomically() {
        patient(PATIENT, "测试患者乙");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));

        // v1 审核 + 批准
        assertThat(workflow.audit(PATIENT, PLAN, 1, "R-AUD-1").httpStatus()).isEqualTo(200);
        assertThat(workflow.approve(PATIENT, PLAN, 1, "R-APV-1").httpStatus()).isEqualTo(200);
        assertThat(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().status())
                .isEqualTo(PlanStatus.APPROVED.name());

        // 设计 v2（修正剂量），审核通过
        createPlanVersion(PATIENT, PLAN, 2, TPL, 5000L, 200L, 25, 4300L, new BigDecimal("30.00"));
        assertThat(workflow.audit(PATIENT, PLAN, 2, "R-AUD-2").httpStatus()).isEqualTo(200);

        // 批准 v2：v1 必须在同一事务内失效
        assertThat(workflow.approve(PATIENT, PLAN, 2, "R-APV-2").httpStatus()).isEqualTo(200);

        PlanVersionRow v1 = planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow();
        PlanVersionRow v2 = planRepository.findVersion(PATIENT, PLAN, 2).orElseThrow();
        assertThat(v2.status()).isEqualTo(PlanStatus.APPROVED.name());
        assertThat(v1.status()).isEqualTo(PlanStatus.INVALID.name());
        assertThat(v1.invalidatedAt()).isNotNull();

        ActivePlanRow active = planRepository.findActivePlan(PATIENT).orElseThrow();
        assertThat(active.planVersionId()).isEqualTo(v2.id());
        assertThat(active.status()).isEqualTo("ACTIVE");

        // 旧版本不能再模体验证
        RecordedResponse verifyOld = workflow.verify(PATIENT, PLAN, 1, 200L, 200L, "R-VER-OLD");
        assertThat(verifyOld.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) verifyOld.body()).code())
                .isIn("PLAN_VERSION_INVALID", "PLAN_NOT_APPROVED");

        // 可执行版本唯一：全部版本中仅 v2 可执行
        List<PlanVersionRow> all = planRepository.findVersions(PATIENT, PLAN);
        long executable = all.stream()
                .filter(v -> !PlanStatus.INVALID.name().equals(v.status())
                        && !PlanStatus.DESIGN.name().equals(v.status()))
                .filter(v -> v.id() == v2.id())
                .count();
        assertThat(executable).isEqualTo(1);
    }

    @Test
    @DisplayName("新版本未通过物理审核不能批准，旧版本保持可执行")
    void newVersionMustBeReviewedBeforeApproval() {
        patient(PATIENT, "测试患者乙");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        workflow.audit(PATIENT, PLAN, 1, "R2-AUD-1");
        workflow.approve(PATIENT, PLAN, 1, "R2-APV-1");

        createPlanVersion(PATIENT, PLAN, 2, TPL, 5000L, 200L, 25, 4300L, new BigDecimal("30.00"));
        RecordedResponse result = workflow.approve(PATIENT, PLAN, 2, "R2-APV-2");
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("PLAN_NOT_REVIEWED");

        // v1 仍 APPROVED，守卫未切换
        assertThat(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().status())
                .isEqualTo(PlanStatus.APPROVED.name());
        assertThat(planRepository.findActivePlan(PATIENT).orElseThrow().planVersionId())
                .isEqualTo(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().id());
    }

    @Test
    @DisplayName("审核复算：存储快照与按模板/申报值复算结果逐项一致")
    void auditSnapshotRecomputesConsistently() {
        patient(PATIENT, "测试患者乙");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25, 4500L, new BigDecimal("40.00"));
        workflow.audit(PATIENT, PLAN, 1, "R3-AUD");

        WorkflowService.AuditRecomputation recompute = workflow.recomputeAudit(PATIENT, PLAN, 1);
        assertThat(recompute.auditStored()).isTrue();
        assertThat(recompute.snapshotMatches()).isTrue();
        assertThat(recompute.recomputedItems()).hasSize(2);
        assertThat(recompute.recomputedItems()).allSatisfy(
                item -> assertThat(item.withinLimit()).isTrue());
    }
}
