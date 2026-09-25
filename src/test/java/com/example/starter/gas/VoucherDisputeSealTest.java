package com.example.starter.gas;

import com.example.starter.gas.dto.AdjustmentRequest;
import com.example.starter.gas.dto.AssessmentResponse;
import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DisputeRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.SealRequest;
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
 * 争议期边界（UTC 日界）、封账终态、封账后调整凭证与考核引用测试。
 * 凭证于 2026-09-21 生效，争议期为 09-22 至 09-28（含第 7 日），09-29 起封账。
 */
class VoucherDisputeSealTest extends AbstractH2IntegrationTest {

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

    private VoucherResponse effectiveVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("r1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"), new BigDecimal("10000"), new BigDecimal("39.200000")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("v1", "ST01", GAS_DAY));
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("c1", "A"));
        VoucherResponse effective = custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("c2", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        return effective;
    }

    @Test
    void disputeBeforeEffective_isRejected() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("r-draft", "ST01",
                Instant.parse("2026-09-20T18:00:00Z"), new BigDecimal("1000"), new BigDecimal("39")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse draft = custodyService.createVoucher(
                new VoucherCreateRequest("v-draft", "ST01", GAS_DAY));

        ApiException ex = catchApi(() -> custodyService.dispute(draft.voucherNo(),
                new DisputeRequest("d0", "A", "未生效先争议")));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("VOUCHER_NOT_EFFECTIVE");
    }

    @Test
    void disputeExactlyOnSeventhDay_isAccepted() {
        VoucherResponse voucher = effectiveVoucher();

        // 恰第 7 个自然日（2026-09-28 UTC）发起争议，受理挂起
        clock.setInstant(Instant.parse("2026-09-28T12:00:00Z"));
        VoucherResponse disputed = custodyService.dispute(voucher.voucherNo(),
                new DisputeRequest("d7", "A", "热值存疑"));
        assertThat(disputed.status()).isEqualTo("DISPUTED");
        Integer disputeCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_dispute WHERE voucher_no=?",
                Integer.class, voucher.voucherNo());
        assertThat(disputeCount).isEqualTo(1);
    }

    @Test
    void sealWithinWindow_isRejected() {
        VoucherResponse voucher = effectiveVoucher();
        clock.setInstant(Instant.parse("2026-09-28T12:00:00Z"));
        ApiException ex = catchApi(() -> custodyService.seal(voucher.voucherNo(),
                new SealRequest("s-early")));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("SEAL_TOO_EARLY");
        assertThat(custodyService.getVoucher(voucher.voucherNo()).status()).isEqualTo("EFFECTIVE");
    }

    @Test
    void disputeAfterWindow_sealsAndRejects() {
        VoucherResponse voucher = effectiveVoucher();

        // 第 8 日（2026-09-29）争议：逾期封账为终态，争议拒绝
        clock.setInstant(Instant.parse("2026-09-29T00:01:00Z"));
        ApiException ex = catchApi(() -> custodyService.dispute(voucher.voucherNo(),
                new DisputeRequest("d-late", "B", "逾期争议")));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("DISPUTE_WINDOW_CLOSED");
        assertThat(custodyService.getVoucher(voucher.voucherNo()).status()).isEqualTo("SEALED");
    }

    @Test
    void sealedVoucher_rejectsModifyAndDispute_repeatedSealIdempotent() {
        VoucherResponse voucher = effectiveVoucher();
        clock.setInstant(Instant.parse("2026-09-29T00:01:00Z"));

        VoucherResponse sealed = custodyService.seal(voucher.voucherNo(), new SealRequest("s1"));
        assertThat(sealed.status()).isEqualTo("SEALED");
        assertThat(sealed.sealedAt()).isNotNull();

        // 重复封账：幂等成功
        VoucherResponse sealedAgain = custodyService.seal(voucher.voucherNo(), new SealRequest("s2"));
        assertThat(sealedAgain.status()).isEqualTo("SEALED");
        assertThat(sealedAgain.sealedAt()).isEqualTo(sealed.sealedAt());

        // 封账后确认与争议一律拒绝
        ApiException confirmEx = catchApi(() -> custodyService.confirm(voucher.voucherNo(),
                new ConfirmRequest("c-late", "A")));
        assertThat(confirmEx.getCode()).isEqualTo("VOUCHER_SEALED");
        ApiException disputeEx = catchApi(() -> custodyService.dispute(voucher.voucherNo(),
                new DisputeRequest("d-x", "A", "封账后争议")));
        assertThat(disputeEx.getCode()).isEqualTo("VOUCHER_SEALED");
    }

    @Test
    void postSealError_handledByDualSignedAdjustmentAndAssessmentUsesIt() {
        VoucherResponse voucher = effectiveVoucher();
        clock.setInstant(Instant.parse("2026-09-29T00:01:00Z"));
        custodyService.seal(voucher.voucherNo(), new SealRequest("s-final"));

        // 缺少任一方签认：拒绝
        ApiException noSign = catchApi(() -> custodyService.adjust(voucher.voucherNo(),
                new AdjustmentRequest("adj-0", new BigDecimal("10100"), new BigDecimal("39.200000"),
                        true, false)));
        assertThat(noSign.getCode()).isEqualTo("SIGN_OFF_REQUIRED");

        // 双方签认的调整凭证：引用原凭证号，提交即生效
        VoucherResponse adjustment = custodyService.adjust(voucher.voucherNo(),
                new AdjustmentRequest("adj-1", new BigDecimal("10100"), new BigDecimal("39.200000"),
                        true, true));
        assertThat(adjustment.voucherType()).isEqualTo("ADJUSTMENT");
        assertThat(adjustment.originalVoucherNo()).isEqualTo(voucher.voucherNo());
        assertThat(adjustment.status()).isEqualTo("EFFECTIVE");
        // 10100 × 39.2 = 395920.00
        assertThat(adjustment.energy()).isEqualByComparingTo("395920.00");

        // 考核以调整凭证为准：偏差 15920，阈值 11400，超出 4520 × 0.5 = 2260
        AssessmentResponse assessment = custodyService.assessDay("ST01", GAS_DAY);
        assertThat(assessment.voucherNo()).isEqualTo(adjustment.voucherNo());
        assertThat(assessment.actualEnergy()).isEqualByComparingTo("395920.00");
        assertThat(assessment.exempt()).isFalse();
        assertThat(assessment.penalty()).isEqualByComparingTo("2260.00");

        // 原凭证仍为封账终态，未被修改
        assertThat(custodyService.getVoucher(voucher.voucherNo()).status()).isEqualTo("SEALED");
    }
}
