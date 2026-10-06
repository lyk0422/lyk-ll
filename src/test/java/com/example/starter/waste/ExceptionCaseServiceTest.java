package com.example.starter.waste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 异常核查结论：设备误差与装载差异两类结论留痕、结论幂等。
 */
class ExceptionCaseServiceTest extends WasteLedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private ExceptionCaseService exceptionCaseService;

    /**
     * 造一笔偏差超 2% 的回执，返回异常核查单主键。
     */
    private long givenOpenException() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());
        long manifestId = weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null))
                .manifestId();
        return receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1100")))
                .exceptionId();
    }

    @Test
    @DisplayName("设备误差结论留痕")
    void concludeDeviceErrorTraced() {
        long exceptionId = givenOpenException();

        ExceptionCaseResponse response = exceptionCaseService.conclude(exceptionId,
                new ConclusionRequest(newRequestId("conclusion"), ConclusionType.DEVICE_ERROR, "地磅传感器漂移"));

        assertEquals("RESOLVED", response.status());
        assertEquals("DEVICE_ERROR", response.conclusionType());
        assertEquals("地磅传感器漂移", response.conclusionNote());
        assertNotNull(response.resolvedAt());

        String tracedType = jdbc.queryForObject(
                "SELECT conclusion_type FROM exception_case WHERE id = ?", String.class, exceptionId);
        assertEquals("DEVICE_ERROR", tracedType);
    }

    @Test
    @DisplayName("装载差异结论留痕")
    void concludeLoadingDiffTraced() {
        long exceptionId = givenOpenException();

        ExceptionCaseResponse response = exceptionCaseService.conclude(exceptionId,
                new ConclusionRequest(newRequestId("conclusion"), ConclusionType.LOADING_DIFF, "途中装卸损耗"));

        assertEquals("RESOLVED", response.status());
        assertEquals("LOADING_DIFF", response.conclusionType());
        assertNotNull(response.resolvedAt());
    }

    @Test
    @DisplayName("结论同键同参幂等重放返回首次结果")
    void conclusionIdempotentReplay() {
        long exceptionId = givenOpenException();
        ConclusionRequest request = new ConclusionRequest(
                newRequestId("conclusion"), ConclusionType.DEVICE_ERROR, "地磅传感器漂移");

        ExceptionCaseResponse first = exceptionCaseService.conclude(exceptionId, request);
        ExceptionCaseResponse second = exceptionCaseService.conclude(exceptionId, request);

        assertEquals(first.exceptionId(), second.exceptionId());
        assertEquals(first.resolvedAt(), second.resolvedAt());
    }

    @Test
    @DisplayName("结论同键异参返回 409")
    void conclusionSameRequestIdDifferentParamsConflict() {
        long exceptionId = givenOpenException();
        String requestId = newRequestId("conclusion");
        exceptionCaseService.conclude(exceptionId,
                new ConclusionRequest(requestId, ConclusionType.DEVICE_ERROR, "地磅传感器漂移"));

        ApiException e = assertThrows(ApiException.class, () -> exceptionCaseService.conclude(exceptionId,
                new ConclusionRequest(requestId, ConclusionType.LOADING_DIFF, "途中装卸损耗")));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
    }

    @Test
    @DisplayName("已结论核查单用新请求键重复结论返回 409")
    void concludeResolvedCaseWithNewRequestIdConflict() {
        long exceptionId = givenOpenException();
        exceptionCaseService.conclude(exceptionId,
                new ConclusionRequest(newRequestId("conclusion"), ConclusionType.DEVICE_ERROR, "地磅传感器漂移"));

        ApiException e = assertThrows(ApiException.class, () -> exceptionCaseService.conclude(exceptionId,
                new ConclusionRequest(newRequestId("conclusion"), ConclusionType.LOADING_DIFF, "途中装卸损耗")));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
    }

    @Test
    @DisplayName("核查单不存在返回 404")
    void concludeMissingExceptionRejected() {
        ApiException e = assertThrows(ApiException.class, () -> exceptionCaseService.conclude(999999L,
                new ConclusionRequest(newRequestId("conclusion"), ConclusionType.DEVICE_ERROR, "说明")));

        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals("EXCEPTION_NOT_FOUND", e.code());
    }
}
