package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.service.FacilityService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;
import com.example.starter.support.TestClockConfig;

/**
 * 设施目录维护：版本化修改（expectedVersion 过期 409）、幂等、停收区间 [start, end) 边界与品类校验。
 */
class FacilityServiceTest extends LedgerTestSupport {

    private static final Instant SUSPEND_START = TestClockConfig.BASE_INSTANT.plus(Duration.ofHours(1));
    private static final Instant SUSPEND_END = TestClockConfig.BASE_INSTANT.plus(Duration.ofHours(2));

    @Autowired
    private FacilityService facilityService;

    @Autowired
    private WeighingService weighingService;

    /**
     * 将 F-KITCHEN 改为接收 KITCHEN/OTHER，并配置停收区间 [基准+1h, 基准+2h)。
     */
    private void suspendKitchenFacility(String requestId, int expectedVersion) {
        facilityService.update("F-KITCHEN", requestId, expectedVersion,
                List.of("KITCHEN", "OTHER"),
                List.of(new FacilityService.SuspensionView(SUSPEND_START, SUSPEND_END)));
    }

    @Test
    void updateBumpsVersionAndReplacesConfig() {
        var result = facilityService.update("F-KITCHEN", "REQ-FAC-1", 1,
                List.of("KITCHEN", "OTHER"),
                List.of(new FacilityService.SuspensionView(SUSPEND_START, SUSPEND_END)));

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.replayed()).isFalse();
        assertThat(result.categories()).containsExactly("KITCHEN", "OTHER");
        assertThat(result.suspensions()).hasSize(1);

        var view = facilityService.detail("F-KITCHEN");
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.categories()).containsExactly("KITCHEN", "OTHER");
        assertThat(view.suspensions().get(0).startAt()).isEqualTo(SUSPEND_START);
        assertThat(view.suspensions().get(0).endAt()).isEqualTo(SUSPEND_END);
    }

    @Test
    void staleExpectedVersionIsConflict() {
        suspendKitchenFacility("REQ-FAC-V1", 1);

        assertThatThrownBy(() -> suspendKitchenFacility("REQ-FAC-V2", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FACILITY_VERSION_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 版本未被过期请求推进
        assertThat(facilityService.detail("F-KITCHEN").version()).isEqualTo(2);
    }

    @Test
    void updateIsIdempotentByRequestId() {
        suspendKitchenFacility("REQ-FAC-IDEM", 1);

        var replay = facilityService.update("F-KITCHEN", "REQ-FAC-IDEM", 1,
                List.of("KITCHEN", "OTHER"),
                List.of(new FacilityService.SuspensionView(SUSPEND_START, SUSPEND_END)));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.version()).isEqualTo(2);
        assertThat(facilityService.detail("F-KITCHEN").version()).isEqualTo(2);
    }

    @Test
    void updateSameRequestIdDifferentParamsIsConflict() {
        suspendKitchenFacility("REQ-FAC-409", 1);

        assertThatThrownBy(() -> facilityService.update("F-KITCHEN", "REQ-FAC-409", 1,
                        List.of("KITCHEN"), List.of()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void invalidSuspensionIntervalIsRejectedAndDoesNotOccupyKey() {
        assertThatThrownBy(() -> facilityService.update("F-KITCHEN", "REQ-FAC-BAD", 1,
                        List.of("KITCHEN"),
                        List.of(new FacilityService.SuspensionView(SUSPEND_END, SUSPEND_START))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SUSPENSION_INTERVAL_INVALID");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        // 失败不占键：同 requestId 以合法参数可成功
        var result = facilityService.update("F-KITCHEN", "REQ-FAC-BAD", 1,
                List.of("KITCHEN"),
                List.of(new FacilityService.SuspensionView(SUSPEND_START, SUSPEND_END)));
        assertThat(result.version()).isEqualTo(2);
    }

    @Test
    void unknownCategoryIsRejected() {
        assertThatThrownBy(() -> facilityService.update("F-KITCHEN", "REQ-FAC-CAT", 1,
                        List.of("NUCLEAR"), List.of()))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("CATEGORY_INVALID"));
    }

    @Test
    void missingFacilityIsNotFound() {
        assertThatThrownBy(() -> facilityService.update("F-NOPE", "REQ-FAC-404", 1,
                        List.of("KITCHEN"), List.of()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FACILITY_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void entryRejectsCategoryNotAcceptedByFacility() {
        facilityService.update("F-KITCHEN", "REQ-FAC-CATONLY", 1, List.of("OTHER"), List.of());
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        assertThatThrownBy(() -> weighingService.registerEntry(
                        entryCommand("REQ-ENTRY-CAT", vehicleId, new BigDecimal("13000.000"), null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FACILITY_CATEGORY_REJECTED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(manifestCount(vehicleId)).isZero();
    }

    @Test
    void entrySuspendedAtIntervalStartAndResumedExactlyAtEnd() {
        suspendKitchenFacility("REQ-FAC-SUSP", 1);

        // 区间首端（含）：停收
        clock.setInstant(SUSPEND_START);
        long firstVehicle = createVehicle();
        insertTare(firstVehicle, new BigDecimal("8000.000"), clock.instant());
        assertThatThrownBy(() -> weighingService.registerEntry(
                        entryCommand("REQ-ENTRY-S1", firstVehicle, new BigDecimal("13000.000"), null)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_SUSPENDED"));

        // 区间内：停收
        clock.setInstant(SUSPEND_START.plus(Duration.ofMinutes(30)));
        long secondVehicle = createVehicle();
        insertTare(secondVehicle, new BigDecimal("8000.000"), clock.instant());
        assertThatThrownBy(() -> weighingService.registerEntry(
                        entryCommand("REQ-ENTRY-S2", secondVehicle, new BigDecimal("13000.000"), null)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_SUSPENDED"));

        // 恰为 end（不含）：恢复接收
        clock.setInstant(SUSPEND_END);
        long thirdVehicle = createVehicle();
        insertTare(thirdVehicle, new BigDecimal("8000.000"), clock.instant());
        var result = weighingService.registerEntry(
                entryCommand("REQ-ENTRY-S3", thirdVehicle, new BigDecimal("13000.000"), null));
        assertThat(result.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void facilityWithoutSuspensionConfigBehavesAsBefore() {
        // F-RECYCLING 无停收配置：任何时刻均可登记
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        var command = new WeighingService.EntryCommand(
                "REQ-ENTRY-NOSUSP", plateOf(vehicleId), "示例小区", "RECYCLABLE",
                new BigDecimal("13000.000"), "F-RECYCLING", null);

        var result = weighingService.registerEntry(command);

        assertThat(result.status()).isEqualTo("IN_TRANSIT");
        assertThat(result.netKg()).isEqualByComparingTo("5000.000");
    }
}
