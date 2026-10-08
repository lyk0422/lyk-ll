package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ManifestStatus;
import com.example.starter.repo.FacilityRepository;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 处理设施回执闭环：回执净重与进场净重偏差超过 2% 转异常核查，恰 2% 视为一致。
 * 受控联单（发生过改投，去向版本 > 1）的回执必须提供设施标识与去向版本，
 * 恰为当前版本且设施匹配才入账；旧版本迟到回执仅存证据，不闭环、不增加接收量。
 */
@Service
public class ReceiptService {

    /** 偏差阈值百分比：恰等于该值视为一致，超过转异常。 */
    static final BigDecimal DEVIATION_THRESHOLD_PCT = new BigDecimal("2");

    /** 回执结论：迟到回执仅存证据。 */
    static final String RESULT_LATE_EVIDENCE = "LATE_EVIDENCE";

    private final ManifestRepository manifestRepository;
    private final FacilityRepository facilityRepository;
    private final Clock clock;

    public ReceiptService(ManifestRepository manifestRepository,
                          FacilityRepository facilityRepository,
                          Clock clock) {
        this.manifestRepository = manifestRepository;
        this.facilityRepository = facilityRepository;
        this.clock = clock;
    }

    /**
     * 回执结果。
     *
     * @param result             MATCH 一致闭环 / EXCEPTION 转异常核查 / LATE_EVIDENCE 迟到回执仅存证据
     * @param facilityCode       回执声明的设施编码，旧式调用为 null
     * @param destinationVersion 回执声明的去向版本，旧式调用为 null
     * @param replayed           是否为幂等重放
     */
    public record ReceiptResult(
            long receiptId,
            String requestId,
            long manifestId,
            BigDecimal manifestNetKg,
            BigDecimal receiptNetKg,
            BigDecimal deviationPct,
            String result,
            String facilityCode,
            Integer destinationVersion,
            boolean replayed,
            Instant receivedAt) {
    }

    /**
     * 旧式调用兼容入口：未改投的历史联单可不提供设施标识与去向版本。
     */
    @Transactional
    public ReceiptResult registerReceipt(long manifestId, String requestId, BigDecimal receiptNetKg) {
        return registerReceipt(manifestId, requestId, receiptNetKg, null, null);
    }

    @Transactional
    public ReceiptResult registerReceipt(long manifestId, String requestId, BigDecimal receiptNetKg,
                                         String facilityCode, Integer destinationVersion) {
        if (receiptNetKg == null || receiptNetKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("RECEIPT_NET_INVALID", "回执净重必须为正数");
        }
        if ((facilityCode == null) != (destinationVersion == null)) {
            throw BizException.badRequest("RECEIPT_DESTINATION_INCOMPLETE",
                    "设施标识与去向版本必须同时提供或同时缺省");
        }
        String fingerprint = Fingerprints.of(
                requestId, String.valueOf(manifestId), receiptNetKg.stripTrailingZeros().toPlainString(),
                facilityCode == null ? "" : facilityCode,
                destinationVersion == null ? "" : String.valueOf(destinationVersion));

        var existing = manifestRepository.findReceiptByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        var existingLate = manifestRepository.findLateReceiptByRequestId(requestId);
        if (existingLate.isPresent()) {
            return replayLateOrConflict(existingLate.get(), fingerprint);
        }

        var manifest = manifestRepository.findManifestById(manifestId)
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + manifestId));
        if (!ManifestStatus.IN_TRANSIT.name().equals(manifest.status())) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "联单当前状态不允许回执：" + manifest.status());
        }

        boolean controlled = manifest.destinationVersion() > 1;
        if (controlled && facilityCode == null) {
            throw BizException.badRequest("RECEIPT_DESTINATION_REQUIRED",
                    "受控联单（已改投）的回执必须提供设施标识和去向版本：" + manifestId);
        }

        Instant now = clock.instant();
        if (facilityCode != null) {
            var currentFacility = facilityRepository.findById(manifest.facilityId())
                    .orElseThrow(() -> new IllegalStateException("联单去向设施缺失：" + manifest.facilityId()));
            boolean booked = destinationVersion == manifest.destinationVersion()
                    && currentFacility.code().equals(facilityCode);
            if (!booked) {
                return recordLateReceipt(manifest, requestId, fingerprint, receiptNetKg,
                        facilityCode, destinationVersion, now);
            }
        }

        return bookReceipt(manifest, requestId, fingerprint, receiptNetKg, facilityCode, destinationVersion, now);
    }

    /**
     * 按原 2% 规则入账：插入回执并同事务推进联单状态；去向版本条件保证与并发改投只能先成一个。
     */
    private ReceiptResult bookReceipt(ManifestRepository.ManifestRow manifest, String requestId,
                                      String fingerprint, BigDecimal receiptNetKg,
                                      String facilityCode, Integer destinationVersion, Instant now) {
        long manifestId = manifest.id();
        BigDecimal entryNet = manifest.netKg();
        BigDecimal deviationPct = receiptNetKg.subtract(entryNet).abs()
                .movePointRight(2)
                .divide(entryNet, 6, RoundingMode.HALF_UP);
        // 用精确乘法判定阈值，避免舍入影响“恰 2% 视为一致”的边界
        boolean exceeds = receiptNetKg.subtract(entryNet).abs().multiply(new BigDecimal("100"))
                .compareTo(entryNet.multiply(DEVIATION_THRESHOLD_PCT)) > 0;
        String result = exceeds ? "EXCEPTION" : "MATCH";
        String toStatus = exceeds ? ManifestStatus.EXCEPTION.name() : ManifestStatus.RECEIVED.name();

        long receiptId;
        try {
            receiptId = manifestRepository.insertReceipt(
                    requestId, fingerprint, manifestId, receiptNetKg, deviationPct, result,
                    facilityCode, destinationVersion, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findReceiptByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("RECEIPT_ALREADY_EXISTS", "该联单已存在回执：" + manifestId);
        }
        int updated = manifestRepository.transitionStatus(manifestId, ManifestStatus.IN_TRANSIT.name(),
                toStatus, manifest.destinationVersion());
        if (updated != 1) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "联单状态或去向版本已变化，回执失败：" + manifestId);
        }

        return new ReceiptResult(receiptId, requestId, manifestId, entryNet, receiptNetKg,
                deviationPct, result, facilityCode, destinationVersion, false, now);
    }

    /**
     * 迟到回执：设施或去向版本与当前不匹配，仅存证据，不闭环、不增加接收量。
     */
    private ReceiptResult recordLateReceipt(ManifestRepository.ManifestRow manifest, String requestId,
                                            String fingerprint, BigDecimal receiptNetKg,
                                            String facilityCode, int destinationVersion, Instant now) {
        long lateId;
        try {
            lateId = manifestRepository.insertLateReceipt(requestId, fingerprint, manifest.id(),
                    facilityCode, destinationVersion, receiptNetKg, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findLateReceiptByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayLateOrConflict(duplicated.get(), fingerprint);
            }
            throw e;
        }
        return new ReceiptResult(lateId, requestId, manifest.id(), manifest.netKg(), receiptNetKg,
                null, RESULT_LATE_EVIDENCE, facilityCode, destinationVersion, false, now);
    }

    private ReceiptResult replayOrConflict(ManifestRepository.ReceiptRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        var manifest = manifestRepository.findManifestById(row.manifestId())
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + row.manifestId()));
        return new ReceiptResult(row.id(), row.requestId(), row.manifestId(), manifest.netKg(), row.netKg(),
                row.deviationPct(), row.result(), row.facilityCode(), row.destinationVersion(),
                true, row.receivedAt());
    }

    private ReceiptResult replayLateOrConflict(ManifestRepository.LateReceiptRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        var manifest = manifestRepository.findManifestById(row.manifestId())
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + row.manifestId()));
        return new ReceiptResult(row.id(), row.requestId(), row.manifestId(), manifest.netKg(), row.netKg(),
                null, RESULT_LATE_EVIDENCE, row.facilityCode(), row.destinationVersion(),
                true, row.receivedAt());
    }
}
