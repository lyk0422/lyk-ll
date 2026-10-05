package com.example.starter.gas;

import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DifferenceSignOffRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.SealRequest;
import com.example.starter.gas.dto.VerificationResponse;
import com.example.starter.gas.dto.VerificationSampleRequest;
import com.example.starter.gas.dto.VerificationSubmitRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.service.QualityVerificationService;
import com.example.starter.gas.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 气质组分核验主流程与失败分支（H2 真实库）：
 * 组分勾稽、恰 1% 边界、差异暂停确认、双方签认解除、版本追加、封账不改写、幂等。
 */
class QualityVerificationFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 20);

    @Autowired
    private CustodyService custodyService;
    @Autowired
    private QualityVerificationService verificationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        addNomination(jdbc, GAS_DAY, "400000.00");
    }

    /** 生成凭证热值恰为 40.000000 的原始凭证（DRAFT）。 */
    private VoucherResponse prepareDraftVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("qr-1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"),
                new BigDecimal("10000"), new BigDecimal("40.000000")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        return custodyService.createVoucher(new VoucherCreateRequest("qv-1", "ST01", GAS_DAY));
    }

    private VerificationSubmitRequest verificationRequest(String requestId, String sampleTime,
                                                          String volume, Map<String, BigDecimal> components) {
        return new VerificationSubmitRequest(requestId, "ST01", GAS_DAY,
                List.of(new VerificationSampleRequest(Instant.parse(sampleTime),
                        new BigDecimal(volume), components)));
    }

    @Test
    void exactOnePercentDeviation_isConsistent_andConfirmNotPaused() {
        VoucherResponse voucher = prepareDraftVoucher();
        assertThat(voucher.weightedHeatingValue()).isEqualByComparingTo("40.000000");

        // 98% CH4 + 2% C2H6 → 核验热值 40.4，相对偏差恰为 1%
        VerificationResponse response = verificationService.submitVerification(
                verificationRequest("q-1pct", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2"))));

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.verifiedHeatingValue()).isEqualByComparingTo("40.400000");
        assertThat(response.voucherHeatingValue()).isEqualByComparingTo("40.000000");
        assertThat(response.relativeDeviation()).isEqualByComparingTo("0.01000000");
        assertThat(response.differenceFlag()).isFalse();
        assertThat(response.status()).isEqualTo("CONSISTENT");
        assertThat(response.confirmPaused()).isFalse();

        // 无差异：双方确认不受限，凭证正常生效
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("qc-a", "A"));
        VoucherResponse effective = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("qc-b", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
    }

    @Test
    void ratioNotExactlyHundred_rejectedAndNothingPersisted() {
        prepareDraftVoucher();
        ApiException ex = catchApi(() -> verificationService.submitVerification(
                verificationRequest("q-bad-sum", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("1.5")))));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("COMPONENT_RATIO_NOT_100");
        assertThat(countRows("quality_sample")).isZero();
        assertThat(countRows("quality_verification")).isZero();
    }

    @Test
    void unknownComponent_rejected() {
        prepareDraftVoucher();
        ApiException ex = catchApi(() -> verificationService.submitVerification(
                verificationRequest("q-unknown", "2026-09-20T10:00:00Z", "500",
                        Map.of("H2S", new BigDecimal("100")))));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("COMPONENT_UNKNOWN");
        assertThat(countRows("quality_verification")).isZero();
    }

    @Test
    void sampleOutsideGasDay_rejected() {
        prepareDraftVoucher();
        ApiException ex = catchApi(() -> verificationService.submitVerification(
                verificationRequest("q-day", "2026-09-21T01:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("100")))));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("SAMPLE_DAY_MISMATCH");
    }

    @Test
    void zeroVoucherHeatingValue_verificationRejected() {
        // 直接以服务层构造热值为 0 的读数（控制器校验之外的边界），凭证热值为零
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("qr-zero", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"),
                new BigDecimal("10000"), BigDecimal.ZERO));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        custodyService.createVoucher(new VoucherCreateRequest("qv-zero", "ST01", GAS_DAY));

        ApiException ex = catchApi(() -> verificationService.submitVerification(
                verificationRequest("q-zero", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("100")))));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_HEATING_VALUE_ZERO");
        assertThat(countRows("quality_verification")).isZero();
    }

    @Test
    void missingVoucher_verificationRejected() {
        ApiException ex = catchApi(() -> verificationService.submitVerification(
                verificationRequest("q-no-voucher", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("100")))));
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_NOT_FOUND");
    }

    @Test
    void difference_pausesConfirm_untilBothPartiesSignOff() {
        VoucherResponse voucher = prepareDraftVoucher();
        // A 方先确认（差异出现前的确认记录须保留）
        VoucherResponse partial = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("qc-a", "A"));
        assertThat(partial.status()).isEqualTo("PARTIALLY_CONFIRMED");

        // 97% CH4 + 3% C2H6 → 核验热值 40.6，偏差 1.5% > 1% → 气质差异
        VerificationResponse difference = verificationService.submitVerification(
                verificationRequest("q-diff", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("97"), "C2H6", new BigDecimal("3"))));
        assertThat(difference.differenceFlag()).isTrue();
        assertThat(difference.status()).isEqualTo("DIFFERENT_OPEN");
        assertThat(difference.confirmPaused()).isTrue();

        // 差异未解决：B 方确认被暂停；A 方已有确认记录保留
        ApiException paused = catchApi(() -> custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("qc-b", "B")));
        assertThat(paused.getStatus().value()).isEqualTo(409);
        assertThat(paused.getCode()).isEqualTo("CONFIRM_PAUSED");
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirmation WHERE voucher_no=?",
                Integer.class, voucher.voucherNo());
        assertThat(confirmations).isEqualTo(1);

        // 单方签认不解除暂停
        VerificationResponse afterA = verificationService.signOffDifference(
                difference.verificationNo(), new DifferenceSignOffRequest("qs-a", "A"));
        assertThat(afterA.signOffA()).isTrue();
        assertThat(afterA.signOffB()).isFalse();
        assertThat(afterA.status()).isEqualTo("DIFFERENT_OPEN");
        assertThat(afterA.confirmPaused()).isTrue();
        ApiException stillPaused = catchApi(() -> custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("qc-b2", "B")));
        assertThat(stillPaused.getCode()).isEqualTo("CONFIRM_PAUSED");

        // 双方签认齐备：差异解除、暂停解除，B 方确认后凭证生效
        VerificationResponse resolved = verificationService.signOffDifference(
                difference.verificationNo(), new DifferenceSignOffRequest("qs-b", "B"));
        assertThat(resolved.status()).isEqualTo("DIFFERENT_RESOLVED");
        assertThat(resolved.confirmPaused()).isFalse();
        assertThat(resolved.resolvedAt()).isNotNull();

        VoucherResponse effective = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("qc-b3", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        Integer finalConfirmations = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirmation WHERE voucher_no=?",
                Integer.class, voucher.voucherNo());
        assertThat(finalConfirmations).isEqualTo(2);
    }

    @Test
    void revision_appendsVersion_andOldConclusionQueryable() {
        prepareDraftVoucher();
        // v1：单样 98% CH4 + 2% C2H6 → 恰 1% 一致
        VerificationResponse v1 = verificationService.submitVerification(
                verificationRequest("q-v1", "2026-09-20T10:00:00Z", "100",
                        Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2"))));
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo("CONSISTENT");

        // v2：追加同日采样（100% CH4，体积 300），加权后核验热值 40.1，仍一致
        VerificationResponse v2 = verificationService.submitVerification(
                verificationRequest("q-v2", "2026-09-20T18:00:00Z", "300",
                        Map.of("CH4", new BigDecimal("100"))));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.sampleCount()).isEqualTo(2);
        assertThat(v2.verifiedHeatingValue()).isEqualByComparingTo("40.100000");
        assertThat(v2.relativeDeviation()).isEqualByComparingTo("0.00250000");

        // 旧版本结论可查
        List<VerificationResponse> versions = verificationService.listVerifications("ST01", GAS_DAY);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(0).verifiedHeatingValue()).isEqualByComparingTo("40.400000");
        assertThat(versions.get(1).version()).isEqualTo(2);
        VerificationResponse old = verificationService.getVerification(v1.verificationNo());
        assertThat(old.relativeDeviation()).isEqualByComparingTo("0.01000000");
    }

    @Test
    void duplicateSample_sameStationAndTime_deduped() {
        prepareDraftVoucher();
        // 同一提交内两条相同时刻采样：按“站＋时刻”去重，仅首条入账
        VerificationSubmitRequest request = new VerificationSubmitRequest("q-dup", "ST01", GAS_DAY,
                List.of(new VerificationSampleRequest(Instant.parse("2026-09-20T10:00:00Z"),
                                new BigDecimal("100"),
                                Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2"))),
                        new VerificationSampleRequest(Instant.parse("2026-09-20T10:00:00Z"),
                                new BigDecimal("9999"), Map.of("CH4", new BigDecimal("100")))));
        VerificationResponse v1 = verificationService.submitVerification(request);
        assertThat(v1.sampleCount()).isEqualTo(1);
        assertThat(v1.verifiedHeatingValue()).isEqualByComparingTo("40.400000");
        assertThat(countRows("quality_sample")).isEqualTo(1);

        // 后续版本重复提交同一采样：不重复计数
        VerificationResponse v2 = verificationService.submitVerification(
                verificationRequest("q-dup-2", "2026-09-20T10:00:00Z", "100",
                        Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2"))));
        assertThat(v2.sampleCount()).isEqualTo(1);
        assertThat(countRows("quality_sample")).isEqualTo(1);
    }

    @Test
    void idempotency_sameKeySameParamsReturnsFirst_sameKeyDifferentParamsConflicts() {
        prepareDraftVoucher();
        VerificationResponse first = verificationService.submitVerification(
                verificationRequest("q-idem", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2"))));
        VerificationResponse retry = verificationService.submitVerification(
                verificationRequest("q-idem", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2"))));
        assertThat(retry).isEqualTo(first);
        assertThat(countRows("quality_verification")).isEqualTo(1);

        ApiException conflict = catchApi(() -> verificationService.submitVerification(
                verificationRequest("q-idem", "2026-09-20T11:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("100")))));
        assertThat(conflict.getStatus().value()).isEqualTo(409);
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(countRows("quality_verification")).isEqualTo(1);
    }

    @Test
    void signOffOnConsistentVerification_rejected() {
        prepareDraftVoucher();
        VerificationResponse consistent = verificationService.submitVerification(
                verificationRequest("q-con", "2026-09-20T10:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("100"))));
        assertThat(consistent.status()).isEqualTo("CONSISTENT");
        ApiException ex = catchApi(() -> verificationService.signOffDifference(
                consistent.verificationNo(), new DifferenceSignOffRequest("qs-x", "A")));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("VERIFICATION_CONSISTENT");
    }

    @Test
    void sealedVoucher_lateSampleDoesNotRewrite_andAdjustmentStillReferencesOriginal() {
        VoucherResponse voucher = prepareDraftVoucher();
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("qc-a", "A"));
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("qc-b", "B"));
        // 争议期（至 2026-09-28 含）结束后封账
        clock.setInstant(Instant.parse("2026-09-29T00:10:00Z"));
        VoucherResponse sealed = custodyService.seal(voucher.voucherNo(), new SealRequest("q-seal"));
        assertThat(sealed.status()).isEqualTo("SEALED");

        // 迟到采样（归属已封账气量日）触发气质差异：不改写封账凭证、不产生确认暂停
        VerificationResponse difference = verificationService.submitVerification(
                verificationRequest("q-late", "2026-09-20T22:00:00Z", "500",
                        Map.of("CH4", new BigDecimal("97"), "C2H6", new BigDecimal("3"))));
        assertThat(difference.differenceFlag()).isTrue();
        assertThat(difference.confirmPaused()).isFalse();

        VoucherResponse unchanged = custodyService.getVoucher(voucher.voucherNo());
        assertThat(unchanged.status()).isEqualTo("SEALED");
        assertThat(unchanged.volume()).isEqualByComparingTo("10000.0000");
        assertThat(unchanged.weightedHeatingValue()).isEqualByComparingTo("40.000000");
        assertThat(unchanged.energy()).isEqualByComparingTo("400000.00");
        assertThat(countRows("voucher_confirm_pause")).isZero();

        // 封账后如需调整：只能沿用原有调整凭证并引用原号
        VoucherResponse adjustment = custodyService.adjust(voucher.voucherNo(),
                new com.example.starter.gas.dto.AdjustmentRequest("q-adj",
                        new BigDecimal("10000"), new BigDecimal("40.600000"), true, true));
        assertThat(adjustment.voucherType()).isEqualTo("ADJUSTMENT");
        assertThat(adjustment.originalVoucherNo()).isEqualTo(voucher.voucherNo());
        assertThat(adjustment.status()).isEqualTo("EFFECTIVE");
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
