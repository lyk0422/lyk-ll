package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.ReevaluateRequest;
import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Responses.PermitResponse;
import com.example.starter.api.Responses.ReevaluateResponse;
import com.example.starter.api.Responses.TreeRegisterResponse;
import com.example.starter.domain.PermitRow;
import com.example.starter.domain.PlanRow;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.WorkType;
import com.example.starter.repo.PermitRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.service.BusinessException;
import com.example.starter.service.PermitService;
import com.example.starter.service.SegmentService;
import com.example.starter.service.ServiceResult;
import com.example.starter.service.TreeService;

/**
 * 年度风险复评：每年一次；评级变化触发修剪计划重排，重排不改变在途许可的既定时限。
 */
class ReevaluationIT extends BaseIntegrationTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private TreeService treeService;
    @Autowired
    private PermitService permitService;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PermitRepository permitRepository;

    @Test
    void ratingDownReschedulesPlanButKeepsPermitDeadline() {
        segmentService.register(new SegmentRegisterRequest("seg-r1", "SEG-R1", "复评路"));
        ServiceResult treeResult = treeService.register(new TreeRegisterRequest(
                "tree-r1", "SEG-R1", "T-R1", 30.0, 2, 40, 2));
        TreeRegisterResponse tree = (TreeRegisterResponse) treeResult.body();
        LocalDateTime originalDeadline = tree.deadline();
        ServiceResult permitResult = permitService.apply(new PermitApplyRequest(
                "permit-r1", "SEG-R1", tree.planId(), WorkType.HIGH_ALTITUDE));
        String permitNo = ((PermitResponse) permitResult.body()).permitNo();

        // 一年后复评，风险降为四级
        clock.plus(Duration.ofDays(365));
        ServiceResult reeval = treeService.reevaluate(new ReevaluateRequest(
                "reeval-r1", "T-R1", 2.0, 0, 41, 0));
        ReevaluateResponse body = (ReevaluateResponse) reeval.body();
        assertEquals(1, body.oldLevel());
        assertEquals(4, body.newLevel());
        assertTrue(body.rescheduled());
        assertNull(body.newPlanId());

        // 原计划被重排
        PlanRow oldPlan = planRepository.findById(tree.planId()).orElseThrow();
        assertEquals(PlanStatus.RESCHEDULED, oldPlan.status());
        // 在途许可的既定时限不变
        PermitRow permit = permitRepository.findByPermitNo(permitNo).orElseThrow();
        assertEquals(originalDeadline, permit.deadline());
    }

    @Test
    void reevaluationWithinOneYearRejected() {
        segmentService.register(new SegmentRegisterRequest("seg-r2", "SEG-R2", "复评路2"));
        treeService.register(new TreeRegisterRequest("tree-r2", "SEG-R2", "T-R2", 30.0, 2, 40, 2));
        clock.plus(Duration.ofDays(100));
        BusinessException e = assertThrows(BusinessException.class, () ->
                treeService.reevaluate(new ReevaluateRequest("reeval-r2", "T-R2", 2.0, 0, 41, 0)));
        assertEquals(409, e.status());
        assertEquals("REEVALUATION_TOO_EARLY", e.code());
    }

    @Test
    void ratingUpToLevel1CreatesNewPlan() {
        segmentService.register(new SegmentRegisterRequest("seg-r3", "SEG-R3", "复评路3"));
        ServiceResult treeResult = treeService.register(new TreeRegisterRequest(
                "tree-r3", "SEG-R3", "T-R3", 10.0, 1, 20, 1));
        assertEquals(2, ((TreeRegisterResponse) treeResult.body()).riskLevel());

        clock.plus(Duration.ofDays(365));
        LocalDateTime reevalTime = LocalDateTime.now(clock);
        ServiceResult reeval = treeService.reevaluate(new ReevaluateRequest(
                "reeval-r3", "T-R3", 30.0, 2, 21, 2));
        ReevaluateResponse body = (ReevaluateResponse) reeval.body();
        assertEquals(2, body.oldLevel());
        assertEquals(1, body.newLevel());
        assertTrue(body.rescheduled());
        assertEquals(reevalTime.plusDays(7), body.newDeadline());
        PlanRow newPlan = planRepository.findById(body.newPlanId()).orElseThrow();
        assertEquals(PlanStatus.PENDING, newPlan.status());
    }
}
