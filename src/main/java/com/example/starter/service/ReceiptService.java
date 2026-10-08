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
 * 受控联单（改投过、去向版本 > 1）的回执必须提供设施标识与去向版本，
 * 恰为当前版本且设施匹配才入账；旧版本或错设施回执仅存迟到证据，不闭环、不计入接收量。
 */
@Service
public class ReceiptService {

    /** 偏差阈值百分比：恰等于该值视为一致，超过转异常。 */
    static final BigDecimal DEVIATION_THRESHOLD_PCT = new BigDecimal("2");

    /** 迟到回执结果标记：仅存证据，不闭环。 */
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
     * @param result   MATCH 一致闭环 / EXCEPTION 转异常核查 / LATE_EVIDENCE 迟到回执仅存证据
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
            String facilityCode,
            Integer destinationVersion,
            boolean replayed,
            Instant receivedAt) {
    }

    /**
     * 旧式回执入口（不提供设施与去向版本），仅兼容未改投的联单。
     */
    @Transactional
    public ReceiptResult registerReceipt(long manifestId, String requestId, BigDecimal receiptNetKg) {
        return registerReceipt(manifestId, requestId, receiptNetKg, null, null);
    }

    /**
     * 登记处理设施回执。
     *
     * @param facilityCode      受控回执的设施编码；旧式回执为 null（仅允许未改投联单）
     * @param destinationVersion 受控回执的去向版本；旧式回执为 null
     */
    @Transactional
    public ReceiptResult registerReceipt(long manifestId, String requestId, BigDecimal receiptNetKg,
                                         String facilityCode, Integer destinationVersion) {        if (receiptNetKg == null || receiptNetKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("RECEIPT_NET_INVALID", "回执净重必须为正数");
        }
        if ((facilityCode == null) != (destinationVersion == null)) {
            throw BizException.badRequest("DESTINATION_CONTROL_INCOMPLETE",
                    "受控回执须同时提供设施标识与去向版本");
        }
        String fingerprint = fingerprintOf(requestId, manifestId, receiptNetKg, facilityCode, destinationVersion);

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

        Long claimedFacilityId = null;
        if (facilityCode != null) {
            var facility = facilityRepository.findByCode(facilityCode)
                    .orElseThrow(() -> BizException.badRequest("FACILITY_NOT_IN_CATALOG",
                            "回执设施不在处理设施目录内：" + facilityCode));
            claimedFacilityId = facility.id();
            boolean matchesCurrent = destinationVersion == manifest.destinationVersion()
                    && facility.id() == manifest.facilityId();
            if (!matchesCurrent) {
                return recordLateEvidence(manifest, requestId, fingerprint, receiptNetKg,
                        facility.id(), destinationVersion, facilityCode);
            }
        } else if (manifest.destinationVersion() > 1) {
            // 受控联单：旧回执入口不得绕过去向校验
            throw BizException.badRequest("DESTINATION_CONTROL_REQUIRED",
                    "联单已改投，回执必须提供设施标识与去向版本：" + manifestId);
        }

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
                    requestId, fingerprint, manifestId, receiptNetKg, deviationPct, result,
                    claimedFacilityId, destinationVersion, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findReceiptByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("RECEIPT_ALREADY_EXISTS", "该联单已存在回执：" + manifestId);
        }
        // 状态与去向版本双守卫：并发改投先成则此处命中 0 行，回执整体回滚
        int updated = manifestRepository.transitionStatusGuarded(
                manifestId, ManifestStatus.IN_TRANSIT.name(), toStatus, manifest.destinationVersion());
        if (updated != 1) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "联单状态或去向版本已变化，回执失败：" + manifestId);
        }

        return new ReceiptResult(receiptId, requestId, manifestId, entryNet, receiptNetKg,
                deviationPct, result, facilityCode, destinationVersion, false, now);
    }

    /**
     * 迟到/错去向回执：只写证据表，不闭环、不增加接收量。
     */
    private ReceiptResult recordLateEvidence(ManifestRepository.ManifestRow manifest, String requestId,
                                             String fingerprint, BigDecimal receiptNetKg, long facilityId,
                                             int destinationVersion, String facilityCode) {
        Instant now = clock.instant();
        long lateId;
        try {
            lateId = manifestRepository.insertLateReceipt(requestId, fingerprint, manifest.id(),
                    facilityId, destinationVersion, receiptNetKg, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findLateReceiptByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayLateOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("RECEIPT_ALREADY_EXISTS", "该联单已存在回执：" + manifest.id());
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
        String facilityCode = null;
        if (row.facilityId() != null) {
            facilityCode = facilityRepository.findById(row.facilityId())
                    .map(FacilityRepository.FacilityRow::code)
                    .orElse(null);
        }
        return new ReceiptResult(row.id(), row.requestId(), row.manifestId(), manifest.netKg(), row.netKg(),
                row.deviationPct(), row.result(), facilityCode, row.destinationVersion(), true, row.receivedAt());
    }

    private ReceiptResult replayLateOrConflict(ManifestRepository.LateReceiptRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        var manifest = manifestRepository.findManifestById(row.manifestId())
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + row.manifestId()));
        String facilityCode = facilityRepository.findById(row.facilityId())
                .map(FacilityRepository.FacilityRow::code)
                .orElse(null);
        return new ReceiptResult(row.id(), row.requestId(), row.manifestId(), manifest.netKg(), row.netKg(),
                null, RESULT_LATE_EVIDENCE, facilityCode, row.destinationVersion(), true, row.receivedAt());
    }

    private static String fingerprintOf(String requestId, long manifestId, BigDecimal receiptNetKg,
                                        String facilityCode, Integer destinationVersion) {
        return Fingerprints.of(
                requestId,
                String.valueOf(manifestId),
                receiptNetKg.stripTrailingZeros().toPlainString(),
                facilityCode == null ? "" : facilityCode,
                destinationVersion == null ? "" : String.valueOf(destinationVersion));
    }
}
