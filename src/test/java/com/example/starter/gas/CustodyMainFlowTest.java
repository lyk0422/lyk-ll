package com.example.starter.gas;

import com.example.starter.gas.dto.AssessmentResponse;
import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.ReadingSubmitResponse;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读数入库 → 凭证生成 → 双方确认 → 偏差考核的主流程，
 * 含能量折算、同日唯一、双方确认、3% 恰免考、读数乱序归位、幂等等场景。
 */
class CustodyMainFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 20);

    @Autowired
    private CustodyService custodyService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        addNomination(jdbc, GAS_DAY, "10000.00");
    }

    private ReadingSubmitResponse reading(String requestId, String meterTime,
                                          String volume, String hv) {
        return custodyService.submitReading(new ReadingSubmitRequest(requestId, "ST01",
                Instant.parse(meterTime), new BigDecimal(volume), new BigDecimal(hv)));
    }

    @Test
    void fullFlow_readingsVoucherBothConfirmAndAssessment_atExactThreePercentExempt() {
        // 气量日当日（2026-09-20 UTC）分两段读数，乱序提交（晚间读数先入库）
        clock.setInstant(Instant.parse("2026-09-20T20:00:00Z"));
        var evening = reading("rd-evening", "2026-09-20T20:00:00Z", "4000", "38.000000");
        clock.setInstant(Instant.parse("2026-09-20T12:05:00Z"));
        var morning = reading("rd-morning", "2026-09-20T12:00:00Z", "6000", "40.000000");
        assertThat(evening.duplicated()).isFalse();
        assertThat(morning.duplicated()).isFalse();
        // 均按表底时刻归位到同一气量日
        assertThat(evening.gasDate()).isEqualTo(GAS_DAY);
        assertThat(morning.gasDate()).isEqualTo(GAS_DAY);

        // 气量日未结束不能生成凭证
        ApiException early = catchApi(() -> custodyService.createVoucher(
                new VoucherCreateRequest("vc-1", "ST01", GAS_DAY)));
        assertThat(early.getCode()).isEqualTo("GAS_DAY_NOT_CLOSED");

        // 24:00（UTC）后生成凭证
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("vc-1", "ST01", GAS_DAY));
        assertThat(voucher.volume()).isEqualByComparingTo("10000.0000");
        // (6000×40 + 4000×38)/10000 = 39.2
        assertThat(voucher.weightedHeatingValue()).isEqualByComparingTo("39.200000");
        // 体积×加权热值 = 392000.00
        assertThat(voucher.energy()).isEqualByComparingTo("392000.00");
        assertThat(voucher.status()).isEqualTo("DRAFT");

        // 单方确认不生效
        VoucherResponse afterA = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("cf-a", "A"));
        assertThat(afterA.status()).isEqualTo("PARTIALLY_CONFIRMED");
        assertThat(afterA.firstConfirmParty()).isEqualTo("A");

        // B 方确认后生效
        VoucherResponse effective = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("cf-b", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void sameStationSameGasDay_onlyOneVoucher() {
        prepareEffectiveVoucher();

        ApiException ex = catchApi(() -> custodyService.createVoucher(
                new VoucherCreateRequest("vc-dup", "ST01", GAS_DAY)));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_ALREADY_EXISTS");

        Integer voucherCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM custody_voucher WHERE station_code='ST01' AND gas_date=? "
                        + "AND voucher_type='ORIGINAL'", Integer.class, java.sql.Date.valueOf(GAS_DAY));
        assertThat(voucherCount).isEqualTo(1);
    }

    @Test
    void idempotency_sameKeySameParamsReturnsFirstResult_sameKeyDifferentParamsConflicts() {
        clock.setInstant(Instant.parse("2026-09-20T10:00:00Z"));
        var first = reading("idem-rd", "2026-09-20T10:00:00Z", "1000", "39.000000");
        // 同键同参：返回首次结果快照（首次为新写入，duplicated=false）
        var retry = custodyService.submitReading(new ReadingSubmitRequest("idem-rd", "ST01",
                Instant.parse("2026-09-20T10:00:00Z"), new BigDecimal("1000"), new BigDecimal("39.000000")));
        assertThat(retry.readingId()).isEqualTo(first.readingId());
        assertThat(retry.duplicated()).isEqualTo(first.duplicated());
        assertThat(retry).isEqualTo(first);

        // 同键异参：409 且无新版本/新读数
        ApiException conflict = catchApi(() -> custodyService.submitReading(
                new ReadingSubmitRequest("idem-rd", "ST01",
                        Instant.parse("2026-09-20T11:00:00Z"),
                        new BigDecimal("2000"), new BigDecimal("39.000000"))));
        assertThat(conflict.getStatus().value()).isEqualTo(409);
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM meter_reading", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void lateReading_afterDayBoundary_goesToDiffAndDoesNotChangeVoucher() {
        // 24:00 前入库一段读数
        clock.setInstant(Instant.parse("2026-09-20T23:50:00Z"));
        reading("rd-on-time", "2026-09-20T23:00:00Z", "5000", "40.000000");
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("vc-late", "ST01", GAS_DAY));
        assertThat(voucher.volume()).isEqualByComparingTo("5000.0000");

        // 24:00 后才入库、但表底时刻仍属 20 日的迟到读数：不改凭证
        clock.setInstant(Instant.parse("2026-09-21T01:00:00Z"));
        reading("rd-late", "2026-09-20T21:00:00Z", "3000", "35.000000");
        VoucherResponse unchanged = custodyService.getVoucher(voucher.voucherNo());
        assertThat(unchanged.volume()).isEqualByComparingTo("5000.0000");
        assertThat(unchanged.energy()).isEqualByComparingTo("200000.00");

        Integer diffCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_reading_diff WHERE voucher_no=?",
                Integer.class, voucher.voucherNo());
        assertThat(diffCount).isEqualTo(1);
    }

    @Test
    void assessment_isStableOnRecomputeAndThreePercentBoundaryExempt() {
        prepareEffectiveVoucher();

        // 实际能量 392000，指定能量按比例换算使偏差恰为 3%：
        // 指定 N 满足 |392000 - N| = 0.03 N → N = 392000/1.03 ≈ 380582.52
        jdbc.update("UPDATE daily_nomination SET nominated_energy=380582.52 WHERE gas_date=?",
                java.sql.Date.valueOf(GAS_DAY));

        AssessmentResponse first = custodyService.assessDay("ST01", GAS_DAY);
        // |392000 - 380582.52| = 11417.48；3% × 380582.52 = 11417.4756 → 11417.48
        assertThat(first.deviationAbs()).isEqualByComparingTo("11417.48");
        assertThat(first.toleranceEnergy()).isEqualByComparingTo("11417.48");
        assertThat(first.exempt()).isTrue();
        assertThat(first.penalty()).isEqualByComparingTo("0.00");

        // 逐日复算结果稳定（仍一行、免考）
        AssessmentResponse second = custodyService.assessDay("ST01", GAS_DAY);
        assertThat(second).isEqualTo(first);
        Integer rows = jdbc.queryForObject("SELECT COUNT(1) FROM daily_assessment", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void assessment_overThreePercent_chargesPenaltyOnExcess() {
        prepareEffectiveVoucher();
        // 指定能量 380000：偏差 12000，阈值 11400，超出 600 × 0.5 = 300
        jdbc.update("UPDATE daily_nomination SET nominated_energy=380000.00 WHERE gas_date=?",
                java.sql.Date.valueOf(GAS_DAY));

        AssessmentResponse assessment = custodyService.assessDay("ST01", GAS_DAY);
        assertThat(assessment.exempt()).isFalse();
        assertThat(assessment.deviation()).isEqualByComparingTo("12000.00");
        assertThat(assessment.toleranceEnergy()).isEqualByComparingTo("11400.00");
        assertThat(assessment.penalty()).isEqualByComparingTo("300.00");
        assertThat(assessment.voucherNo()).startsWith("V-ST01-2026-09-20-");
    }

    /** 准备：两段读数 → 生成凭证 → 双方确认生效（生效日 2026-09-21）。 */
    private VoucherResponse prepareEffectiveVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        reading("prep-morning", "2026-09-20T12:00:00Z", "6000", "40.000000");
        clock.setInstant(Instant.parse("2026-09-20T20:00:00Z"));
        reading("prep-evening", "2026-09-20T20:00:00Z", "4000", "38.000000");
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("prep-vc", "ST01", GAS_DAY));
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("prep-a", "A"));
        VoucherResponse effective = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("prep-b", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        return effective;
    }
}
