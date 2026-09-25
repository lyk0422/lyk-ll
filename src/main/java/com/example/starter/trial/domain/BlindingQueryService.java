package com.example.starter.trial.domain;

import com.example.starter.trial.api.dto.BlindingResponse;
import com.example.starter.trial.domain.EnrollmentRepository.EnrollmentRow;
import org.springframework.stereotype.Service;

/**
 * 盲态查询：揭盲前 {@code groupCode} 强制为 null，仅返回随机号与盲态编码；
 * 揭盲终态后才返回真实组别。
 */
@Service
public class BlindingQueryService {

    private final EnrollmentRepository enrollmentRepository;

    public BlindingQueryService(EnrollmentRepository enrollmentRepository) {
        this.enrollmentRepository = enrollmentRepository;
    }

    public BlindingResponse getBlinding(String screeningNo) {
        EnrollmentRow row = enrollmentRepository
                .findByScreeningNo(TrialConstants.DEFAULT_TRIAL, screeningNo)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo));
        boolean unblinded = TrialConstants.STATUS_UNBLINDED.equals(row.status());
        return new BlindingResponse(
                row.trialCode(), row.centerCode(), row.screeningNo(), row.randomNo(),
                row.blindedCode(),
                unblinded ? row.groupCode() : null,
                row.status(), row.enrolledAtUtc(), row.unblindedAtUtc());
    }
}
