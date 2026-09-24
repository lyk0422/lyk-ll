package com.example.starter.unblinding;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.audit.AuditRepository;
import com.example.starter.common.ApiException;
import com.example.starter.enrollment.Enrollment;
import com.example.starter.enrollment.EnrollmentRepository;
import com.example.starter.enrollment.Trial;

/**
 * 应急揭盲服务：两名不同授权人在同一事务内先后确认方可生效；揭盲为终态，重复请求返回首次结果。
 */
@Service
public class UnblindingService {

    private final UnblindingRepository repository;
    private final EnrollmentRepository enrollmentRepository;
    private final AuditRepository auditRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public UnblindingService(UnblindingRepository repository, EnrollmentRepository enrollmentRepository,
            AuditRepository auditRepository, TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        this.enrollmentRepository = enrollmentRepository;
        this.auditRepository = auditRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 应急揭盲。单人确认（两名确认人相同或缺失）不生效；已揭盲受试者重复请求返回首次结果。
     */
    public UnblindResponse unblind(String trialCode, UnblindRequest request) {
        validateApprovers(request);
        try {
            return transactionTemplate.execute(status -> doUnblind(trialCode, request));
        } catch (DuplicateKeyException e) {
            // 并发重复揭盲：唯一约束兜底，重读首次结果
            Trial trial = requireTrial(trialCode);
            UnblindingRecord existing = repository.findByScreening(trial.id(), request.screeningNo());
            if (existing != null) {
                return toResponse(existing, true);
            }
            throw e;
        }
    }

    private UnblindResponse doUnblind(String trialCode, UnblindRequest request) {
        Trial trial = requireTrial(trialCode);
        if (!repository.isAuthorized(trial.id(), request.approver1())) {
            throw ApiException.unprocessable("APPROVER_NOT_AUTHORIZED",
                    "确认人未授权: " + request.approver1());
        }
        if (!repository.isAuthorized(trial.id(), request.approver2())) {
            throw ApiException.unprocessable("APPROVER_NOT_AUTHORIZED",
                    "确认人未授权: " + request.approver2());
        }
        Enrollment enrollment = enrollmentRepository.findByScreening(trial.id(), request.screeningNo());
        if (enrollment == null) {
            throw ApiException.notFound("SUBJECT_NOT_FOUND", "受试者未入组: " + request.screeningNo());
        }

        UnblindingRecord existing = repository.findByScreening(trial.id(), request.screeningNo());
        if (existing != null) {
            // 揭盲为终态：重复请求返回首次结果，不新增事件
            return toResponse(existing, true);
        }

        Instant now = Instant.now(clock);
        // 两名授权人在同一事务内先后确认
        auditRepository.append(trial.id(), request.screeningNo(), "UNBLIND_CONFIRM_1",
                request.approver1(), "第一确认人确认揭盲", now);
        auditRepository.append(trial.id(), request.screeningNo(), "UNBLIND_CONFIRM_2",
                request.approver2(), "第二确认人确认揭盲", now);

        UnblindingRecord record = new UnblindingRecord(0L, trial.id(), request.screeningNo(),
                request.approver1(), request.approver2(), request.reason(),
                enrollment.actualArm(), now);
        repository.insert(record);
        enrollmentRepository.updateStatus(trial.id(), request.screeningNo(), "UNBLINDED");
        repository.excludeSubsequentVisits(trial.id(), request.screeningNo(), now);
        auditRepository.append(trial.id(), request.screeningNo(), "UNBLINDED", null,
                "revealedArm=" + record.revealedArm(), now);
        return toResponse(record, false);
    }

    /**
     * 单人确认不生效：两名确认人必须存在且不同。
     */
    private void validateApprovers(UnblindRequest request) {
        if (request.approver1().equals(request.approver2())) {
            throw ApiException.unprocessable("SINGLE_APPROVER_NOT_ALLOWED",
                    "应急揭盲须两名不同授权人先后确认，单人确认不生效");
        }
    }

    private UnblindResponse toResponse(UnblindingRecord record, boolean repeated) {
        return new UnblindResponse(record.screeningNo(), String.valueOf(record.revealedArm()),
                record.approver1(), record.approver2(), record.reason(), record.effectiveAt(), repeated);
    }

    private Trial requireTrial(String trialCode) {
        Trial trial = enrollmentRepository.findTrialByCode(trialCode);
        if (trial == null) {
            throw ApiException.notFound("TRIAL_NOT_FOUND", "试验不存在: " + trialCode);
        }
        return trial;
    }
}
