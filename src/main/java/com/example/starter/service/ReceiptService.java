package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ManifestStatus;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 处理设施回执闭环：回执净重与进场净重偏差超过 2% 转异常核查，恰 2% 视为一致。
 */
@Service
public class ReceiptService {

    /** 偏差阈值百分比：恰等于该值视为一致，超过转异常。 */
    static final BigDecimal DEVIATION_THRESHOLD_PCT = new BigDecimal("2");

    private final ManifestRepository manifestRepository;
    private final Clock clock;

    public ReceiptService(ManifestRepository manifestRepository, Clock clock) {
        this.manifestRepository = manifestRepository;
        this.clock = clock;
    }

    /**
     * 回执结果。
     *
     * @param result   MATCH 一致闭环 / EXCEPTION 转异常核查
     * @param replayed 是否为幂等重放
     */
    public record ReceiptResult(
            long receiptId,
            String requestId,
            long manifestId,
            BigDecimal manifestNetKg,
            BigDecimal receiptNetKg,
            BigDecimal deviationPct,
            String result,
            boolean replayed,
            Instant receivedAt) {
    }

    @Transactional
    public ReceiptResult registerReceipt(long manifestId, String requestId, BigDecimal receiptNetKg) {
        if (receiptNetKg == null || receiptNetKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("RECEIPT_NET_INVALID", "回执净重必须为正数");
        }
        String fingerprint = Fingerprints.of(
                requestId, String.valueOf(manifestId), receiptNetKg.stripTrailingZeros().toPlainString());

        var existing = manifestRepository.findReceiptByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        var manifest = manifestRepository.findManifestById(manifestId)
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + manifestId));
        if (!ManifestStatus.IN_TRANSIT.name().equals(manifest.status())) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "联单当前状态不允许回执：" + manifest.status());
        }

        BigDecimal entryNet = manifest.netKg();
        BigDecimal deviationPct = receiptNetKg.subtract(entryNet).abs()
                .movePointRight(2)
                .divide(entryNet, 6, RoundingMode.HALF_UP);
        // 用精确乘法判定阈值，避免舍入影响“恰 2% 视为一致”的边界
        boolean exceeds = receiptNetKg.subtract(entryNet).abs().multiply(new BigDecimal("100"))
                .compareTo(entryNet.multiply(DEVIATION_THRESHOLD_PCT)) > 0;
        String result = exceeds ? "EXCEPTION" : "MATCH";
        String toStatus = exceeds ? ManifestStatus.EXCEPTION.name() : ManifestStatus.RECEIVED.name();

        Instant now = clock.instant();
        long receiptId;
        try {
            receiptId = manifestRepository.insertReceipt(
                    requestId, fingerprint, manifestId, receiptNetKg, deviationPct, result, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findReceiptByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("RECEIPT_ALREADY_EXISTS", "该联单已存在回执：" + manifestId);
        }
        int updated = manifestRepository.transitionStatus(manifestId, ManifestStatus.IN_TRANSIT.name(), toStatus);
        if (updated != 1) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT", "联单状态已变化，回执失败：" + manifestId);
        }

        return new ReceiptResult(receiptId, requestId, manifestId, entryNet, receiptNetKg,
                deviationPct, result, false, now);
    }

    private ReceiptResult replayOrConflict(ManifestRepository.ReceiptRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        var manifest = manifestRepository.findManifestById(row.manifestId())
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + row.manifestId()));
        return new ReceiptResult(row.id(), row.requestId(), row.manifestId(), manifest.netKg(), row.netKg(),
                row.deviationPct(), row.result(), true, row.receivedAt());
    }
}
