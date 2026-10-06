package com.example.starter.waste;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处理设施回执服务：回执闭环联单；回执净重与进场净重偏差超过 2% 转异常核查，恰 2% 视为一致。
 */
@Service
public class ReceiptService {

    /** 偏差容忍阈值（含边界），超过该值转异常核查。 */
    static final BigDecimal DEVIATION_THRESHOLD = new BigDecimal("0.02");

    private final ManifestRepository manifestRepository;
    private final ReceiptRepository receiptRepository;
    private final Clock clock;

    public ReceiptService(ManifestRepository manifestRepository, ReceiptRepository receiptRepository,
            Clock businessClock) {
        this.manifestRepository = manifestRepository;
        this.receiptRepository = receiptRepository;
        this.clock = businessClock;
    }

    /**
     * 登记回执并闭环联单。
     */
    @Transactional
    public ReceiptResponse registerReceipt(long manifestId, ReceiptRequest request) {
        String fingerprint = fingerprintOf(manifestId, request);
        var replay = receiptRepository.findReceiptByRequestId(request.requestId());
        if (replay.isPresent()) {
            return resolveReplay(replay.get(), fingerprint);
        }

        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MANIFEST_NOT_FOUND",
                        "联单不存在: " + manifestId));
        if (manifest.status() != ManifestStatus.IN_PROGRESS) {
            throw new ApiException(HttpStatus.CONFLICT, "MANIFEST_NOT_IN_PROGRESS",
                    "联单当前状态不允许回执: " + manifest.status());
        }

        BigDecimal deviation = request.receivedNetKg().subtract(manifest.netKg()).abs()
                .divide(manifest.netKg(), 6, RoundingMode.HALF_UP);
        boolean consistent = deviation.compareTo(DEVIATION_THRESHOLD) <= 0;
        LocalDateTime now = LocalDateTime.now(clock);

        try {
            long receiptId = receiptRepository.insertReceipt(
                    manifestId, request.requestId(), fingerprint, request.receivedNetKg(), deviation, now);
            Long exceptionId = null;
            if (consistent) {
                manifestRepository.closeStatus(manifestId, ManifestStatus.CLOSED);
            } else {
                manifestRepository.closeStatus(manifestId, ManifestStatus.EXCEPTION);
                exceptionId = receiptRepository.insertException(manifestId, now);
            }
            return new ReceiptResponse(receiptId, manifestId, request.requestId(), request.receivedNetKg(),
                    deviation, consistent ? ManifestStatus.CLOSED.name() : ManifestStatus.EXCEPTION.name(),
                    exceptionId, now);
        } catch (DuplicateKeyException e) {
            return receiptRepository.findReceiptByRequestId(request.requestId())
                    .map(existing -> resolveReplay(existing, fingerprint))
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RECEIPT_CONFLICT",
                            "联单已存在回执，不能重复回执"));
        }
    }

    /**
     * 幂等回放：同键同参返回首次结果，同键异参 409。
     */
    private ReceiptResponse resolveReplay(Receipt existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同的业务参数");
        }
        Manifest manifest = manifestRepository.findById(existing.manifestId()).orElseThrow();
        Long exceptionId = receiptRepository.findExceptionByManifestId(existing.manifestId())
                .map(ExceptionCase::id)
                .orElse(null);
        return new ReceiptResponse(existing.id(), existing.manifestId(), existing.requestId(),
                existing.receivedNetKg(), existing.deviationRatio(), manifest.status().name(),
                exceptionId, existing.receivedAt());
    }

    static String fingerprintOf(long manifestId, ReceiptRequest request) {
        return Fingerprints.of(List.of(
                request.requestId(),
                String.valueOf(manifestId),
                request.receivedNetKg().toPlainString()));
    }
}
