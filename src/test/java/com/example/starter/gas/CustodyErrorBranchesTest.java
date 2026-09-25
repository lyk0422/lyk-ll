package com.example.starter.gas;

import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DisputeRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
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
 * 失败分支与幂等边界：读数天然去重、重复确认、唯一争议、
 * 前置状态不满足（未生效考核/未封账调整/无读数/资源不存在）。
 */
class CustodyErrorBranchesTest extends AbstractH2IntegrationTest {

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
        addNomination(jdbc, GAS_DAY, "380000.00");
    }

    @Test
    void sameStationAndMeterTime_isDeduplicatedEvenWithDifferentRequestId() {
        clock.setInstant(Instant.parse("2026-09-20T10:00:00Z"));
        var first = custodyService.submitReading(new ReadingSubmitRequest("rr-1", "ST01",
                Instant.parse("2026-09-20T08:00:00Z"), new BigDecimal("1000"), new BigDecimal("39")));
        assertThat(first.duplicated()).isFalse();

        // 不同 requestId、同站同表底时刻（业务上为同一读数）：去重返回，不新增
        var dup = custodyService.submitReading(new ReadingSubmitRequest("rr-2", "ST01",
                Instant.parse("2026-09-20T08:00:00Z"), new BigDecimal("1000"), new BigDecimal("39")));
        assertThat(dup.duplicated()).isTrue();
        assertThat(dup.readingId()).isEqualTo(first.readingId());
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM meter_reading", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void unknownStation_returnsNotFound() {
        ApiException ex = catchApi(() -> custodyService.submitReading(new ReadingSubmitRequest("x1", "NOPE",
                Instant.parse("2026-09-20T08:00:00Z"), new BigDecimal("1"), new BigDecimal("39"))));
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("STATION_NOT_FOUND");
    }

    @Test
    void unknownVoucher_returnsNotFound() {
        ApiException ex = catchApi(() -> custodyService.getVoucher("V-NO-SUCH"));
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_NOT_FOUND");
    }

    @Test
    void noConfirmedReading_whenDayClosed_rejectsVoucher() {
        clock.setInstant(Instant.parse("2026-09-21T01:00:00Z"));
        ApiException ex = catchApi(() -> custodyService.createVoucher(
                new VoucherCreateRequest("v-empty", "ST01", GAS_DAY)));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("NO_CONFIRMED_READING");
    }

    @Test
    void duplicateConfirmationBySameParty_isIdempotentAndSingleRecord() {
        String voucherNo = prepareDraftVoucher();
        custodyService.confirm(voucherNo, new ConfirmRequest("cc-1", "A"));

        // A 方以新 requestId 重复确认：返回部分确认状态，不产生第二条确认
        VoucherResponse resp = custodyService.confirm(voucherNo, new ConfirmRequest("cc-2", "A"));
        assertThat(resp.status()).isEqualTo("PARTIALLY_CONFIRMED");
        Integer aCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirmation WHERE voucher_no=? AND party='A'",
                Integer.class, voucherNo);
        assertThat(aCount).isEqualTo(1);
    }

    @Test
    void secondDispute_returnsDisputedWithoutNewRecord() {
        String voucherNo = prepareEffectiveVoucher();
        clock.setInstant(Instant.parse("2026-09-25T10:00:00Z"));
        custodyService.dispute(voucherNo, new DisputeRequest("dp-1", "A", "原因一"));

        VoucherResponse again = custodyService.dispute(voucherNo,
                new DisputeRequest("dp-2", "B", "原因二"));
        assertThat(again.status()).isEqualTo("DISPUTED");
        Integer disputes = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_dispute WHERE voucher_no=?", Integer.class, voucherNo);
        assertThat(disputes).isEqualTo(1);
    }

    @Test
    void assessmentBeforeEffective_isRejected() {
        String voucherNo = prepareDraftVoucher();
        ApiException ex = catchApi(() -> custodyService.assessDay("ST01", GAS_DAY));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_NOT_EFFECTIVE");
    }

    @Test
    void adjustmentBeforeSeal_isRejected() {
        String voucherNo = prepareEffectiveVoucher();
        ApiException ex = catchApi(() -> custodyService.adjust(voucherNo,
                new com.example.starter.gas.dto.AdjustmentRequest("adj-x",
                        new BigDecimal("10000"), new BigDecimal("39.2"), true, true)));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("ORIGINAL_NOT_SEALED");
    }

    private String prepareDraftVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("er-1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"), new BigDecimal("10000"), new BigDecimal("39.2")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("ev-1", "ST01", GAS_DAY));
        return voucher.voucherNo();
    }

    private String prepareEffectiveVoucher() {
        String voucherNo = prepareDraftVoucher();
        custodyService.confirm(voucherNo, new ConfirmRequest("ec-1", "A"));
        custodyService.confirm(voucherNo, new ConfirmRequest("ec-2", "B"));
        return voucherNo;
    }
}
