package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.ReportSubmitRequest;
import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Responses.PermitResponse;
import com.example.starter.api.Responses.ReportResponse;
import com.example.starter.api.Responses.TreeRegisterResponse;
import com.example.starter.domain.PlanRow;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.WorkType;
import com.example.starter.repo.PlanRepository;
import com.example.starter.service.LedgerService;
import com.example.starter.service.PermitService;
import com.example.starter.service.SegmentService;
import com.example.starter.service.ServiceResult;
import com.example.starter.service.TreeService;

/**
 * 一级风险 7 日修剪期限：恰第 7 日闭环有效，逾期升级督办。
 */
class PruningDeadlineIT extends BaseIntegrationTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private TreeService treeService;
    @Autowired
    private PermitService permitService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private PlanRepository planRepository;

    private TreeRegisterResponse registerLevel1Tree(String suffix) {
        segmentService.register(new SegmentRegisterRequest("seg-" + suffix, "SEG-" + suffix, "测试路" + suffix));
        ServiceResult result = treeService.register(new TreeRegisterRequest(
                "tree-" + suffix, "SEG-" + suffix, "T-" + suffix, 30.0, 2, 40, 2));
        return (TreeRegisterResponse) result.body();
    }

    private String applyPermit(String suffix, long planId) {
        ServiceResult result = permitService.apply(new PermitApplyRequest(
                "permit-" + suffix, "SEG-" + suffix, planId, WorkType.HIGH_ALTITUDE));
        return ((PermitResponse) result.body()).permitNo();
    }

    @Test
    void level1TreeGetsSevenDayPlan() {
        TreeRegisterResponse tree = registerLevel1Tree("d1");
        assertEquals(1, tree.riskLevel());
        assertNotNull(tree.planId());
        LocalDateTime expectedDeadline = LocalDateTime.now(clock).plusDays(7);
        assertEquals(expectedDeadline, tree.deadline());
    }

    @Test
    void closureExactlyOnDay7IsOnTime() {
        TreeRegisterResponse tree = registerLevel1Tree("d2");
        String permitNo = applyPermit("d2", tree.planId());
        // 恰第 7 日：closedAt == deadline
        clock.plus(Duration.ofDays(7));
        ServiceResult report = permitService.submitReport(new ReportSubmitRequest(
                "report-d2", permitNo, "photo-before-1", "photo-after-1", "弃枝2车已清运"));
        assertTrue(((ReportResponse) report.body()).onTime());
        PlanRow plan = planRepository.findById(tree.planId()).orElseThrow();
        assertEquals(PlanStatus.CLOSED, plan.status());
        assertEquals(Boolean.TRUE, plan.closedOnTime());
        assertEquals(plan.deadline(), plan.closedAt());
    }

    @Test
    void closureAfterDeadlineIsNotOnTime() {
        TreeRegisterResponse tree = registerLevel1Tree("d3");
        String permitNo = applyPermit("d3", tree.planId());
        clock.plus(Duration.ofDays(7).plusSeconds(1));
        ServiceResult report = permitService.submitReport(new ReportSubmitRequest(
                "report-d3", permitNo, "photo-before-1", "photo-after-1", "弃枝2车已清运"));
        assertFalse(((ReportResponse) report.body()).onTime());
        PlanRow plan = planRepository.findById(tree.planId()).orElseThrow();
        assertEquals(PlanStatus.CLOSED, plan.status());
        assertEquals(Boolean.FALSE, plan.closedOnTime());
    }

    @Test
    void overduePlanEscalatesToSupervision() {
        TreeRegisterResponse tree = registerLevel1Tree("d4");
        // 恰第 7 日未闭环仍不算逾期
        clock.plus(Duration.ofDays(7));
        ledgerService.ledger();
        assertEquals(PlanStatus.PENDING, planRepository.findById(tree.planId()).orElseThrow().status());
        // 超过期限即升级督办
        clock.plus(Duration.ofSeconds(1));
        var entries = ledgerService.ledger();
        assertEquals(PlanStatus.ESCALATED, planRepository.findById(tree.planId()).orElseThrow().status());
        var entry = entries.stream().filter(e -> e.segmentCode().equals("SEG-d4")).findFirst().orElseThrow();
        assertEquals(1, entry.escalatedPlans());
        assertEquals(0.0, entry.closureRate());
    }
}
