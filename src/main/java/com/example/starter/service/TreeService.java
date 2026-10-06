package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.api.Requests.ReevaluateRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Responses.ReevaluateResponse;
import com.example.starter.api.Responses.TreeRegisterResponse;
import com.example.starter.domain.PlanRow;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.SegmentRow;
import com.example.starter.domain.TreeRow;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.TreeRepository;

/**
 * 树木建档与年度风险复评。一级风险树木自动生成 7 日期限的修剪计划；
 * 复评每年一次，评级变化触发计划重排，重排不修改在途许可的既定时限。
 */
@Service
public class TreeService {

    /** 一级风险修剪期限天数，恰第7日闭环有效。 */
    public static final int PRUNE_DEADLINE_DAYS = 7;
    /** 复评最小间隔天数（每年一次）。 */
    public static final int REEVALUATION_INTERVAL_DAYS = 365;

    private final TreeRepository treeRepository;
    private final SegmentRepository segmentRepository;
    private final PlanRepository planRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TreeService(TreeRepository treeRepository, SegmentRepository segmentRepository,
                       PlanRepository planRepository, IdempotencyService idempotency, Clock clock) {
        this.treeRepository = treeRepository;
        this.segmentRepository = segmentRepository;
        this.planRepository = planRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ServiceResult register(TreeRegisterRequest request) {
        return idempotency.execute("tree.register", request.requestId(), request,
                () -> doRegister(request));
    }

    private ServiceResult doRegister(TreeRegisterRequest request) {
        SegmentRow segment = segmentRepository.findByCode(request.segmentCode())
                .orElseThrow(() -> new BusinessException(404, "SEGMENT_NOT_FOUND",
                        "路段不存在: " + request.segmentCode()));
        if (treeRepository.findByCode(request.treeCode()).isPresent()) {
            throw new BusinessException(409, "TREE_EXISTS", "树木编号已存在: " + request.treeCode());
        }
        double score = RiskModel.score(request.tiltDegrees(), request.diseaseLevel(),
                request.ageYears(), request.windExposure());
        int level = RiskModel.level(score);
        LocalDateTime now = LocalDateTime.now(clock);
        long treeId = treeRepository.insert(segment.id(), request.treeCode(), request.tiltDegrees(),
                request.diseaseLevel(), request.ageYears(), request.windExposure(),
                score, level, now);
        Long planId = null;
        LocalDateTime deadline = null;
        if (level == 1) {
            deadline = now.plusDays(PRUNE_DEADLINE_DAYS);
            planId = planRepository.insert(treeId, segment.id(), level, PlanStatus.PENDING, now, deadline);
        }
        return new ServiceResult(200, new TreeRegisterResponse(treeId, request.treeCode(),
                segment.code(), score, level, planId, deadline));
    }

    public ServiceResult reevaluate(ReevaluateRequest request) {
        return idempotency.execute("tree.reevaluate", request.requestId(), request,
                () -> doReevaluate(request));
    }

    private ServiceResult doReevaluate(ReevaluateRequest request) {
        TreeRow tree = treeRepository.findByCode(request.treeCode())
                .orElseThrow(() -> new BusinessException(404, "TREE_NOT_FOUND",
                        "树木不存在: " + request.treeCode()));
        LocalDateTime now = LocalDateTime.now(clock);
        if (tree.evaluatedAt().plusDays(REEVALUATION_INTERVAL_DAYS).isAfter(now)) {
            throw new BusinessException(409, "REEVALUATION_TOO_EARLY",
                    "风险复评每年一次，距上次评估不足一年");
        }
        double score = RiskModel.score(request.tiltDegrees(), request.diseaseLevel(),
                request.ageYears(), request.windExposure());
        int newLevel = RiskModel.level(score);
        treeRepository.updateAssessment(tree.id(), request.tiltDegrees(), request.diseaseLevel(),
                request.ageYears(), request.windExposure(), score, newLevel, now);

        boolean rescheduled = false;
        Long newPlanId = null;
        LocalDateTime newDeadline = null;
        if (newLevel != tree.riskLevel()) {
            // 评级变化触发修剪计划重排；在途许可行的既定时限不被修改
            List<PlanRow> openPlans = planRepository.findOpenByTree(tree.id());
            for (PlanRow plan : openPlans) {
                planRepository.updateStatus(plan.id(), PlanStatus.RESCHEDULED);
            }
            rescheduled = true;
            if (newLevel == 1) {
                newDeadline = now.plusDays(PRUNE_DEADLINE_DAYS);
                newPlanId = planRepository.insert(tree.id(), tree.segmentId(), newLevel,
                        PlanStatus.PENDING, now, newDeadline);
            }
        }
        return new ServiceResult(200, new ReevaluateResponse(tree.treeCode(), tree.riskLevel(),
                newLevel, rescheduled, newPlanId, newDeadline));
    }
}
