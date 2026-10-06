package com.example.starter.waste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 回执闭环：偏差恰 2% 视为一致、超过 2% 转异常核查、回执幂等与状态约束。
 */
class ReceiptServiceTest extends WasteLedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    /**
     * 登记净重 1000 的执行中联单。
     */
    private long givenInProgressManifest() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());
        return weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null))
                .manifestId();
    }

    @Test
    @DisplayName("回执偏差恰 2% 视为一致并联单闭环")
    void deviationExactlyTwoPercentConsistent() {
        long manifestId = givenInProgressManifest();

        ReceiptResponse response = receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1020")));

        assertEquals(0, new BigDecimal("0.020000").compareTo(response.deviationRatio()));
        assertEquals("CLOSED", response.manifestStatus());
        assertNull(response.exceptionId());
        assertEquals(0, countRows("exception_case"));
    }

    @Test
    @DisplayName("回执偏差低于 2% 一致闭环")
    void deviationBelowTwoPercentConsistent() {
        long manifestId = givenInProgressManifest();

        ReceiptResponse response = receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("980")));

        assertEquals("CLOSED", response.manifestStatus());
        assertNull(response.exceptionId());
    }

    @Test
    @DisplayName("回执偏差超过 2% 转异常核查")
    void deviationAboveTwoPercentRaisesException() {
        long manifestId = givenInProgressManifest();

        ReceiptResponse response = receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1021")));

        assertEquals(0, new BigDecimal("0.021000").compareTo(response.deviationRatio()));
        assertEquals("EXCEPTION", response.manifestStatus());
        assertNotNull(response.exceptionId());
        assertEquals(1, countRows("exception_case"));
        String status = jdbc.queryForObject("SELECT status FROM exception_case", String.class);
        assertEquals("OPEN", status);
    }

    @Test
    @DisplayName("回执后释放车辆执行中占位，可登记新联单")
    void vehicleReleasedAfterReceipt() {
        long manifestId = givenInProgressManifest();
        receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1000")));

        String plate = jdbc.queryForObject(
                "SELECT v.plate_no FROM vehicle v JOIN manifest m ON m.vehicle_id = v.id WHERE m.id = ?",
                String.class, manifestId);
        ManifestResponse second = weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9100", null));
        assertEquals("IN_PROGRESS", second.status());
    }

    @Test
    @DisplayName("回执同键同参幂等重放返回首次结果")
    void receiptIdempotentReplay() {
        long manifestId = givenInProgressManifest();
        ReceiptRequest request = new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1021"));

        ReceiptResponse first = receiptService.registerReceipt(manifestId, request);
        ReceiptResponse second = receiptService.registerReceipt(manifestId, request);

        assertEquals(first.receiptId(), second.receiptId());
        assertEquals(first.exceptionId(), second.exceptionId());
        assertEquals(1, countRows("receipt"));
        assertEquals(1, countRows("exception_case"));
    }

    @Test
    @DisplayName("回执同键异参返回 409")
    void receiptSameRequestIdDifferentParamsConflict() {
        long manifestId = givenInProgressManifest();
        String requestId = newRequestId("receipt");
        receiptService.registerReceipt(manifestId, new ReceiptRequest(requestId, new BigDecimal("1000")));

        ApiException e = assertThrows(ApiException.class, () -> receiptService.registerReceipt(manifestId,
                new ReceiptRequest(requestId, new BigDecimal("1001"))));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals(1, countRows("receipt"));
    }

    @Test
    @DisplayName("非执行中联单回执返回 409")
    void receiptOnClosedManifestRejected() {
        long manifestId = givenInProgressManifest();
        receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1000")));

        ApiException e = assertThrows(ApiException.class, () -> receiptService.registerReceipt(manifestId,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1000"))));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("MANIFEST_NOT_IN_PROGRESS", e.code());
    }

    @Test
    @DisplayName("联单不存在回执返回 404")
    void receiptOnMissingManifestRejected() {
        ApiException e = assertThrows(ApiException.class, () -> receiptService.registerReceipt(999999L,
                new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1000"))));

        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals("MANIFEST_NOT_FOUND", e.code());
    }
}
