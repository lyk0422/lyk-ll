package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.starter.domain.ConflictException;
import com.example.starter.web.dto.AdjustmentRequest;
import com.example.starter.web.dto.ConfirmRequest;
import com.example.starter.web.dto.DisputeRequest;
import com.example.starter.web.dto.VoucherResponse;

/**
 * 争议期边界（第 7 日受理、次日逾期封账）、封账终态、调整凭证、封账后拒绝修改。
 */
class DisputeAndSealH2Test extends AbstractH2IntegrationTest {

    /** 生效于固定时钟 2026-09-11，争议截止 2026-09-18。 */
    private VoucherResponse effective() {
        return effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");
    }

    @Test
    void disputeOnExactlySeventhDayIsAccepted() {
        VoucherResponse voucher = effective();
        // 拨到截止日当天（第 7 个自然日）2026-09-18，仍受理
        clock.setInstant(Instant.parse("2026-09-18T23:59:00Z"));

        var outcome = service.dispute(new DisputeRequest("req-dispute-1", voucher.voucherNo(),
                "A", "热值存疑"));

        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.body().status()).isEqualTo("DISPUTED");
        assertThat(statusOf(voucher.voucherNo())).isEqualTo("DISPUTED");
    }

    @Test
    void disputeOneDayAfterDeadlineIsRejectedAndVoucherSealed() {
        VoucherResponse voucher = effective();
        // 拨到截止次日 2026-09-19：逾期，拒绝争议并在同事务封账
        clock.setInstant(Instant.parse("2026-09-19T00:00:00Z"));

        var outcome = service.dispute(new DisputeRequest("req-dispute-late",
                voucher.voucherNo(), "A", "迟到的争议"));

        assertThat(outcome.httpStatus()).isEqualTo(409);
        assertThat(outcome.body().status()).isEqualTo("SEALED");
        assertThat(statusOf(voucher.voucherNo())).isEqualTo("SEALED");
        assertThat(count("SELECT COUNT(*) FROM dispute")).isEqualTo(0);
    }

    @Test
    void sealBeforeDeadlineIsRejected() {
        VoucherResponse voucher = effective();
        // 仍在争议期内（2026-09-15）
        clock.setInstant(Instant.parse("2026-09-15T00:00:00Z"));

        assertThatThrownBy(() -> service.seal(voucher.voucherNo(), "req-seal-early"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("争议期内");
        assertThat(statusOf(voucher.voucherNo())).isEqualTo("EFFECTIVE");
    }

    @Test
    void sealAfterDeadlineSucceedsAndRepeatedSealIsIdempotent() {
        VoucherResponse voucher = effective();
        clock.setInstant(Instant.parse("2026-09-19T00:00:00Z"));

        var sealed = service.seal(voucher.voucherNo(), "req-seal-1");
        assertThat(sealed.body().status()).isEqualTo("SEALED");
        assertThat(sealed.body().sealedAt()).isNotNull();

        // 重复封账幂等成功，返回同一终态
        var again = service.seal(voucher.voucherNo(), "req-seal-1");
        assertThat(again.httpStatus()).isEqualTo(200);
        assertThat(again.body().status()).isEqualTo("SEALED");
        assertThat(again.body().sealedAt()).isEqualTo(sealed.body().sealedAt());
    }

    @Test
    void modificationAfterSealIsRejected() {
        VoucherResponse voucher = effective();
        clock.setInstant(Instant.parse("2026-09-19T00:00:00Z"));
        service.seal(voucher.voucherNo(), "req-seal-1");

        // 封账后确认被拒绝
        assertThatThrownBy(() -> service.confirm(
                new ConfirmRequest("req-conf-x", voucher.voucherNo(), "A", "王五")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封账");
        // 封账后争议被拒绝
        clock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        assertThatThrownBy(() -> service.dispute(new DisputeRequest("req-dispute-x",
                voucher.voucherNo(), "B", "封账后争议")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封账");
    }

    @Test
    void sealedVoucherErrorHandledBySignedAdjustmentReferencingOriginal() {
        VoucherResponse original = effective();
        clock.setInstant(Instant.parse("2026-09-19T00:00:00Z"));
        service.seal(original.voucherNo(), "req-seal-1");

        // 双方签认调整凭证：体积 210，热值 40 → 能量 8400.00，引用原凭证号
        var outcome = service.adjustment(new AdjustmentRequest("req-adj-1",
                original.voucherNo(), "张三", "李四", new BigDecimal("210.000"),
                new BigDecimal("40.0000"), "表底系数差错，双方签认调整"));

        assertThat(outcome.httpStatus()).isEqualTo(201);
        VoucherResponse adjustment = outcome.body();
        assertThat(adjustment.voucherType()).isEqualTo("ADJUSTMENT");
        assertThat(adjustment.originalVoucherNo()).isEqualTo(original.voucherNo());
        assertThat(adjustment.energyMj()).isEqualByComparingTo("8400.00");
        assertThat(adjustment.status()).isEqualTo("EFFECTIVE");
        // 原凭证进入 ADJUSTED 终态
        assertThat(statusOf(original.voucherNo())).isEqualTo("ADJUSTED");
    }

    @Test
    void adjustmentRejectedWhenVoucherNotSealed() {
        VoucherResponse voucher = effective();
        // 未封账、在争议期内，不能直接调整
        assertThatThrownBy(() -> service.adjustment(new AdjustmentRequest("req-adj-x",
                voucher.voucherNo(), "张三", "李四", new BigDecimal("210"),
                new BigDecimal("40"), "提前调整")))
                .isInstanceOf(ConflictException.class);
        assertThat(voucherNos()).hasSize(1);
    }

    @Test
    void disputeOnDraftVoucherIsRejected() {
        createStation();
        seedDayReadings();
        VoucherResponse draft = submit("req-submit-1");

        assertThatThrownBy(() -> service.dispute(new DisputeRequest("req-draft-dispute",
                draft.voucherNo(), "A", "未生效争议")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("尚未经双方确认生效");
    }
}
