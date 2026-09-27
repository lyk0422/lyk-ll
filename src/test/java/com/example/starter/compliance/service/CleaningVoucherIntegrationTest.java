package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 清线凭证确认 H2 测试：双人确认、固定线路/时刻/占用版本、2 小时有效期边界（恰满失效）、
 * 后续量产作废、requestId 幂等与结构化指纹。
 */
class CleaningVoucherIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-10T08:00:00Z");

    @Autowired
    private ComplianceService service;

    @Test
    void confirmVoucherPinsLineTimeAndOccupancyVersion() {
        service.registerLine(Fixtures.line("LINE-A"));
        setInstant(T0);

        Results.VoucherView view = service.confirmCleaning(Fixtures.cleaning(
                "LINE-A", List.of("MILK", "EGG"), T0.toEpochMilli() - 60_000, 1,
                "operator-1", "reviewer-1")).body();

        assertThat(view.voucherId()).isNotNull();
        assertThat(view.lineCode()).isEqualTo("LINE-A");
        assertThat(view.allergens()).containsExactly("EGG", "MILK");
        assertThat(view.cleanedAt()).isEqualTo(T0.toEpochMilli() - 60_000);
        assertThat(view.confirmedAt()).isEqualTo(T0.toEpochMilli());
        assertThat(view.validUntil())
                .isEqualTo(T0.toEpochMilli() + ComplianceService.VOUCHER_VALIDITY_MILLIS);
        assertThat(view.occupancyVersion()).isEqualTo(1);
        assertThat(view.status()).isEqualTo("ISSUED");
        assertThat(view.effectiveStatus()).isEqualTo("ISSUED");

        Results.VoucherView queried = service.getVoucher(view.voucherId());
        assertThat(queried.effectiveStatus()).isEqualTo("ISSUED");
    }

    @Test
    void samePersonAsOperatorAndReviewerRejected422() {
        service.registerLine(Fixtures.line("LINE-B"));
        setInstant(T0);
        assertThatThrownBy(() -> service.confirmCleaning(Fixtures.cleaning(
                "LINE-B", List.of("MILK"), T0.toEpochMilli(), 1, "same-person", "same-person")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("CONFIRMER_NOT_DISTINCT");
                });
    }

    @Test
    void occupancyVersionMismatchConflicts409() {
        service.registerLine(Fixtures.line("LINE-C"));
        setInstant(T0);
        assertThatThrownBy(() -> service.confirmCleaning(Fixtures.cleaning(
                "LINE-C", List.of("MILK"), T0.toEpochMilli(), 7, "op", "rev")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("OCCUPANCY_VERSION_CONFLICT");
                });
    }

    @Test
    void invalidVoucherParamsRejected() {
        service.registerLine(Fixtures.line("LINE-D"));
        setInstant(T0);
        // 线路不存在 404。
        assertThatThrownBy(() -> service.confirmCleaning(Fixtures.cleaning(
                "LINE-MISSING", List.of("MILK"), T0.toEpochMilli(), 1, "op", "rev")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("LINE_NOT_FOUND");
                });
        // 空覆盖集合 400。
        assertThatThrownBy(() -> service.confirmCleaning(Fixtures.cleaning(
                "LINE-D", List.of(), T0.toEpochMilli(), 1, "op", "rev")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        // 清线时刻缺失 400。
        assertThatThrownBy(() -> service.confirmCleaning(
                new com.example.starter.compliance.web.Requests.ConfirmCleaningRequest(
                        Fixtures.requestId(), "LINE-D", List.of("MILK"), null, 1, "op", "rev")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        // 清线时刻晚于确认时刻 400。
        assertThatThrownBy(() -> service.confirmCleaning(Fixtures.cleaning(
                "LINE-D", List.of("MILK"), T0.toEpochMilli() + 1_000, 1, "op", "rev")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void voucherExpiresExactlyAfterTwoHours() {
        service.registerLine(Fixtures.line("LINE-E"));
        setInstant(T0);
        long voucherId = service.confirmCleaning(Fixtures.cleaning(
                "LINE-E", List.of("MILK"), T0.toEpochMilli(), 1, "op", "rev"))
                .body().voucherId();

        // 恰满 2 小时前 1 毫秒仍有效。
        setInstant(T0.plusMillis(ComplianceService.VOUCHER_VALIDITY_MILLIS - 1));
        assertThat(service.getVoucher(voucherId).effectiveStatus()).isEqualTo("ISSUED");

        // 恰满 2 小时即失效。
        setInstant(T0.plusMillis(ComplianceService.VOUCHER_VALIDITY_MILLIS));
        assertThat(service.getVoucher(voucherId).effectiveStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void idempotentReplayAndStructuredFingerprint() {
        service.registerLine(Fixtures.line("LINE-F"));
        setInstant(T0);
        String requestId = Fixtures.requestId();

        Results.VoucherView first = service.confirmCleaning(Fixtures.cleaning(
                requestId, "LINE-F", List.of("MILK"), T0.toEpochMilli(), 1, "op", "rev")).body();
        Results.VoucherView replay = service.confirmCleaning(Fixtures.cleaning(
                requestId, "LINE-F", List.of("MILK"), T0.toEpochMilli(), 1, "op", "rev")).body();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.voucherId()).isEqualTo(first.voucherId());

        // 结构化指纹：合法文本含分隔符也须区分——"a,b"/"c" 与 "a"/"b,c" 是不同参数。
        String separatorKey = Fixtures.requestId();
        service.confirmCleaning(Fixtures.cleaning(
                separatorKey, "LINE-F", List.of("EGG"), T0.toEpochMilli(), 1, "a,b", "c"));
        assertThatThrownBy(() -> service.confirmCleaning(Fixtures.cleaning(
                separatorKey, "LINE-F", List.of("EGG"), T0.toEpochMilli(), 1, "a", "b,c")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("REQUEST_PARAM_CONFLICT");
                });
    }
}
