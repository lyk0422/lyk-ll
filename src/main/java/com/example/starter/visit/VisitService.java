package com.example.starter.visit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiException;
import com.example.starter.enrollment.Enrollment;
import com.example.starter.enrollment.EnrollmentRepository;
import com.example.starter.enrollment.Trial;

/**
 * 访视数据服务：已揭盲受试者的后续访视数据自动标记为排除出主要分析集。
 */
@Service
public class VisitService {

    private final VisitRepository repository;
    private final EnrollmentRepository enrollmentRepository;
    private final Clock clock;

    public VisitService(VisitRepository repository, EnrollmentRepository enrollmentRepository, Clock clock) {
        this.repository = repository;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
    }

    @Transactional
    public VisitView addVisit(String trialCode, VisitRequest request) {
        Trial trial = requireTrial(trialCode);
        Enrollment enrollment = enrollmentRepository.findByScreening(trial.id(), request.screeningNo());
        if (enrollment == null) {
            throw ApiException.notFound("SUBJECT_NOT_FOUND", "受试者未入组: " + request.screeningNo());
        }
        boolean excluded = enrollment.isUnblinded();
        VisitView visit = new VisitView(0L, request.screeningNo(), request.visitTime(),
                request.note(), excluded);
        repository.insert(trial.id(), visit, Instant.now(clock));
        return visit;
    }

    @Transactional(readOnly = true)
    public List<VisitView> listVisits(String trialCode, String screeningNo) {
        Trial trial = requireTrial(trialCode);
        return repository.listByScreening(trial.id(), screeningNo);
    }

    private Trial requireTrial(String trialCode) {
        Trial trial = enrollmentRepository.findTrialByCode(trialCode);
        if (trial == null) {
            throw ApiException.notFound("TRIAL_NOT_FOUND", "试验不存在: " + trialCode);
        }
        return trial;
    }
}
