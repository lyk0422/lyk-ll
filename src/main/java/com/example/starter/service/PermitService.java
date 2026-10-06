package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.ReportSubmitRequest;
import com.example.starter.api.Responses.PermitResponse;
import com.example.starter.api.Responses.ReportResponse;
import com.example.starter.domain.PermitRow;
import com.example.starter.domain.PermitStatus;
import com.example.starter.domain.PlanRow;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.SegmentRow;
import com.example.starter.domain.WorkType;
import com.example.starter.repo.FreezeRepository;
import com.example.starter.repo.PermitRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.ReportRepository;
import com.example.starter.repo.SegmentRepository;

/**
 * 作业许可与作业回传。同一路段同时刻仅允许一张执行中许可（数据库唯一约束保证）；
 * 大风冻结期间高风险路段禁止签发新的高空作业许可；
 * 回传必须包含前后照片标识与弃枝清运记录，回传即闭环修剪计划。
 */
@Service
public class PermitService {

    private final PermitRepository permitRepository;
    private final PlanRepository planRepository;
    private final SegmentRepository segmentRepository;
    private final FreezeRepository freezeRepository;
    private final ReportRepository reportRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public PermitService(PermitRepository permitRepository, PlanRepository planRepository,
                         SegmentRepository segmentRepository, FreezeRepository freezeRepository,
                         ReportRepository reportRepository, IdempotencyService idempotency,
                         Clock clock) {
        this.permitRepository = permitRepository;
        this.planRepository = planRepository;
        this.segmentRepository = segmentRepository;
        this.freezeRepository = freezeRepository;
        this.reportRepository = reportRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ServiceResult apply(PermitApplyRequest request) {
        return idempotency.execute("permit.apply", request.requestId(), request,
                () -> doApply(request));
    }

    private ServiceResult doApply(PermitApplyRequest request) {
        SegmentRow segment = segmentRepository.findByCode(request.segmentCode())
                .orElseThrow(() -> new BusinessException(404, "SEGMENT_NOT_FOUND",
                        "路段不存在: " + request.segmentCode()));
        PlanRow plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new BusinessException(404, "PLAN_NOT_FOUND",
                        "修剪计划不存在: " + request.planId()));
        if (plan.segmentId() != segment.id()) {
            throw new BusinessException(409, "PLAN_SEGMENT_MISMATCH", "计划与路段不匹配");
        }
        if (plan.status() != PlanStatus.PENDING && plan.status() != PlanStatus.ESCALATED) {
            throw new BusinessException(409, "PLAN_NOT_OPEN", "计划状态不允许申请许可: " + plan.status());
        }
        if (request.workType() == WorkType.HIGH_ALTITUDE
                && freezeRepository.existsBySegmentId(segment.id())) {
            throw new BusinessException(409, "SEGMENT_FROZEN",
                    "大风预警生效,高风险路段已冻结新高空作业许可: " + segment.code());
        }
        String permitNo = "PM-" + UUID.randomUUID();
        try {
            permitRepository.insert(permitNo, segment.id(), plan.id(), request.workType(),
                    plan.deadline(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "PERMIT_EXECUTING_EXISTS",
                    "同一路段同时刻仅允许一张执行中许可: " + segment.code());
        }
        return new ServiceResult(200,
                new PermitResponse(permitNo, PermitStatus.EXECUTING.name(), plan.deadline()));
    }

    public ServiceResult submitReport(ReportSubmitRequest request) {
        return idempotency.execute("permit.report", request.requestId(), request,
                () -> doSubmitReport(request));
    }

    private ServiceResult doSubmitReport(ReportSubmitRequest request) {
        PermitRow permit = permitRepository.findByPermitNo(request.permitNo())
                .orElseThrow(() -> new BusinessException(404, "PERMIT_NOT_FOUND",
                        "许可不存在: " + request.permitNo()));
        if (permit.status() != PermitStatus.EXECUTING) {
            throw new BusinessException(409, "PERMIT_NOT_EXECUTING",
                    "许可非执行中状态: " + permit.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long reportId = reportRepository.insert(permit.id(), request.beforePhoto(),
                request.afterPhoto(), request.disposalRecord(), now);
        permitRepository.complete(permit.id(), now);
        PlanRow plan = planRepository.findById(permit.planId())
                .orElseThrow(() -> new BusinessException(404, "PLAN_NOT_FOUND",
                        "修剪计划不存在: " + permit.planId()));
        // 恰第7日闭环有效：closedAt <= deadline 为按期
        boolean onTime = !now.isAfter(plan.deadline());
        planRepository.close(plan.id(), now, onTime);
        return new ServiceResult(200,
                new ReportResponse(reportId, permit.permitNo(), plan.id(), onTime));
    }
}
