package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.CloseRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.PhotoRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReinspectionRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.CaseView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.dto.Views.PhotoResult;
import com.example.starter.damage.service.DamageCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 残损检验业务流测试：使用真实 H2（MySQL 兼容模式）验证状态机、
 * 30% 全损阈值、批复 7 个自然日有效期、返修一次限制、判定唯一与幂等语义。
 */
@SpringBootTest
class DamageFlowServiceTest {

    @Autowired
    private DamageCaseService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
    }

    private long newCase(String containerNo, String containerValue) {
        return service.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal(containerValue))).id();
    }

    private void survey(long caseId, String requestId, DamageItemInput... items) {
        service.survey(caseId, new SurveyRequest(requestId, List.of(items)));
    }

    private DamageItemInput item(String repairCost) {
        return new DamageItemInput("DN", "侧板", "凹痕一处", Responsibility.OWNER, new BigDecimal(repairCost));
    }

    private DamageItemInput terminalItem(String repairCost) {
        return new DamageItemInput("BR", "底梁", "弯曲变形", Responsibility.TERMINAL, new BigDecimal(repairCost));
    }

    private void approveAndComplete(long caseId, LocalDate approvalDate, LocalDate completionDate) {
        service.approve(caseId, new ApprovalRequest("ap-" + UUID.randomUUID(), approvalDate));
        service.completeRepair(caseId, new CompletionRequest("cp-" + UUID.randomUUID(), completionDate));
    }

    @Test
    void exactlyThirtyPercentIsRepair() {
        long caseId = newCase("TEMU1000001", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("3000.00"));

        EstimateView estimate = service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));

        assertThat(estimate.determination()).isEqualTo(Determination.REPAIR);
        assertThat(estimate.totalCost()).isEqualByComparingTo("3000.00");
        assertThat(estimate.version()).isEqualTo(1);
    }

    @Test
    void overThirtyPercentIsTotalLossAndCannotApproveRepair() {
        long caseId = newCase("TEMU1000002", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("3000.01"));

        EstimateView estimate = service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        assertThat(estimate.determination()).isEqualTo(Determination.TOTAL_LOSS);

        // 判定唯一：全损案件不得批复修理
        assertThatThrownBy(() -> service.approve(caseId, new ApprovalRequest("ap-x1", LocalDate.of(2026, 9, 1))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        // 全损结案为终态
        CaseView closed = service.close(caseId, new CloseRequest("cl-" + UUID.randomUUID()));
        assertThat(closed.status()).isEqualTo(CaseStatus.TOTAL_LOSS);
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void repairDeterminationCannotCloseAsTotalLoss() {
        long caseId = newCase("TEMU1000003", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"));
        EstimateView estimate = service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        assertThat(estimate.determination()).isEqualTo(Determination.REPAIR);
        service.approve(caseId, new ApprovalRequest("ap-" + UUID.randomUUID(), LocalDate.of(2026, 9, 1)));

        // 修理判定案件在批复后不得直接按全损结案（判定唯一）
        assertThatThrownBy(() -> service.close(caseId, new CloseRequest("cl-x1")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void completionOnExactDaySevenIsValid() {
        long caseId = newCase("TEMU1000004", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"));
        service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        service.approve(caseId, new ApprovalRequest("ap-" + UUID.randomUUID(), LocalDate.of(2026, 9, 1)));

        // 批复日 9-01 为第 1 日，9-07 恰为第 7 日，完工有效
        var completion = service.completeRepair(caseId,
                new CompletionRequest("cp-" + UUID.randomUUID(), LocalDate.of(2026, 9, 7)));

        assertThat(completion.completionDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(completion.rework()).isFalse();
        assertThat(service.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);
    }

    @Test
    void overdueCompletionRejectedAndReestimateRequired() {
        long caseId = newCase("TEMU1000005", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"));
        service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        service.approve(caseId, new ApprovalRequest("ap-" + UUID.randomUUID(), LocalDate.of(2026, 9, 1)));

        // 9-08 为第 8 日，逾期须重新估价
        assertThatThrownBy(() -> service.completeRepair(caseId,
                new CompletionRequest("cp-x1", LocalDate.of(2026, 9, 8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("逾期");

        // 重新估价产生新版本，旧批复失效
        EstimateView second = service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        assertThat(second.version()).isEqualTo(2);
        CaseDetailView detail = service.detail(caseId);
        assertThat(detail.estimates()).hasSize(2);
        assertThat(detail.estimates().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(detail.estimates().get(1).status()).isEqualTo("CURRENT");
        assertThat(detail.approvals().get(0).status()).isEqualTo("SUPERSEDED");

        // 重新批复后在有效期内完工
        approveAndComplete(caseId, LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 14));
        assertThat(service.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);
    }

    @Test
    void reworkAllowedOnlyOnceThenTotalLoss() {
        long caseId = newCase("TEMU1000006", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"));
        service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        approveAndComplete(caseId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3));

        // 首次复检不合格 → 返修一次
        CaseView afterFirstFail = service.reinspect(caseId, new ReinspectionRequest("ri-" + UUID.randomUUID(), false));
        assertThat(afterFirstFail.status()).isEqualTo(CaseStatus.REWORK);
        assertThat(afterFirstFail.reworkCount()).isEqualTo(1);

        // 返修完工
        var reworkCompletion = service.completeRepair(caseId,
                new CompletionRequest("cp-" + UUID.randomUUID(), LocalDate.of(2026, 9, 5)));
        assertThat(reworkCompletion.rework()).isTrue();

        // 返修后复检仍不合格 → 转全损评估
        CaseView afterSecondFail = service.reinspect(caseId, new ReinspectionRequest("ri-" + UUID.randomUUID(), false));
        assertThat(afterSecondFail.status()).isEqualTo(CaseStatus.TOTAL_LOSS_PENDING);
        assertThat(afterSecondFail.determination()).isEqualTo(Determination.TOTAL_LOSS);

        CaseView closed = service.close(caseId, new CloseRequest("cl-" + UUID.randomUUID()));
        assertThat(closed.status()).isEqualTo(CaseStatus.TOTAL_LOSS);
    }

    @Test
    void fullRepairFlowReleasesAndFreesContainer() {
        long caseId = newCase("TEMU1000007", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"), terminalItem("500.00"));
        EstimateView estimate = service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        assertThat(estimate.totalCost()).isEqualByComparingTo("1500.00");
        approveAndComplete(caseId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));

        CaseView passed = service.reinspect(caseId, new ReinspectionRequest("ri-" + UUID.randomUUID(), true));
        assertThat(passed.status()).isEqualTo(CaseStatus.REPAIR_PASSED);

        // 放行与结案同事务完成
        CaseView closed = service.close(caseId, new CloseRequest("cl-" + UUID.randomUUID()));
        assertThat(closed.status()).isEqualTo(CaseStatus.RELEASED);
        assertThat(closed.closedAt()).isNotNull();

        // 箱号已释放，可登记新残损案
        long nextCase = newCase("TEMU1000007", "10000.00");
        assertThat(nextCase).isNotEqualTo(caseId);
    }

    @Test
    void idempotentReplayReturnsFirstResultWithoutNewVersion() {
        long caseId = newCase("TEMU1000008", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"));

        EstimateView first = service.estimate(caseId, new EstimateRequest("est-replay-1"));
        EstimateView replay = service.estimate(caseId, new EstimateRequest("est-replay-1"));

        assertThat(replay).isEqualTo(first);
        assertThat(service.detail(caseId).estimates()).hasSize(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndNoNewEstimateVersion() {
        long caseId = newCase("TEMU1000009", "10000.00");
        survey(caseId, "svy-conflict-1", item("1000.00"));

        // 同 requestId 异参 → 409，且不产生新估价版本、不改变已登记残损项
        assertThatThrownBy(() -> survey(caseId, "svy-conflict-1", item("2000.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        CaseDetailView detail = service.detail(caseId);
        assertThat(detail.estimates()).isEmpty();
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).repairCost()).isEqualByComparingTo("1000.00");
    }

    @Test
    void sameRegisterKeyDifferentParamsReturns409() {
        service.register(new RegisterRequest("reg-conflict-1", "TEMU1000010", new BigDecimal("10000.00")));

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("reg-conflict-1", "TEMU1000010", new BigDecimal("20000.00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void closeIsTerminalAndLatePhotoOnlyRecordsEvent() {
        long caseId = newCase("TEMU1000011", "10000.00");
        survey(caseId, "svy-" + UUID.randomUUID(), item("1000.00"));
        service.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        approveAndComplete(caseId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
        service.reinspect(caseId, new ReinspectionRequest("ri-" + UUID.randomUUID(), true));

        CaseView closed = service.close(caseId, new CloseRequest("cl-" + UUID.randomUUID()));
        assertThat(closed.status()).isEqualTo(CaseStatus.RELEASED);

        // 重复结案（不同 requestId）返回幂等成功
        CaseView again = service.close(caseId, new CloseRequest("cl-" + UUID.randomUUID()));
        assertThat(again.status()).isEqualTo(CaseStatus.RELEASED);
        assertThat(again.closedAt()).isEqualTo(closed.closedAt());

        // 结案后迟到检验照片只记迟到事件，状态不变
        PhotoResult photo = service.photo(caseId, new PhotoRequest("photo-late-001"));
        assertThat(photo.result()).isEqualTo("LATE_RECORDED");
        CaseDetailView detail = service.detail(caseId);
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.RELEASED);
        assertThat(detail.events())
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo("LATE_PHOTO");
                    assertThat(e.payload()).isEqualTo("photo-late-001");
                });
    }

    @Test
    void unknownIiclCodeRejected() {
        long caseId = newCase("TEMU1000012", "10000.00");
        DamageItemInput bad = new DamageItemInput("XX", "侧板", "未知代码", Responsibility.OWNER, new BigDecimal("10.00"));

        assertThatThrownBy(() -> survey(caseId, "svy-" + UUID.randomUUID(), bad))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void duplicateActiveCaseForSameContainerRejected() {
        newCase("TEMU1000013", "10000.00");

        assertThatThrownBy(() -> newCase("TEMU1000013", "10000.00"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
