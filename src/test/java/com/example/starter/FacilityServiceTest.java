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
import com.example.starter.service.ReceiptService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;
import com.example.starter.support.TestClockConfig;

/**
 * 设施停收改版：expectedVersion 乐观并发（成功加 1、过期 409）、幂等重放、
 * UTC 停收区间 [start, end) 边界（首端停收、恰 end 恢复）与可接收品类验收。
 */
class FacilityServiceTest extends LedgerTestSupport {

    @Autowired
    private FacilityService facilityService;

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    private static final Instant SUSPEND_START = TestClockConfig.BASE_INSTANT.plus(Duration.ofHours(1));
    private static final Instant SUSPEND_END = TestClockConfig.BASE_INSTANT.plus(Duration.ofHours(3));

    private FacilityService.RevisionCommand suspendCommand(String requestId, int expectedVersion) {
        return new FacilityService.RevisionCommand(
                requestId, "F-KITCHEN", expectedVersion, null, null,
                new FacilityService.SuspensionWindow(SUSPEND_START, SUSPEND_END), false);
    }

    @Test
    void revisionSucceedsAndBumpsVersion() {
        var result = facilityService.revise(suspendCommand("REQ-FAC-REV-1", 1));

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.replayed()).isFalse();
        Integer version = jdbc.sql("SELECT version FROM facility WHERE code = 'F-KITCHEN'")
                .query(Integer.class).single();
        assertThat(version).isEqualTo(2);
    }

    @Test
    void staleExpectedVersionIsConflict() {
        facilityService.revise(suspendCommand("REQ-FAC-REV-2", 1));

        assertThatThrownBy(() -> facilityService.revise(suspendCommand("REQ-FAC-REV-3", 1)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FACILITY_VERSION_STALE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 失败不占键：同 requestId 以正确版本重试可成功
        var retried = facilityService.revise(suspendCommand("REQ-FAC-REV-3", 2));
        assertThat(retried.version()).isEqualTo(3);
    }

    @Test
    void revisionIsIdempotentAndConflictOnDifferentParams() {
        var first = facilityService.revise(suspendCommand("REQ-FAC-REV-4", 1));

        var replay = facilityService.revise(suspendCommand("REQ-FAC-REV-4", 1));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.version()).isEqualTo(first.version());

        var tampered = new FacilityService.RevisionCommand(
                "REQ-FAC-REV-4", "F-KITCHEN", 1, null, null,
                new FacilityService.SuspensionWindow(SUSPEND_START, SUSPEND_END.plus(Duration.ofHours(1))), false);
        assertThatThrownBy(() -> facilityService.revise(tampered))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidSuspensionWindowIsRejected() {
        var inverted = new FacilityService.RevisionCommand(
                "REQ-FAC-REV-5", "F-KITCHEN", 1, null, null,
                new FacilityService.SuspensionWindow(SUSPEND_END, SUSPEND_START), false);
        assertThatThrownBy(() -> facilityService.revise(inverted))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SUSPENSION_INVALID");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void entryRejectedAtSuspensionStartAndAllowedExactlyAtEnd() {
        facilityService.revise(suspendCommand("REQ-FAC-REV-6", 1));
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        // 恰为区间首端：停收
        clock.setInstant(SUSPEND_START);
        assertThatThrownBy(() -> weighingService.registerEntry(entryCommand("REQ-SUSP-1", vehicleId,
                new BigDecimal("12000.000"), null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FACILITY_SUSPENDED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // 区间中点：仍停收
        clock.setInstant(SUSPEND_START.plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> weighingService.registerEntry(entryCommand("REQ-SUSP-2", vehicleId,
                new BigDecimal("12000.000"), null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_SUSPENDED"));

        // 恰为 end：恢复收运
        clock.setInstant(SUSPEND_END);
        var result = weighingService.registerEntry(entryCommand("REQ-SUSP-3", vehicleId,
                new BigDecimal("12000.000"), null));
        assertThat(result.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void facilityWithoutSuspensionConfigWorksAsBefore() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        var result = weighingService.registerEntry(entryCommand("REQ-NOSUSP", vehicleId,
                new BigDecimal("12000.000"), null));
        assertThat(result.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void categoryNotAcceptedByFacilityIsRejected() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        // F-KITCHEN 仅接收 KITCHEN，OTHER 品类拒绝
        var command = new WeighingService.EntryCommand("REQ-CAT-1", plateOf(vehicleId), "示例小区",
                "OTHER", new BigDecimal("12000.000"), "F-KITCHEN", null);
        assertThatThrownBy(() -> weighingService.registerEntry(command))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FACILITY_CATEGORY_NOT_ACCEPTED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void categoryRevisionTakesEffectForNextAcceptanceCheck() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        // 改版：F-KITCHEN 增加接收 OTHER
        facilityService.revise(new FacilityService.RevisionCommand(
                "REQ-FAC-REV-7", "F-KITCHEN", 1, null, List.of("KITCHEN", "OTHER"), null, false));

        var command = new WeighingService.EntryCommand("REQ-CAT-2", plateOf(vehicleId), "示例小区",
                "OTHER", new BigDecimal("12000.000"), "F-KITCHEN", null);
        var result = weighingService.registerEntry(command);
        assertThat(result.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void clearSuspensionRestoresAcceptance() {
        facilityService.revise(suspendCommand("REQ-FAC-REV-8", 1));
        clock.setInstant(SUSPEND_START);

        facilityService.revise(new FacilityService.RevisionCommand(
                "REQ-FAC-REV-9", "F-KITCHEN", 2, null, null, null, true));

        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        var result = weighingService.registerEntry(entryCommand("REQ-CLEAR-1", vehicleId,
                new BigDecimal("12000.000"), null));
        assertThat(result.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void facilityRevisionDoesNotTouchClosedManifests() {
        // 联单先闭环（回执入账）
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        long manifestId = weighingService.registerEntry(entryCommand("REQ-CLOSED-KEEP", vehicleId,
                new BigDecimal("13000.000"), null)).manifestId();
        receiptService.registerReceipt(manifestId, "REQ-CLOSED-KEEP-R", new BigDecimal("5000.000"));
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");

        // 设施随后停收改版：已闭环联单状态与去向不受影响
        facilityService.revise(suspendCommand("REQ-FAC-REV-10", 1));

        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
        String destination = jdbc.sql("""
                SELECT f.code || '|' || m.destination_version FROM manifest m
                JOIN facility f ON f.id = m.facility_id WHERE m.id = :id
                """).param("id", manifestId).query(String.class).single();
        assertThat(destination).isEqualTo("F-KITCHEN|1");
    }
}
