package com.example.starter.gas.service;

import com.example.starter.gas.ApiException;
import com.example.starter.gas.domain.QualitySampleRow;
import com.example.starter.gas.domain.VerificationRow;
import com.example.starter.gas.domain.VoucherRow;
import com.example.starter.gas.dto.DifferenceSignOffRequest;
import com.example.starter.gas.dto.VerificationResponse;
import com.example.starter.gas.dto.VerificationSampleRequest;
import com.example.starter.gas.dto.VerificationSubmitRequest;
import com.example.starter.gas.repo.IdempotencyRepository;
import com.example.starter.gas.repo.QualityRepository;
import com.example.starter.gas.repo.ReferenceDataRepository;
import com.example.starter.gas.repo.VoucherRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 气质组分核验服务：采样入账、组分勾稽、核验版本、差异签认与确认暂停。
 *
 * <p>核验提交按 requestId 幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409。差异标记、暂停状态与版本记录在同一事务内提交。</p>
 */
@Service
public class QualityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(QualityVerificationService.class);

    private static final String TYPE_VERIFICATION = "VERIFICATION";
    private static final String TYPE_SIGNOFF = "SIGNOFF";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReferenceDataRepository referenceDataRepository;
    private final VoucherRepository voucherRepository;
    private final QualityRepository qualityRepository;
    private final IdempotencyRepository idempotencyRepository;

    public QualityVerificationService(Clock clock,
                                      ObjectMapper objectMapper,
                                      ReferenceDataRepository referenceDataRepository,
                                      VoucherRepository voucherRepository,
                                      QualityRepository qualityRepository,
                                      IdempotencyRepository idempotencyRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.referenceDataRepository = referenceDataRepository;
        this.voucherRepository = voucherRepository;
        this.qualityRepository = qualityRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    /**
     * 提交气质组分核验：采样按“站＋时刻”去重入账，组分比例合计恰为 100% 方可入账；
     * 按组分热值表折算、同日采样体积加权得核验热值，与凭证热值求绝对相对偏差；
     * 恰 1% 视为一致，超过 1% 标记气质差异并追加新版本。
     */
    @Transactional
    public VerificationResponse submitVerification(VerificationSubmitRequest request) {
        String fingerprint = Fingerprint.sha256(canonicalVerification(request));
        return withIdempotency(TYPE_VERIFICATION, request.requestId(), fingerprint,
                VerificationResponse.class, () -> doSubmitVerification(request, fingerprint));
    }

    private VerificationResponse doSubmitVerification(VerificationSubmitRequest request,
                                                      String fingerprint) {
        requireStation(request.stationCode());
        VoucherRow voucher = voucherRepository.findOriginalByStationAndDate(
                request.stationCode(), request.gasDate());
        if (voucher == null) {
            throw ApiException.notFound("VOUCHER_NOT_FOUND",
                    "该气量日尚无交接凭证，无法核验: " + request.gasDate());
        }
        if (voucher.weightedHeatingValue().signum() == 0) {
            // 凭证热值为零：相对偏差分母为零，拒绝核验
            throw ApiException.badRequest("VOUCHER_HEATING_VALUE_ZERO",
                    "凭证热值为零，不能作为相对偏差分母，拒绝核验");
        }

        Map<String, BigDecimal> componentHv = new HashMap<>();
        for (VerificationSampleRequest sample : request.samples()) {
            validateSample(request, sample, componentHv);
        }

        // 版本序列占用：同站同日并发核验只允许一个生效版本；
        // 在采样入账前裁决，落败者整体回滚、不产生任何记录
        Integer current = qualityRepository.findCurrentVersion(request.stationCode(), request.gasDate());
        final int version;
        if (current == null) {
            try {
                qualityRepository.insertVersionSeq(request.stationCode(), request.gasDate(), 1);
                version = 1;
            } catch (DuplicateKeyException ex) {
                return resolveVersionRace(request, fingerprint);
            }
        } else {
            version = current + 1;
            if (qualityRepository.casVersionSeq(request.stationCode(), request.gasDate(),
                    current, version) == 0) {
                return resolveVersionRace(request, fingerprint);
            }
        }

        // 采样入账：按“站＋采样时刻”去重，重复采样保留首次记录
        for (VerificationSampleRequest sample : request.samples()) {
            BigDecimal sampleHv = QualityCalculation.sampleHeatingValue(sample.components(), componentHv);
            qualityRepository.insertSample(request.stationCode(), sample.sampleTime(),
                    request.gasDate(), sample.sampleVolume(), sampleHv,
                    canonicalComponents(sample.components()), request.requestId());
        }

        // 同日全部采样按体积加权得到核验热值
        List<QualitySampleRow> daySamples = qualityRepository.findSamplesOfDay(
                request.stationCode(), request.gasDate());
        BigDecimal verifiedHv = QualityCalculation.verifiedHeatingValue(daySamples);
        BigDecimal deviation = QualityCalculation.relativeDeviation(
                verifiedHv, voucher.weightedHeatingValue());
        boolean difference = QualityCalculation.isDifference(deviation);

        Instant now = clock.instant();
        String verificationNo = "Q-" + request.stationCode() + "-" + request.gasDate() + "-v" + version;
        VerificationRow row = new VerificationRow(null, verificationNo, request.requestId(),
                request.stationCode(), request.gasDate(), voucher.voucherNo(), version,
                daySamples.size(), verifiedHv, voucher.weightedHeatingValue(), deviation,
                difference, difference ? VerificationRow.STATUS_DIFFERENT_OPEN
                        : VerificationRow.STATUS_CONSISTENT,
                null, null, null, now);
        try {
            // 同站同日同版本数据库级唯一（兜底，正常由版本序列先行裁决）
            qualityRepository.insertVerification(row);
        } catch (DuplicateKeyException ex) {
            return resolveVersionRace(request, fingerprint);
        }

        // 气质差异且关联凭证尚未生效：同一事务内暂停双方确认（已生效/已封账凭证不暂停、不改写）
        if (difference && voucher.effectiveDate() == null) {
            qualityRepository.ensurePause(voucher.voucherNo(), verificationNo, now);
        }
        VerificationRow saved = qualityRepository.findVerificationByNo(verificationNo);
        return toResponse(saved, qualityRepository.isConfirmPaused(voucher.voucherNo()));
    }

    /**
     * 双方对气质差异签认：双方签认齐备后解除关联凭证的确认暂停；
     * 签认记录、结论状态与暂停解除在同一事务内提交。
     */
    @Transactional
    public VerificationResponse signOffDifference(String verificationNo, DifferenceSignOffRequest request) {
        String fingerprint = Fingerprint.sha256(
                canonical(TYPE_SIGNOFF, request.requestId(), verificationNo, request.party()));
        return withIdempotency(TYPE_SIGNOFF, request.requestId(), fingerprint,
                VerificationResponse.class, () -> doSignOff(verificationNo, request));
    }

    private VerificationResponse doSignOff(String verificationNo, DifferenceSignOffRequest request) {
        VerificationRow verification = requireVerification(verificationNo);
        if (VerificationRow.STATUS_CONSISTENT.equals(verification.status())) {
            throw ApiException.badRequest("VERIFICATION_CONSISTENT",
                    "核验结论一致，无气质差异需要签认");
        }
        if (qualityRepository.isSignedOffBy(verificationNo, request.party())) {
            // 同方重复签认：返回当前结论，不产生新记录
            return toResponse(qualityRepository.findVerificationByNo(verificationNo),
                    qualityRepository.isConfirmPaused(verification.voucherNo()));
        }

        Instant now = clock.instant();
        qualityRepository.insertSignOff(verificationNo, request.party(), request.requestId(), now);
        qualityRepository.markSignedOff(verificationNo, request.party(), now);

        String otherParty = "A".equals(request.party()) ? "B" : "A";
        if (qualityRepository.isSignedOffBy(verificationNo, otherParty)) {
            // 双方签认齐备：结论置为已解除，并解除关联凭证的确认暂停
            qualityRepository.markResolved(verificationNo, now);
            qualityRepository.releasePause(verification.voucherNo(), now);
        }
        return toResponse(qualityRepository.findVerificationByNo(verificationNo),
                qualityRepository.isConfirmPaused(verification.voucherNo()));
    }

    /** 按核验单号查询（旧版本结论可查）。 */
    public VerificationResponse getVerification(String verificationNo) {
        VerificationRow row = requireVerification(verificationNo);
        return toResponse(row, qualityRepository.isConfirmPaused(row.voucherNo()));
    }

    /** 查询某站某气量日的全部核验版本（版本号升序，旧结论可查）。 */
    public List<VerificationResponse> listVerifications(String stationCode, LocalDate gasDate) {
        requireStation(stationCode);
        return qualityRepository.findVerifications(stationCode, gasDate).stream()
                .map(row -> toResponse(row, qualityRepository.isConfirmPaused(row.voucherNo())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void validateSample(VerificationSubmitRequest request, VerificationSampleRequest sample,
                                Map<String, BigDecimal> componentHvCache) {
        LocalDate sampleDay = LocalDate.ofInstant(sample.sampleTime(), ZoneOffset.UTC);
        if (!sampleDay.equals(request.gasDate())) {
            throw ApiException.badRequest("SAMPLE_DAY_MISMATCH",
                    "采样时刻按 UTC 归属气量日 " + sampleDay + "，与核验气量日 "
                            + request.gasDate() + " 不一致");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : sample.components().entrySet()) {
            BigDecimal ratio = entry.getValue();
            if (ratio == null || ratio.signum() < 0) {
                throw ApiException.badRequest("COMPONENT_RATIO_INVALID",
                        "组分比例不能为空或负数: " + entry.getKey());
            }
            if (!componentHvCache.containsKey(entry.getKey())) {
                BigDecimal hv = qualityRepository.findComponentHeatingValue(entry.getKey());
                if (hv == null) {
                    throw ApiException.badRequest("COMPONENT_UNKNOWN",
                            "组分热值表中不存在该组分: " + entry.getKey());
                }
                componentHvCache.put(entry.getKey(), hv);
            }
            total = total.add(ratio);
        }
        if (total.compareTo(QualityCalculation.RATIO_TOTAL) != 0) {
            // 一组比例相加须恰为 100%，不等则拒绝入账
            throw ApiException.badRequest("COMPONENT_RATIO_NOT_100",
                    "一组组分比例合计须恰为 100%，实际为 " + total.stripTrailingZeros().toPlainString());
        }
    }

    /**
     * 版本竞态裁决：同键同参返回先提交者的首次结果；同键异参 409；
     * 其余为同站同日并发核验冲突，仅允许一个生效版本。
     */
    private VerificationResponse resolveVersionRace(VerificationSubmitRequest request,
                                                    String fingerprint) {
        String racedFingerprint = idempotencyRepository.findFingerprint(request.requestId());
        if (racedFingerprint != null && racedFingerprint.equals(fingerprint)) {
            return readSnapshot(request.requestId(), VerificationResponse.class);
        }
        if (racedFingerprint != null) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同的业务参数");
        }
        throw ApiException.conflict("VERIFICATION_VERSION_CONFLICT",
                "同站同日并发核验仅允许一个生效版本，请刷新后重试");
    }

    private void requireStation(String stationCode) {
        if (!referenceDataRepository.stationExists(stationCode)) {
            throw ApiException.notFound("STATION_NOT_FOUND", "交接站不存在: " + stationCode);
        }
    }

    private VerificationRow requireVerification(String verificationNo) {
        VerificationRow row = qualityRepository.findVerificationByNo(verificationNo);
        if (row == null) {
            throw ApiException.notFound("VERIFICATION_NOT_FOUND", "核验单不存在: " + verificationNo);
        }
        return row;
    }

    /**
     * 幂等包装：同键同参返回首次响应快照；同键异参 409；并发同键落败者重查裁决。
     */
    private <T> T withIdempotency(String requestType, String requestId, String fingerprint,
                                  Class<T> responseType, java.util.function.Supplier<T> action) {
        String existingFingerprint = idempotencyRepository.findFingerprint(requestId);
        if (existingFingerprint != null) {
            if (!existingFingerprint.equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 提交了不同的业务参数");
            }
            return readSnapshot(requestId, responseType);
        }

        T result = action.get();
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应快照序列化失败", ex);
        }
        try {
            idempotencyRepository.insert(requestId, requestType, fingerprint, snapshot);
        } catch (DuplicateKeyException ex) {
            // 并发同键：以先提交者的指纹/快照为准
            String racedFingerprint = idempotencyRepository.findFingerprint(requestId);
            if (racedFingerprint == null) {
                throw ApiException.conflict("IDEMPOTENCY_RACE", "幂等并发冲突，请稍后重试");
            }
            if (!racedFingerprint.equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 提交了不同的业务参数");
            }
            return readSnapshot(requestId, responseType);
        }
        return result;
    }

    private <T> T readSnapshot(String requestId, Class<T> responseType) {
        String json = idempotencyRepository.findResponse(requestId);
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception ex) {
            log.warn("幂等响应快照反序列化失败 requestId={}", requestId, ex);
            throw ApiException.badRequest("IDEMPOTENCY_SNAPSHOT_INVALID", "首次响应快照不可读");
        }
    }

    /** 核验指纹规范化串：覆盖 requestId、站、气量日与全部采样业务字段。 */
    private static String canonicalVerification(VerificationSubmitRequest request) {
        StringBuilder builder = new StringBuilder()
                .append(TYPE_VERIFICATION).append('|')
                .append(request.requestId()).append('|')
                .append(request.stationCode()).append('|')
                .append(request.gasDate());
        request.samples().stream()
                .sorted(java.util.Comparator.comparing(VerificationSampleRequest::sampleTime))
                .forEach(sample -> builder.append('|')
                        .append(sample.sampleTime()).append(',')
                        .append(sample.sampleVolume().stripTrailingZeros().toPlainString()).append(',')
                        .append(canonicalComponents(sample.components())));
        return builder.toString();
    }

    /** 组分比例规范化串：按组分编码排序，保证同参同指纹。 */
    private static String canonicalComponents(Map<String, BigDecimal> components) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(components).forEach((code, ratio) -> builder.append(code)
                .append('=').append(ratio.stripTrailingZeros().toPlainString()).append(';'));
        return builder.toString();
    }

    private static String canonical(Object... parts) {
        return String.join("|", java.util.Arrays.stream(parts).map(String::valueOf).toList());
    }

    static VerificationResponse toResponse(VerificationRow row, boolean confirmPaused) {
        return new VerificationResponse(row.verificationNo(), row.requestId(), row.stationCode(),
                row.gasDate(), row.voucherNo(), row.version(), row.sampleCount(),
                row.verifiedHeatingValue(), row.voucherHeatingValue(), row.relativeDeviation(),
                row.differenceFlag(), row.status(), confirmPaused,
                row.signOffAAt() != null, row.signOffBAt() != null,
                row.resolvedAt(), row.createdAt());
    }
}
