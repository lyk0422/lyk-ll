package com.example.starter.gas.service;

import com.example.starter.gas.ApiException;
import com.example.starter.gas.domain.SampleRow;
import com.example.starter.gas.domain.VerificationRow;
import com.example.starter.gas.domain.VoucherRow;
import com.example.starter.gas.dto.SignOffRequest;
import com.example.starter.gas.dto.SampleRequest;
import com.example.starter.gas.dto.VerificationResponse;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 气质组分核验服务：在交接凭证之外补一层气质核验，不改写凭证的读数、
 * 确认、偏差考核、争议期与封账流程。
 *
 * <p>核验提交按 requestId 幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409。同站同气量日只允许一个生效版本，
 * 修订追加版本，旧结论保留可查；差异标记、暂停状态与版本记录在同一事务内提交。</p>
 */
@Service
public class QualityService {

    private static final Logger log = LoggerFactory.getLogger(QualityService.class);

    private static final String TYPE_VERIFICATION = "QUALITY_VERIFICATION";
    private static final String TYPE_SIGNOFF = "QUALITY_SIGNOFF";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReferenceDataRepository referenceDataRepository;
    private final VoucherRepository voucherRepository;
    private final QualityRepository qualityRepository;
    private final IdempotencyRepository idempotencyRepository;

    public QualityService(Clock clock,
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
     * 提交气质组分核验：采样比例合计恰为 100% 才入账；按组分热值表折算、
     * 按同日采样体积加权得核验热值；以凭证热值为分母计算绝对相对偏差，
     * 恰 1%（含）一致、超过 1% 标记气质差异；同站同日修订追加版本。
     */
    @Transactional
    public VerificationResponse submitVerification(VerificationSubmitRequest request) {
        String fingerprint = Fingerprint.sha256(verificationCanonical(request));
        return withIdempotency(TYPE_VERIFICATION, request.requestId(), fingerprint,
                VerificationResponse.class, () -> doSubmitVerification(request));
    }

    private VerificationResponse doSubmitVerification(VerificationSubmitRequest request) {
        requireStation(request.stationCode());
        VoucherRow voucher = voucherRepository.findByNo(request.voucherNo());
        if (voucher == null) {
            throw ApiException.notFound("VOUCHER_NOT_FOUND", "凭证不存在: " + request.voucherNo());
        }
        if (!voucher.stationCode().equals(request.stationCode())
                || !voucher.gasDate().equals(request.gasDate())) {
            throw ApiException.badRequest("VOUCHER_MISMATCH",
                    "凭证与交接站/气量日不匹配: " + request.voucherNo());
        }
        if (voucher.weightedHeatingValue().signum() == 0) {
            // 凭证热值为零，相对偏差分母无意义：拒绝核验
            throw ApiException.conflict("VOUCHER_HEATING_VALUE_ZERO",
                    "凭证热值为零，不能作为相对偏差分母，拒绝核验");
        }

        Map<String, BigDecimal> heatingValueTable = qualityRepository.findComponentHeatingValues();
        // 同一请求内同站同时刻的重复采样去重（先者为准）
        Map<Instant, SampleRequest> deduped = new LinkedHashMap<>();
        for (SampleRequest sample : request.samples()) {
            deduped.putIfAbsent(sample.sampleTime(), sample);
        }

        // 采样入账：按“站＋采样时刻”全局去重；迟到采样只入采样记录，不改写任何凭证
        List<BigDecimal[]> effectiveSamples = new ArrayList<>();
        BigDecimal totalSampleVolume = BigDecimal.ZERO;
        Instant now = clock.instant();
        String verificationNo = newVerificationNo(request.stationCode(), request.gasDate());
        for (Map.Entry<Instant, SampleRequest> entry : deduped.entrySet()) {
            SampleRequest sample = entry.getValue();
            LocalDate sampleGasDate = LocalDate.ofInstant(sample.sampleTime(), ZoneOffset.UTC);
            if (!sampleGasDate.equals(request.gasDate())) {
                throw ApiException.badRequest("SAMPLE_GAS_DAY_MISMATCH",
                        "采样时刻 " + sample.sampleTime() + " 归属气量日 " + sampleGasDate
                                + "，与核验气量日 " + request.gasDate() + " 不一致");
            }
            validateComposition(sample.components(), heatingValueTable);

            SampleRow existing = qualityRepository.findSampleByStationAndTime(
                    request.stationCode(), sample.sampleTime());
            if (existing != null) {
                // 重复采样按站点与时刻去重：沿用首次入账的体积与折算热值
                effectiveSamples.add(new BigDecimal[]{existing.sampleVolume(),
                        existing.sampleHeatingValue()});
                totalSampleVolume = totalSampleVolume.add(existing.sampleVolume());
                continue;
            }
            BigDecimal sampleHv = QualityCalculation.sampleHeatingValue(
                    sample.components(), heatingValueTable);
            String compositionJson = toJson(new TreeMap<>(sample.components()));
            try {
                qualityRepository.insertSample(request.requestId(), verificationNo,
                        request.stationCode(), sample.sampleTime(), sampleGasDate,
                        sample.sampleVolume(), compositionJson, sampleHv, now);
            } catch (DuplicateKeyException ex) {
                // 并发同站同时刻采样：以先入库者为准
                SampleRow raced = qualityRepository.findSampleByStationAndTime(
                        request.stationCode(), sample.sampleTime());
                if (raced == null) {
                    throw ApiException.conflict("SAMPLE_RACE", "采样并发冲突，请稍后重试");
                }
                effectiveSamples.add(new BigDecimal[]{raced.sampleVolume(),
                        raced.sampleHeatingValue()});
                totalSampleVolume = totalSampleVolume.add(raced.sampleVolume());
                continue;
            }
            effectiveSamples.add(new BigDecimal[]{sample.sampleVolume(), sampleHv});
            totalSampleVolume = totalSampleVolume.add(sample.sampleVolume());
        }

        BigDecimal verifiedHv = QualityCalculation.verifiedHeatingValue(effectiveSamples);
        BigDecimal deviationRate = QualityCalculation.deviationRate(
                verifiedHv, voucher.weightedHeatingValue());
        boolean qualityDiff = QualityCalculation.isQualityDiff(deviationRate);
        // 差异未解决时暂停尚未生效凭证的双方确认；已生效/已封账凭证不受影响、不被改写
        boolean pause = qualityDiff
                && voucher.effectiveDate() == null
                && !VoucherRow.STATUS_SEALED.equals(voucher.status());

        int version = qualityRepository.maxVersion(request.stationCode(), request.gasDate()) + 1;
        // 同站同日只允许一个生效版本：先取当前生效版本（可能为空），再插入新版本行
        VerificationRow active = qualityRepository.findActiveVerification(
                request.stationCode(), request.gasDate());
        VerificationRow row = new VerificationRow(null, verificationNo, request.requestId(),
                request.stationCode(), request.gasDate(), request.voucherNo(), version,
                VerificationRow.STATUS_ACTIVE, totalSampleVolume, verifiedHv,
                voucher.weightedHeatingValue(), deviationRate,
                qualityDiff ? VerificationRow.CONCLUSION_QUALITY_DIFF
                        : VerificationRow.CONCLUSION_CONSISTENT,
                qualityDiff ? VerificationRow.DIFF_OPEN : VerificationRow.DIFF_NONE,
                pause, now);
        try {
            qualityRepository.insertVerification(row);
        } catch (DuplicateKeyException ex) {
            // 并发修订取到重复版本号：同站同日只允许一个生效版本
            throw ApiException.conflict("VERIFICATION_VERSION_CONFLICT",
                    "同站同日并发核验只允许一个生效版本");
        }

        if (active == null) {
            try {
                qualityRepository.occupyStationDay(request.stationCode(), request.gasDate(),
                        verificationNo);
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("VERIFICATION_VERSION_CONFLICT",
                        "同站同日并发核验只允许一个生效版本");
            }
        } else {
            int updated = qualityRepository.replaceActiveVerification(request.stationCode(),
                    request.gasDate(), active.verificationNo(), verificationNo);
            if (updated == 0) {
                throw ApiException.conflict("VERIFICATION_VERSION_CONFLICT",
                        "同站同日并发核验只允许一个生效版本");
            }
            // 旧版本结论保留可查，仅状态置为被取代
            qualityRepository.supersede(active.verificationNo());
        }
        return toResponse(qualityRepository.findVerificationByNo(verificationNo));
    }

    /**
     * 气质差异签认：双方均签认后差异解决、解除关联凭证的确认暂停；
     * 签认、解除在同一事务内提交。
     */
    @Transactional
    public VerificationResponse signOff(String verificationNo, SignOffRequest request) {
        String fingerprint = Fingerprint.sha256(String.join("|", TYPE_SIGNOFF,
                request.requestId(), verificationNo, request.party()));
        return withIdempotency(TYPE_SIGNOFF, request.requestId(), fingerprint,
                VerificationResponse.class, () -> doSignOff(verificationNo, request));
    }

    private VerificationResponse doSignOff(String verificationNo, SignOffRequest request) {
        // 行锁串行化同一核验单的并发签认，保证双方签认齐备时必有一次事务能看到并解除
        VerificationRow verification = qualityRepository.findVerificationByNoForUpdate(verificationNo);
        if (verification == null) {
            throw ApiException.notFound("VERIFICATION_NOT_FOUND", "核验单不存在: " + verificationNo);
        }
        if (!VerificationRow.CONCLUSION_QUALITY_DIFF.equals(verification.conclusion())) {
            throw ApiException.badRequest("NO_QUALITY_DIFF", "该核验单无气质差异，无需签认");
        }
        if (!VerificationRow.STATUS_ACTIVE.equals(verification.status())) {
            throw ApiException.conflict("VERIFICATION_SUPERSEDED",
                    "该核验版本已被修订取代，请对生效版本签认");
        }
        if (VerificationRow.DIFF_RESOLVED.equals(verification.diffStatus())) {
            // 差异已解决：返回当前状态，不重复落库
            return toResponse(verification);
        }

        qualityRepository.insertSignoff(verificationNo, request.party(),
                request.requestId(), clock.instant());
        String otherParty = "A".equals(request.party()) ? "B" : "A";
        if (qualityRepository.isSignedBy(verificationNo, otherParty)) {
            // 双方签认齐备：同一事务内解除差异与暂停
            qualityRepository.markDiffResolved(verificationNo);
        }
        return toResponse(qualityRepository.findVerificationByNo(verificationNo));
    }

    /** 查询核验单（含被取代的旧版本结论）。 */
    public VerificationResponse getVerification(String verificationNo) {
        return toResponse(requireVerification(verificationNo));
    }

    /** 查询同站同气量日的全部核验版本（旧结论可查），按版本号升序。 */
    public List<VerificationResponse> listVerifications(String stationCode, LocalDate gasDate) {
        requireStation(stationCode);
        return qualityRepository.findVersions(stationCode, gasDate).stream()
                .map(QualityService::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void requireStation(String stationCode) {
        if (!referenceDataRepository.stationExists(stationCode)) {
            throw ApiException.notFound("STATION_NOT_FOUND", "交接站不存在: " + stationCode);
        }
    }

    private VerificationRow requireVerification(String verificationNo) {
        VerificationRow verification = qualityRepository.findVerificationByNo(verificationNo);
        if (verification == null) {
            throw ApiException.notFound("VERIFICATION_NOT_FOUND", "核验单不存在: " + verificationNo);
        }
        return verification;
    }

    /** 一组组分比例相加须恰为 100%，且组分编码均须存在于组分热值表。 */
    private static void validateComposition(Map<String, BigDecimal> components,
                                            Map<String, BigDecimal> heatingValueTable) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : components.entrySet()) {
            if (!heatingValueTable.containsKey(entry.getKey())) {
                throw ApiException.badRequest("UNKNOWN_COMPONENT",
                        "组分编码不在组分热值表中: " + entry.getKey());
            }
            sum = sum.add(entry.getValue());
        }
        if (sum.compareTo(QualityCalculation.RATIO_TOTAL) != 0) {
            throw ApiException.badRequest("COMPONENT_RATIO_INVALID",
                    "一组组分比例相加须恰为 100%，实际为 " + sum.stripTrailingZeros().toPlainString());
        }
    }

    private String newVerificationNo(String stationCode, LocalDate gasDate) {
        return "Q-" + stationCode + "-" + gasDate + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF));
    }

    /** 指纹规范化串：覆盖 requestId 与全部业务字段（采样按时刻、组分按编码排序）。 */
    private static String verificationCanonical(VerificationSubmitRequest request) {
        StringBuilder canonical = new StringBuilder(TYPE_VERIFICATION)
                .append('|').append(request.requestId())
                .append('|').append(request.stationCode())
                .append('|').append(request.gasDate())
                .append('|').append(request.voucherNo());
        List<SampleRequest> samples = new ArrayList<>(request.samples());
        samples.sort(java.util.Comparator.comparing(SampleRequest::sampleTime));
        for (SampleRequest sample : samples) {
            canonical.append('|').append(sample.sampleTime())
                    .append('=').append(sample.sampleVolume().stripTrailingZeros().toPlainString())
                    .append('=');
            Map<String, BigDecimal> sorted = new TreeMap<>(sample.components());
            sorted.forEach((code, ratio) -> canonical.append(code).append(':')
                    .append(ratio.stripTrailingZeros().toPlainString()).append(','));
        }
        return canonical.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("组分比例快照序列化失败", ex);
        }
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
        String snapshot = toJson(result);
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

    static VerificationResponse toResponse(VerificationRow row) {
        return new VerificationResponse(row.verificationNo(), row.requestId(), row.stationCode(),
                row.gasDate(), row.voucherNo(), row.version(), row.status(), row.sampleVolume(),
                row.verifiedHeatingValue(), row.voucherHeatingValue(), row.deviationRate(),
                row.conclusion(), row.diffStatus(), row.confirmationPaused(), row.createdAt());
    }
}
