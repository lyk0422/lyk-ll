package com.example.starter.trial.domain;

import com.example.starter.trial.api.dto.VisitRequest;
import com.example.starter.trial.api.dto.VisitResponse;
import com.example.starter.trial.domain.EnrollmentRepository.EnrollmentRow;
import com.example.starter.trial.domain.VisitRepository.VisitRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 访视登记与查询：揭盲生效日（UTC 日界）及之后的访视一律排除出主要分析集，且不可回退。
 */
@Service
public class VisitService {

    private final EnrollmentRepository enrollmentRepository;
    private final VisitRepository visitRepository;

    public VisitService(EnrollmentRepository enrollmentRepository, VisitRepository visitRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.visitRepository = visitRepository;
    }

    @Transactional
    public VisitResponse recordVisit(String screeningNo, VisitRequest request) {
        String trialCode = TrialConstants.DEFAULT_TRIAL;
        EnrollmentRow subject = enrollmentRepository.findByScreeningNo(trialCode, screeningNo)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo));

        boolean excluded = false;
        if (TrialConstants.STATUS_UNBLINDED.equals(subject.status())
                && subject.unblindedAtUtc() != null) {
            LocalDate visitDay = LocalDate.ofInstant(request.visitTimeUtc(), ZoneOffset.UTC);
            LocalDate unblindDay = LocalDate.ofInstant(subject.unblindedAtUtc(), ZoneOffset.UTC);
            excluded = !visitDay.isBefore(unblindDay);
        }

        try {
            visitRepository.insert(trialCode, screeningNo, request.visitCode(),
                    request.visitTimeUtc(), excluded);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("访视已存在: " + request.visitCode());
        }
        return new VisitResponse(trialCode, screeningNo, request.visitCode(),
                request.visitTimeUtc(), excluded);
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> listVisits(String screeningNo) {
        String trialCode = TrialConstants.DEFAULT_TRIAL;
        enrollmentRepository.findByScreeningNo(trialCode, screeningNo)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo));
        return visitRepository.list(trialCode, screeningNo).stream()
                .map(this::toResponse)
                .toList();
    }

    private VisitResponse toResponse(VisitRow row) {
        return new VisitResponse(row.trialCode(), row.screeningNo(), row.visitCode(),
                row.visitTimeUtc(), row.excludedFromMainAnalysis());
    }
}
