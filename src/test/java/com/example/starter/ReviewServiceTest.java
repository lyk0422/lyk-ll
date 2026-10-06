package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.service.ReceiptService;
import com.example.starter.service.ReviewService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;

/**
 * 异常核查：结论分设备误差与装载差异两类并留痕；仅异常联单可核查；幂等重放与冲突。
 */
class ReviewServiceTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private ReviewService reviewService;

    /**
     * 登记一张进场净重 5000kg 的联单，并以 2.02% 偏差回执转入异常。
     */
    private long registerExceptionManifest(String suffix) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        long manifestId = weighingService.registerEntry(entryCommand("REQ-RV-ENTRY-" + suffix, vehicleId,
                new BigDecimal("13000.000"), null)).manifestId();
        receiptService.registerReceipt(manifestId, "REQ-RV-RECEIPT-" + suffix, new BigDecimal("5101.000"));
        return manifestId;
    }

    @Test
    void deviceErrorConclusionResolvesManifestAndPersistsTrace() {
        long manifestId = registerExceptionManifest("DEV");

        var result = reviewService.review(manifestId, "REQ-RV-DEV", "DEVICE_ERROR", "地磅传感器漂移已校准");

        assertThat(result.conclusion()).isEqualTo("DEVICE_ERROR");
        assertThat(result.note()).isEqualTo("地磅传感器漂移已校准");
        assertThat(result.replayed()).isFalse();
        assertThat(manifestStatus(manifestId)).isEqualTo("RESOLVED");

        var row = jdbc.sql("SELECT conclusion, note, reviewed_at FROM exception_review WHERE manifest_id = :id")
                .param("id", manifestId)
                .query((rs, i) -> rs.getString("conclusion") + "|" + rs.getString("note")
                        + "|" + (rs.getTimestamp("reviewed_at") != null))
                .single();
        assertThat(row).isEqualTo("DEVICE_ERROR|地磅传感器漂移已校准|true");
    }

    @Test
    void loadingDiffConclusionIsAccepted() {
        long manifestId = registerExceptionManifest("LOAD");

        var result = reviewService.review(manifestId, "REQ-RV-LOAD", "LOADING_DIFF", null);

        assertThat(result.conclusion()).isEqualTo("LOADING_DIFF");
        assertThat(manifestStatus(manifestId)).isEqualTo("RESOLVED");
    }

    @Test
    void invalidConclusionIsRejected() {
        long manifestId = registerExceptionManifest("BAD");

        assertThatThrownBy(() -> reviewService.review(manifestId, "REQ-RV-BAD", "SOMETHING_ELSE", null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CONCLUSION_INVALID");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(manifestStatus(manifestId)).isEqualTo("EXCEPTION");
    }

    @Test
    void reviewOnNonExceptionManifestIsRejected() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        long manifestId = weighingService.registerEntry(entryCommand("REQ-RV-OPEN", vehicleId,
                new BigDecimal("13000.000"), null)).manifestId();

        assertThatThrownBy(() -> reviewService.review(manifestId, "REQ-RV-OPEN-R", "DEVICE_ERROR", null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MANIFEST_NOT_IN_EXCEPTION");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void reviewIsIdempotentAndConflictOnDifferentParams() {
        long manifestId = registerExceptionManifest("IDEM");
        var first = reviewService.review(manifestId, "REQ-RV-IDEM", "DEVICE_ERROR", "首次结论");

        var replay = reviewService.review(manifestId, "REQ-RV-IDEM", "DEVICE_ERROR", "首次结论");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reviewId()).isEqualTo(first.reviewId());

        assertThatThrownBy(() -> reviewService.review(manifestId, "REQ-RV-IDEM", "LOADING_DIFF", "篡改"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
}
