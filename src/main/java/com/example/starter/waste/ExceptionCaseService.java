package com.example.starter.waste;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异常核查服务：对偏差超阈值生成的核查单登记结论，结论分设备误差与装载差异两类并留痕。
 */
@Service
public class ExceptionCaseService {

    private final ReceiptRepository receiptRepository;
    private final Clock clock;

    public ExceptionCaseService(ReceiptRepository receiptRepository, Clock businessClock) {
        this.receiptRepository = receiptRepository;
        this.clock = businessClock;
    }

    /**
     * 登记核查结论；按 requestId 幂等，同键同参返回首次结果，同键异参 409。
     */
    @Transactional
    public ExceptionCaseResponse conclude(long exceptionId, ConclusionRequest request) {
        String fingerprint = fingerprintOf(exceptionId, request);

        ExceptionCase current = receiptRepository.findExceptionById(exceptionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXCEPTION_NOT_FOUND",
                        "异常核查单不存在: " + exceptionId));

        if (ExceptionCase.STATUS_RESOLVED.equals(current.status())) {
            if (fingerprint.equals(current.conclusionFingerprint())) {
                return toResponse(current);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同的业务参数或核查单已有结论");
        }

        receiptRepository.findExceptionByConclusionRequestId(request.requestId())
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                            "相同 requestId 已用于其他核查结论");
                });

        LocalDateTime now = LocalDateTime.now(clock);
        try {
            receiptRepository.resolveException(exceptionId, request.conclusionType(), request.note(),
                    request.requestId(), fingerprint, now);
        } catch (DuplicateKeyException e) {
            ExceptionCase raced = receiptRepository.findExceptionById(exceptionId).orElseThrow();
            if (fingerprint.equals(raced.conclusionFingerprint())) {
                return toResponse(raced);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同的业务参数");
        }
        return toResponse(receiptRepository.findExceptionById(exceptionId).orElseThrow());
    }

    /**
     * 查询核查单。
     */
    @Transactional(readOnly = true)
    public ExceptionCaseResponse get(long exceptionId) {
        return receiptRepository.findExceptionById(exceptionId)
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXCEPTION_NOT_FOUND",
                        "异常核查单不存在: " + exceptionId));
    }

    private ExceptionCaseResponse toResponse(ExceptionCase c) {
        return new ExceptionCaseResponse(c.id(), c.manifestId(), c.status(),
                c.conclusionType(), c.conclusionNote(), c.createdAt(), c.resolvedAt());
    }

    static String fingerprintOf(long exceptionId, ConclusionRequest request) {
        return Fingerprints.of(List.of(
                request.requestId(),
                String.valueOf(exceptionId),
                request.conclusionType().name(),
                request.note()));
    }
}
