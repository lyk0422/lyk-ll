package com.example.starter.service;

import java.time.LocalDate;

import com.example.starter.domain.QualificationStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.repo.QualificationRepository;
import com.example.starter.time.TimeProvider;
import com.example.starter.web.dto.QualificationDtos.QualificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资格续期服务：仅受理到期前 30 日内（含恰第 30 日）的申请，更早或逾期拒绝。
 */
@Service
public class RenewalService {

    /** 续期受理窗口：到期前 30 日内（含恰第 30 日）。 */
    public static final int RENEWAL_WINDOW_DAYS = 30;

    private final QualificationRepository qualificationRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;

    public RenewalService(QualificationRepository qualificationRepository,
                          IdempotencyService idempotencyService,
                          TimeProvider timeProvider) {
        this.qualificationRepository = qualificationRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
    }

    /**
     * 申请资格续期（幂等）。新资格自原到期日次日起生效，有效期 1 年。
     */
    @Transactional
    public QualificationResponse renew(long qualificationId, String requestId) {
        String fingerprint = idempotencyService.fingerprint(requestId, qualificationId);
        return idempotencyService.execute("POST /api/qualifications/{id}/renewals", requestId,
                fingerprint, QualificationResponse.class, () -> doRenew(qualificationId, requestId));
    }

    private QualificationResponse doRenew(long qualificationId, String requestId) {
        QualificationRepository.QualificationRow current = qualificationRepository
                .findById(qualificationId)
                .orElseThrow(() -> BusinessException.notFound("资格不存在: " + qualificationId));
        if (!QualificationStatus.ACTIVE.name().equals(current.status())) {
            throw BusinessException.conflict("QUALIFICATION_NOT_ACTIVE",
                    "仅有效资格可续期，当前状态: " + current.status());
        }
        LocalDate today = timeProvider.today();
        LocalDate windowStart = current.validTo().minusDays(RENEWAL_WINDOW_DAYS);
        if (today.isBefore(windowStart) || today.isAfter(current.validTo())) {
            throw BusinessException.unprocessable("RENEWAL_WINDOW_VIOLATION",
                    "续期须在到期前 " + RENEWAL_WINDOW_DAYS + " 日内申请，受理窗口 ["
                            + windowStart + ", " + current.validTo() + "]，当前日期 " + today);
        }
        LocalDate newValidFrom = current.validTo().plusDays(1);
        LocalDate newValidTo = newValidFrom.plusYears(1).minusDays(1);
        qualificationRepository.updateStatus(qualificationId, QualificationStatus.RENEWED);
        long newId = qualificationRepository.insert(current.routeId(), newValidFrom, newValidTo,
                "RENEWAL", requestId);
        return new QualificationResponse(newId, current.routeId(), newValidFrom, newValidTo,
                QualificationStatus.ACTIVE.name(), "RENEWAL");
    }
}
