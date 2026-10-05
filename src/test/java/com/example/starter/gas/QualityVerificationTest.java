package com.example.starter.gas;

import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.SampleRequest;
import com.example.starter.gas.dto.SealRequest;
import com.example.starter.gas.dto.SignOffRequest;
import com.example.starter.gas.dto.VerificationResponse;
import com.example.starter.gas.dto.VerificationSubmitRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.service.QualityService;
import com.example.starter.gas.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 气质组分核验主流程与失败分支（H2 真实库）：
 * 组分勾稽、恰 1% 边界、暂停确认与签认解除、修订版本、封账不改写、幂等。
 */
class QualityVerificationTest extends AbstractH2IntegrationTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 20);

    @Autowired
    private CustodyService custodyService;
    @Autowired
    private QualityService qualityService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
    }

    private static SampleRequest sample(String time, String volume, Object... componentPairs) {
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        for (int i = 0; i < componentPairs.length; i += 2) {
            components.put((String) componentPairs[i], new BigDecimal((String) componentPairs[i + 1]));
        }
        return new SampleRequest(Instant.parse(time), new BigDecimal(volume), components);
    }

    /** 准备：一段读数（热值 40.000000）→ 气量日结束后生成 DRAFT 凭证。 */
    private VoucherResponse prepareDraftVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("rd-1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"),
                new BigDecimal("10000"), new BigDecimal("40.000000")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("vc-1", "ST01", GAS_DAY));
        assertThat(voucher.weightedHeatingValue()).isEqualByComparingTo("40.000000");
        return voucher;
    }

    private VerificationResponse submit(String requestId, String voucherNo, SampleRequest... samples) {
        return qualityService.submitVerification(new VerificationSubmitRequest(
                requestId, "ST01", GAS_DAY, voucherNo, List.of(samples)));
    }

    @Test
    void mainFlow_sampleVolumeWeightedVerification_consistent() {
        VoucherResponse voucher = prepareDraftVoucher();

        // 采样1：100 体积纯 CH4（40.0）；采样2：300 体积 CH4 90/C2H6 10（40.4）
        VerificationResponse response = submit("qv-1", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100"),
                sample("2026-09-20T16:00:00Z", "300", "CH4", "90", "C2H6", "10"));

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.sampleVolume()).isEqualByComparingTo("400.0000");
        // (100×40 + 300×40.4)/400 = 40.3
        assertThat(response.verifiedHeatingValue()).isEqualByComparingTo("40.300000");
        assertThat(response.voucherHeatingValue()).isEqualByComparingTo("40.000000");
        // |40.3-40|/40 = 0.0075 ≤ 1%：一致
        assertThat(response.deviationRate()).isEqualByComparingTo("0.007500");
        assertThat(response.conclusion()).isEqualTo("CONSISTENT");
        assertThat(response.diffStatus()).isEqualTo("NONE");
        assertThat(response.confirmationPaused()).isFalse();

        Integer sampleCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_sample WHERE station_code='ST01'", Integer.class);
        assertThat(sampleCount).isEqualTo(2);
    }

    @Test
    void exactlyOnePercentDeviation_isConsistent_onlyAboveMarksDiff() {
        VoucherResponse voucher = prepareDraftVoucher();

        // CH4 90/C2H6 10 → 核验热值 40.4，偏差恰 1%：一致
        VerificationResponse exact = submit("qv-exact", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "90", "C2H6", "10"));
        assertThat(exact.deviationRate()).isEqualByComparingTo("0.010000");
        assertThat(exact.conclusion()).isEqualTo("CONSISTENT");

        // 修订：CH4 89/C2H6 11 → 40.44，偏差 1.1% 超过 1%：气质差异
        VerificationResponse over = submit("qv-over", voucher.voucherNo(),
                sample("2026-09-20T09:00:00Z", "100", "CH4", "89", "C2H6", "11"));
        assertThat(over.deviationRate()).isEqualByComparingTo("0.011000");
        assertThat(over.conclusion()).isEqualTo("QUALITY_DIFF");
        assertThat(over.version()).isEqualTo(2);
    }

    @Test
    void qualityDiff_pausesConfirmation_signOffByBothPartiesReleases() {
        VoucherResponse voucher = prepareDraftVoucher();
        // A 方先确认：单方确认记录保留
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("cf-a", "A"));

        VerificationResponse diff = submit("qv-diff", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "89", "C2H6", "11"));
        assertThat(diff.conclusion()).isEqualTo("QUALITY_DIFF");
        assertThat(diff.diffStatus()).isEqualTo("OPEN");
        assertThat(diff.confirmationPaused()).isTrue();

        // 差异未解决：B 方确认被暂停；A 方已有确认记录保留
        ApiException paused = catchApi(() -> custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("cf-b", "B")));
        assertThat(paused.getStatus().value()).isEqualTo(409);
        assertThat(paused.getCode()).isEqualTo("CONFIRMATION_PAUSED");
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirmation WHERE voucher_no=?",
                Integer.class, voucher.voucherNo());
        assertThat(confirmations).isEqualTo(1);
        assertThat(custodyService.getVoucher(voucher.voucherNo()).status())
                .isEqualTo("PARTIALLY_CONFIRMED");

        // 单方签认不解除暂停
        VerificationResponse afterA = qualityService.signOff(diff.verificationNo(),
                new SignOffRequest("so-a", "A"));
        assertThat(afterA.diffStatus()).isEqualTo("OPEN");
        assertThat(afterA.confirmationPaused()).isTrue();

        // 双方签认后解除暂停，确认可继续并生效
        VerificationResponse resolved = qualityService.signOff(diff.verificationNo(),
                new SignOffRequest("so-b", "B"));
        assertThat(resolved.diffStatus()).isEqualTo("RESOLVED");
        assertThat(resolved.confirmationPaused()).isFalse();

        VoucherResponse effective = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("cf-b2", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
    }

    @Test
    void ratioSumNotExactlyHundred_rejected() {
        VoucherResponse voucher = prepareDraftVoucher();

        ApiException ex = catchApi(() -> submit("qv-bad-ratio", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "90", "C2H6", "9")));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("COMPONENT_RATIO_INVALID");
        // 拒绝入账：无采样、无核验单
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM quality_sample", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM quality_verification", Integer.class)).isZero();

        // 100.0 与 100 视为相等（恰 100% 可入账）
        VerificationResponse ok = submit("qv-ok-ratio", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "90.0", "C2H6", "10.00"));
        assertThat(ok.conclusion()).isEqualTo("CONSISTENT");
    }

    @Test
    void unknownComponent_rejected() {
        VoucherResponse voucher = prepareDraftVoucher();
        ApiException ex = catchApi(() -> submit("qv-unknown", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "H2", "100")));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("UNKNOWN_COMPONENT");
    }

    @Test
    void sampleOutsideGasDay_rejected() {
        VoucherResponse voucher = prepareDraftVoucher();
        ApiException ex = catchApi(() -> submit("qv-mismatch-day", voucher.voucherNo(),
                sample("2026-09-21T01:00:00Z", "100", "CH4", "100")));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("SAMPLE_GAS_DAY_MISMATCH");
    }

    @Test
    void zeroVoucherHeatingValue_rejectsVerification() {
        VoucherResponse voucher = prepareDraftVoucher();
        jdbc.update("UPDATE custody_voucher SET weighted_heating_value=0 WHERE voucher_no=?",
                voucher.voucherNo());

        ApiException ex = catchApi(() -> submit("qv-zero-hv", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100")));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_HEATING_VALUE_ZERO");
    }

    @Test
    void unknownStationVoucherAndMismatch_rejected() {
        VoucherResponse voucher = prepareDraftVoucher();

        ApiException noStation = catchApi(() -> qualityService.submitVerification(
                new VerificationSubmitRequest("qv-x1", "ST99", GAS_DAY, voucher.voucherNo(),
                        List.of(sample("2026-09-20T08:00:00Z", "100", "CH4", "100")))));
        assertThat(noStation.getStatus().value()).isEqualTo(404);
        assertThat(noStation.getCode()).isEqualTo("STATION_NOT_FOUND");

        ApiException noVoucher = catchApi(() -> submit("qv-x2", "V-NOT-EXIST",
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100")));
        assertThat(noVoucher.getStatus().value()).isEqualTo(404);
        assertThat(noVoucher.getCode()).isEqualTo("VOUCHER_NOT_FOUND");

        // 凭证属于 2026-09-20，核验气量日填 2026-09-21：不匹配
        ApiException mismatch = catchApi(() -> qualityService.submitVerification(
                new VerificationSubmitRequest("qv-x3", "ST01", LocalDate.of(2026, 9, 21),
                        voucher.voucherNo(),
                        List.of(sample("2026-09-21T08:00:00Z", "100", "CH4", "100")))));
        assertThat(mismatch.getStatus().value()).isEqualTo(400);
        assertThat(mismatch.getCode()).isEqualTo("VOUCHER_MISMATCH");
    }

    @Test
    void revisionAppendsVersion_oldConclusionQueryableAndPauseLifted() {
        VoucherResponse voucher = prepareDraftVoucher();

        VerificationResponse v1 = submit("qv-v1", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "89", "C2H6", "11"));
        assertThat(v1.conclusion()).isEqualTo("QUALITY_DIFF");
        assertThat(v1.confirmationPaused()).isTrue();

        // 修订：追加版本 v2（一致结论），v1 结论保留可查
        VerificationResponse v2 = submit("qv-v2", voucher.voucherNo(),
                sample("2026-09-20T10:00:00Z", "100", "CH4", "100"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("ACTIVE");
        assertThat(v2.conclusion()).isEqualTo("CONSISTENT");

        VerificationResponse old = qualityService.getVerification(v1.verificationNo());
        assertThat(old.status()).isEqualTo("SUPERSEDED");
        assertThat(old.conclusion()).isEqualTo("QUALITY_DIFF");

        List<VerificationResponse> versions = qualityService.listVerifications("ST01", GAS_DAY);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);

        // 生效版本无未解决差异：确认不再暂停
        VoucherResponse afterA = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("cf-a", "A"));
        assertThat(afterA.status()).isEqualTo("PARTIALLY_CONFIRMED");
    }

    @Test
    void idempotency_sameKeySameParamsReturnsFirst_sameKeyDifferentParamsConflicts() {
        VoucherResponse voucher = prepareDraftVoucher();

        VerificationResponse first = submit("qv-idem", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100"));
        VerificationResponse retry = submit("qv-idem", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100"));
        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM quality_verification", Integer.class))
                .isEqualTo(1);

        ApiException conflict = catchApi(() -> submit("qv-idem", voucher.voucherNo(),
                sample("2026-09-20T09:00:00Z", "200", "CH4", "100")));
        assertThat(conflict.getStatus().value()).isEqualTo(409);
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM quality_verification", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void duplicateSampleByStationAndTime_dedupedWithinAndAcrossSubmissions() {
        VoucherResponse voucher = prepareDraftVoucher();

        // 同一请求内同站同时刻重复采样：先者为准（体积 100，不是 500）
        VerificationResponse v1 = submit("qv-dup1", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100"),
                sample("2026-09-20T08:00:00Z", "500", "CH4", "100"));
        assertThat(v1.sampleVolume()).isEqualByComparingTo("100.0000");

        // 修订时再次提交同站同时刻采样：沿用首次入账记录（体积 100、热值 40.0）
        VerificationResponse v2 = submit("qv-dup2", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "999", "CH4", "100"),
                sample("2026-09-20T12:00:00Z", "100", "CH4", "90", "C2H6", "10"));
        // (100×40 + 100×40.4)/200 = 40.2
        assertThat(v2.verifiedHeatingValue()).isEqualByComparingTo("40.200000");
        assertThat(v2.sampleVolume()).isEqualByComparingTo("200.0000");

        Integer sampleCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_sample WHERE station_code='ST01'", Integer.class);
        assertThat(sampleCount).isEqualTo(2);
    }

    @Test
    void sealedVoucher_notRewrittenByLateVerification() {
        VoucherResponse voucher = prepareDraftVoucher();
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("cf-a", "A"));
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("cf-b", "B"));
        // 争议期（生效日 2026-09-21 起 7 日）结束后封账
        clock.setInstant(Instant.parse("2026-09-29T00:01:00Z"));
        VoucherResponse sealed = custodyService.seal(voucher.voucherNo(), new SealRequest("sl-1"));
        assertThat(sealed.status()).isEqualTo("SEALED");

        // 迟到核验：记录差异但不改写已封账凭证，也不产生暂停
        VerificationResponse late = submit("qv-late", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "89", "C2H6", "11"));
        assertThat(late.conclusion()).isEqualTo("QUALITY_DIFF");
        assertThat(late.confirmationPaused()).isFalse();

        VoucherResponse unchanged = custodyService.getVoucher(voucher.voucherNo());
        assertThat(unchanged.status()).isEqualTo("SEALED");
        assertThat(unchanged.weightedHeatingValue()).isEqualByComparingTo("40.000000");
        assertThat(unchanged.energy()).isEqualByComparingTo("400000.00");
    }

    @Test
    void signOffBranches_noDiff_superseded_duplicateAndIdempotency() {
        VoucherResponse voucher = prepareDraftVoucher();

        // 无差异核验单不能签认
        VerificationResponse consistent = submit("qv-so-1", voucher.voucherNo(),
                sample("2026-09-20T08:00:00Z", "100", "CH4", "100"));
        ApiException noDiff = catchApi(() -> qualityService.signOff(
                consistent.verificationNo(), new SignOffRequest("so-x1", "A")));
        assertThat(noDiff.getStatus().value()).isEqualTo(400);
        assertThat(noDiff.getCode()).isEqualTo("NO_QUALITY_DIFF");

        // 不存在的核验单
        ApiException notFound = catchApi(() -> qualityService.signOff(
                "Q-NOT-EXIST", new SignOffRequest("so-x2", "A")));
        assertThat(notFound.getStatus().value()).isEqualTo(404);
        assertThat(notFound.getCode()).isEqualTo("VERIFICATION_NOT_FOUND");

        // 修订后旧版本不能签认
        VerificationResponse v2 = submit("qv-so-2", voucher.voucherNo(),
                sample("2026-09-20T09:00:00Z", "100", "CH4", "89", "C2H6", "11"));
        VerificationResponse v3 = submit("qv-so-3", voucher.voucherNo(),
                sample("2026-09-20T10:00:00Z", "100", "CH4", "89", "C2H6", "11"));
        ApiException superseded = catchApi(() -> qualityService.signOff(
                v2.verificationNo(), new SignOffRequest("so-x3", "A")));
        assertThat(superseded.getStatus().value()).isEqualTo(409);
        assertThat(superseded.getCode()).isEqualTo("VERIFICATION_SUPERSEDED");

        // 同一方重复签认（不同 requestId）：返回当前状态，仅一条签认记录
        qualityService.signOff(v3.verificationNo(), new SignOffRequest("so-a1", "A"));
        VerificationResponse again = qualityService.signOff(v3.verificationNo(),
                new SignOffRequest("so-a2", "A"));
        assertThat(again.diffStatus()).isEqualTo("OPEN");
        Integer signoffs = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_diff_signoff WHERE verification_no=?",
                Integer.class, v3.verificationNo());
        assertThat(signoffs).isEqualTo(1);

        // 签认幂等：同键同参返回首次结果；同键异参 409
        VerificationResponse replay = qualityService.signOff(v3.verificationNo(),
                new SignOffRequest("so-a1", "A"));
        assertThat(replay.verificationNo()).isEqualTo(v3.verificationNo());
        ApiException conflict = catchApi(() -> qualityService.signOff(v3.verificationNo(),
                new SignOffRequest("so-a1", "B")));
        assertThat(conflict.getStatus().value()).isEqualTo(409);
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
}
