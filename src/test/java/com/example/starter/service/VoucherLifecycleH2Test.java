package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.domain.ConflictException;
import com.example.starter.persistence.MeterReadingPO;
import com.example.starter.persistence.ReadingRepository;
import com.example.starter.web.dto.AssessmentResponse;
import com.example.starter.web.dto.ConfirmRequest;
import com.example.starter.web.dto.NominationRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import com.example.starter.web.dto.VoucherResponse;
import com.example.starter.web.dto.VoucherSubmitRequest;

/**
 * 主流程：能量折算、同日唯一凭证、双方确认生效、读数归位/迟到、幂等语义。
 */
class VoucherLifecycleH2Test extends AbstractH2IntegrationTest {

    @Test
    void energyConversionFromReadings() {
        createStation();
        seedDayReadings();

        VoucherResponse voucher = submit("req-submit-1");

        // 体积 = 1200 - 1000 = 200 Nm3
        assertThat(voucher.volume()).isEqualByComparingTo("200.000");
        // 全部增量发生在 00:00→23:00 一段，取终点热值 40
        assertThat(voucher.avgHeatingValue()).isEqualByComparingTo("40.0000");
        // 能量 = 200 × 40 = 8000.00 MJ
        assertThat(voucher.energyMj()).isEqualByComparingTo("8000.00");
        assertThat(voucher.status()).isEqualTo("DRAFT");
    }

    @Test
    void readingsAreOrderedByMeterTimeEvenWhenArriveOutOfOrder() {
        createStation();
        seedDayReadings();

        List<ReadingResponse> readings = service.listReadings(STATION, GAS_DAY);
        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).readingTime()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(readings.get(1).readingTime()).isEqualTo(Instant.parse("2026-09-10T23:00:00Z"));
    }

    @Test
    void duplicateReadingByStationAndTimeIsDeduplicated() {
        createStation();
        seedDayReadings();
        // 同站同表底时刻再次上报，即使表底/热值不同也去重，不覆盖原值
        service.uploadReading(new ReadingRequest(STATION, "METER-A",
                Instant.parse("2026-09-10T00:00:00Z"), new BigDecimal("9999"),
                new BigDecimal("99")));

        List<ReadingResponse> readings = service.listReadings(STATION, GAS_DAY);
        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).meterIndex()).isEqualByComparingTo("1000.000");
        assertThat(readings.get(0).heatingValue()).isEqualByComparingTo("38.0000");
    }

    @Test
    void onlyOneValidVoucherPerStationAndGasDay() {
        createStation();
        seedDayReadings();
        submit("req-submit-1");

        assertThatThrownBy(() -> submit("req-submit-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同站同气量日");
        assertThat(count("SELECT COUNT(*) FROM voucher")).isEqualTo(1);
    }

    @Test
    void singlePartyConfirmationDoesNotMakeEffective() {
        createStation();
        seedDayReadings();
        VoucherResponse voucher = submit("req-submit-1");

        var afterA = service.confirm(new ConfirmRequest("req-conf-a", voucher.voucherNo(),
                "A", "张三")).body();
        assertThat(afterA.partyAConfirmed()).isTrue();
        assertThat(afterA.partyBConfirmed()).isFalse();
        assertThat(afterA.status()).isEqualTo("DRAFT");
        assertThat(afterA.effectiveAt()).isNull();
    }

    @Test
    void bothPartiesConfirmInSequenceMakesEffectiveWithSevenDayDeadline() {
        VoucherResponse effective = effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");

        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.partyAConfirmed()).isTrue();
        assertThat(effective.partyBConfirmed()).isTrue();
        // 固定时钟 2026-09-11 生效 → 争议期截止恰为 2026-09-18（第 7 日）
        assertThat(effective.effectiveAt()).isEqualTo(Instant.parse("2026-09-11T01:00:00Z"));
        assertThat(effective.disputeDeadline()).isEqualTo(java.time.LocalDate.of(2026, 9, 18));
    }

    @Test
    void repeatedConfirmBySamePartyIsIdempotentAndStaysDraft() {
        createStation();
        seedDayReadings();
        VoucherResponse voucher = submit("req-submit-1");

        service.confirm(new ConfirmRequest("req-conf-a-1", voucher.voucherNo(), "A", "张三"));
        // 换 requestId 但同为 A 方重复确认：不报错、仍 DRAFT（该方确认幂等）
        var repeated = service.confirm(new ConfirmRequest("req-conf-a-2", voucher.voucherNo(),
                "A", "张三")).body();
        assertThat(repeated.status()).isEqualTo("DRAFT");
        // 提交 + 两次 A 方确认各一条幂等记录
        assertThat(count("SELECT COUNT(*) FROM idempotency_record")).isEqualTo(3);
    }

    @Test
    void submitIdempotentSameKeySameParamsReturnsFirstResult() {
        createStation();
        seedDayReadings();
        var first = service.submitVoucher(new VoucherSubmitRequest("dup-key", STATION, GAS_DAY));
        var second = service.submitVoucher(new VoucherSubmitRequest("dup-key", STATION, GAS_DAY));

        assertThat(second.httpStatus()).isEqualTo(first.httpStatus());
        assertThat(second.body().voucherNo()).isEqualTo(first.body().voucherNo());
        assertThat(count("SELECT COUNT(*) FROM voucher")).isEqualTo(1);
    }

    @Test
    void submitIdempotentSameKeyDifferentParamsIsConflictAndNoNewVersion() {
        createStation();
        seedDayReadings();
        service.submitVoucher(new VoucherSubmitRequest("dup-key", STATION, GAS_DAY));

        var otherDay = java.time.LocalDate.of(2026, 9, 11);
        assertThatThrownBy(() -> service.submitVoucher(
                new VoucherSubmitRequest("dup-key", STATION, otherDay)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("业务参数不一致");
        // 不产生新版本：只有 9-10 一张凭证
        assertThat(count("SELECT COUNT(*) FROM voucher")).isEqualTo(1);
    }

    @Test
    void lateReadingAfterVoucherIsMarkedAndRecordedAsDifferenceWithoutChangingVoucher() {
        createStation();
        seedDayReadings();
        VoucherResponse voucher = submit("req-submit-1");
        BigDecimal originalEnergy = voucher.energyMj();

        // 凭证生成后，同日窗口内迟到一条读数（表底时刻仍在 9-10 内）
        var late = service.uploadReading(new ReadingRequest(STATION, "METER-A",
                Instant.parse("2026-09-10T12:00:00Z"), new BigDecimal("1100.000"),
                new BigDecimal("39.0000")));

        assertThat(late.late()).isTrue();
        // 已生成凭证不被改动
        VoucherResponse reloaded = service.getVoucher(voucher.voucherNo());
        assertThat(reloaded.energyMj()).isEqualByComparingTo(originalEnergy);
        assertThat(count("SELECT COUNT(*) FROM reading_difference WHERE voucher_no = ?",
                voucher.voucherNo())).isEqualTo(1);
    }

    @Test
    void assessmentRecomputesStablyAndReflectsAdjustment() {
        effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");
        service.saveNomination(new NominationRequest(STATION, GAS_DAY, new BigDecimal("8000"),
                new BigDecimal("0.5")));

        List<AssessmentResponse> first = service.listAssessments(STATION, GAS_DAY, GAS_DAY);
        List<AssessmentResponse> second = service.listAssessments(STATION, GAS_DAY, GAS_DAY);
        assertThat(first).usingRecursiveComparison().isEqualTo(second);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).exempt()).isTrue();
        assertThat(first.get(0).penaltyAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void assessmentChargesPenaltyOnlyOnExcessBeyondThreePercent() {
        // 实际能量 8000 MJ，日指定 6600 MJ：偏差 1400（>3%=198），计费部分 1202，单价 0.5 → 601.00 元
        effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");
        service.saveNomination(new NominationRequest(STATION, GAS_DAY, new BigDecimal("6600"),
                new BigDecimal("0.5")));

        List<AssessmentResponse> rows = service.listAssessments(STATION, GAS_DAY, GAS_DAY);
        assertThat(rows).hasSize(1);
        AssessmentResponse row = rows.get(0);
        assertThat(row.exempt()).isFalse();
        assertThat(row.actualEnergyMj()).isEqualByComparingTo("8000.00");
        assertThat(row.deviationAbsMj()).isEqualByComparingTo("1400.00");
        assertThat(row.billableExcessMj()).isEqualByComparingTo("1202");
        assertThat(row.penaltyAmount()).isEqualByComparingTo("601.00");
    }

    @Test
    void readingAfterGasDayBoundaryBelongsToNextDay() {
        createStation();
        seedDayReadings();
        VoucherResponse voucher = submit("req-submit-1");

        // 表底时刻恰为气量日次日 00:00（= 9-10 24:00 UTC 之后），归入次日，不影响当日凭证
        var nextDayReading = service.uploadReading(new ReadingRequest(STATION, "METER-A",
                Instant.parse("2026-09-11T00:00:00Z"), new BigDecimal("1210.000"),
                new BigDecimal("40.0000")));
        assertThat(nextDayReading.late()).isFalse();

        // 9-10 当日读数仍为两条、凭证不变
        assertThat(service.listReadings(STATION, GAS_DAY)).hasSize(2);
        assertThat(service.listReadings(STATION, GAS_DAY.plusDays(1))).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM reading_difference WHERE voucher_no = ?",
                voucher.voucherNo())).isZero();
    }
}
