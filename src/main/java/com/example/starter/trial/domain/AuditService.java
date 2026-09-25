package com.example.starter.trial.domain;

import com.example.starter.trial.api.dto.AuditEventResponse;
import com.example.starter.trial.domain.UnblindingRepository.AuditView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 揭盲审计查询：按事件发生顺序（id 递增）返回。
 */
@Service
public class AuditService {

    private final EnrollmentRepository enrollmentRepository;
    private final UnblindingRepository unblindingRepository;

    public AuditService(EnrollmentRepository enrollmentRepository,
                        UnblindingRepository unblindingRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.unblindingRepository = unblindingRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> listAudit(String screeningNo) {
        String trialCode = TrialConstants.DEFAULT_TRIAL;
        enrollmentRepository.findByScreeningNo(trialCode, screeningNo)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo));
        return unblindingRepository.listAudit(trialCode, screeningNo).stream()
                .map(view -> toResponse(view, screeningNo))
                .toList();
    }

    private AuditEventResponse toResponse(AuditView view, String screeningNo) {
        return new AuditEventResponse(view.id(), TrialConstants.DEFAULT_TRIAL, screeningNo,
                view.eventType(), view.eventTimeUtc(), view.requestId(), view.operator(),
                view.detail());
    }
}
