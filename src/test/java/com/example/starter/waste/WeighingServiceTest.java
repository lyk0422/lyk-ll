package com.example.starter.waste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 进场称重登记：净重边界、设施目录、皮重 7 日复称与事务、幂等、一车一单。
 */
class WeighingServiceTest extends WasteLedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Test
    @DisplayName("净重恰等于 0 拒绝登记")
    void netWeightExactlyZeroRejected() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());

        ApiException e = assertThrows(ApiException.class, () -> weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "8000", null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals("NET_WEIGHT_INVALID", e.code());
        assertEquals(0, countRows("manifest"));
    }

    @Test
    @DisplayName("去向设施越目录拒绝登记")
    void facilityOutOfCatalogRejected() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());

        ApiException e = assertThrows(ApiException.class, () -> weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", "F-NOT-EXIST", "9000", null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals("FACILITY_NOT_IN_CATALOG", e.code());
        assertEquals(0, countRows("manifest"));
    }

    @Test
    @DisplayName("皮重超过 7 日未更新且未复称时拒绝登记")
    void staleTareWithoutReweighRejected() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now().minusDays(7).minusMinutes(1));

        ApiException e = assertThrows(ApiException.class, () -> weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals("TARE_REWEIGH_REQUIRED", e.code());
        assertEquals(0, countRows("manifest"));
    }

    @Test
    @DisplayName("皮重未超过 7 日时沿用最近一次空车称重")
    void freshTareWithinSevenDaysReused() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now().minusDays(7).plusMinutes(1));

        ManifestResponse response = weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null));

        assertEquals(0, new BigDecimal("8000").compareTo(response.tareKg()));
        assertEquals(0, new BigDecimal("1000").compareTo(response.netKg()));
        assertEquals("IN_PROGRESS", response.status());
    }

    @Test
    @DisplayName("皮重超期时复称与联单登记在同一事务内完成")
    void staleTareReweighedInSameTransaction() {
        String plate = newPlate();
        long vehicleId = givenVehicle(plate, "8000", LocalDateTime.now().minusDays(8));

        ManifestResponse response = weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9100", "8100"));

        assertEquals(0, new BigDecimal("8100").compareTo(response.tareKg()));
        assertEquals(0, new BigDecimal("1000").compareTo(response.netKg()));
        Long tareCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tare_record WHERE vehicle_id = ?", Long.class, vehicleId);
        assertEquals(2, tareCount);
        BigDecimal latestTare = jdbc.queryForObject(
                "SELECT tare_kg FROM tare_record WHERE vehicle_id = ? ORDER BY weighed_at DESC, id DESC LIMIT 1",
                BigDecimal.class, vehicleId);
        assertEquals(0, new BigDecimal("8100.000").compareTo(latestTare));
    }

    @Test
    @DisplayName("复称后联单登记失败时皮重记录随事务回滚")
    void reweighRolledBackWhenManifestFails() {
        String plate = newPlate();
        long vehicleId = givenVehicle(plate, "8000", LocalDateTime.now().minusDays(8));

        ApiException e = assertThrows(ApiException.class, () -> weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", "F-NOT-EXIST", "9100", "8100")));

        assertEquals("FACILITY_NOT_IN_CATALOG", e.code());
        Long tareCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tare_record WHERE vehicle_id = ?", Long.class, vehicleId);
        assertEquals(1, tareCount);
        assertEquals(0, countRows("manifest"));
    }

    @Test
    @DisplayName("同键同参幂等重放返回首次结果")
    void idempotentReplayReturnsFirstResult() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());
        EntryWeighingRequest request = entryRequest(
                newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null);

        ManifestResponse first = weighingService.registerEntry(request);
        ManifestResponse second = weighingService.registerEntry(request);

        assertEquals(first.manifestId(), second.manifestId());
        assertEquals(1, countRows("manifest"));
    }

    @Test
    @DisplayName("同键异参返回 409")
    void sameRequestIdDifferentParamsConflict() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());
        String requestId = newRequestId("entry");
        weighingService.registerEntry(entryRequest(requestId, plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null));

        ApiException e = assertThrows(ApiException.class, () -> weighingService.registerEntry(
                entryRequest(requestId, plate, "厨余垃圾", FACILITY_KITCHEN, "9500", null)));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals(1, countRows("manifest"));
    }

    @Test
    @DisplayName("同一车辆存在执行中联单时再次登记返回 409")
    void vehicleBusyWhileManifestInProgress() {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());
        weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null));

        ApiException e = assertThrows(ApiException.class, () -> weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, "可回收物", FACILITY_KITCHEN, "9200", null)));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("VEHICLE_BUSY", e.code());
        assertEquals(1, countRows("manifest"));
    }
}
