package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;

/**
 * 进场称重与联单登记：称重边界、皮重 7 日复称（同事务）、越目录拒绝、幂等与同车并发唯一。
 */
class WeighingServiceTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Test
    void freshTareIsUsedAndNetWeightComputed() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant().minus(Duration.ofDays(2)));

        var result = weighingService.registerEntry(entryCommand("REQ-ENTRY-1", vehicleId,
                new BigDecimal("12500.000"), null));

        assertThat(result.netKg()).isEqualByComparingTo("4500.000");
        assertThat(result.tareKg()).isEqualByComparingTo("8000.000");
        assertThat(result.reweighed()).isFalse();
        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo("IN_TRANSIT");
        assertThat(manifestStatus(result.manifestId())).isEqualTo("IN_TRANSIT");
    }

    @Test
    void netWeightExactlyZeroIsRejected() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        assertThatThrownBy(() -> weighingService.registerEntry(entryCommand("REQ-ZERO", vehicleId,
                new BigDecimal("8000.000"), null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("NET_WEIGHT_ZERO");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(manifestCount(vehicleId)).isZero();
    }

    @Test
    void staleTareOlderThanSevenDaysRequiresReweigh() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant().minus(Duration.ofDays(8)));

        assertThatThrownBy(() -> weighingService.registerEntry(entryCommand("REQ-STALE", vehicleId,
                new BigDecimal("12000.000"), null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TARE_REWEIGH_REQUIRED"));
    }

    @Test
    void tareExactlySevenDaysOldIsStillValid() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant().minus(Duration.ofDays(7)));

        var result = weighingService.registerEntry(entryCommand("REQ-7D", vehicleId,
                new BigDecimal("12000.000"), null));

        assertThat(result.reweighed()).isFalse();
        assertThat(result.netKg()).isEqualByComparingTo("4000.000");
    }

    @Test
    void staleTareIsReweighedInSameTransactionAsManifest() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant().minus(Duration.ofDays(30)));

        var result = weighingService.registerEntry(entryCommand("REQ-REWEIGH", vehicleId,
                new BigDecimal("13000.000"), new BigDecimal("9000.000")));

        assertThat(result.reweighed()).isTrue();
        assertThat(result.tareKg()).isEqualByComparingTo("9000.000");
        assertThat(result.netKg()).isEqualByComparingTo("4000.000");
        assertThat(tareRecordCount(vehicleId)).isEqualTo(2);
    }

    @Test
    void reweighRollsBackWhenEntryRejectedSoTareRecordNotPersisted() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant().minus(Duration.ofDays(30)));

        // 复称皮重 9000，毛重同为 9000 -> 净重恰为 0 拒绝；复称记录须随事务回滚
        assertThatThrownBy(() -> weighingService.registerEntry(entryCommand("REQ-ROLLBACK", vehicleId,
                new BigDecimal("9000.000"), new BigDecimal("9000.000"))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NET_WEIGHT_ZERO"));

        assertThat(tareRecordCount(vehicleId)).isEqualTo(1);
        assertThat(manifestCount(vehicleId)).isZero();
    }

    @Test
    void destinationOutsideCatalogIsRejected() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        var unknown = new WeighingService.EntryCommand("REQ-FAC-1", plateOf(vehicleId), "示例小区",
                "KITCHEN", new BigDecimal("12000.000"), "F-NOT-EXIST", null);
        assertThatThrownBy(() -> weighingService.registerEntry(unknown))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));

        // 目录内但已停用的设施同样拒绝
        var inactive = new WeighingService.EntryCommand("REQ-FAC-2", plateOf(vehicleId), "示例小区",
                "KITCHEN", new BigDecimal("12000.000"), "F-HAZARDOUS", null);
        assertThatThrownBy(() -> weighingService.registerEntry(inactive))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));
    }

    @Test
    void sameRequestIdAndSameParamsReturnsFirstResult() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        var command = entryCommand("REQ-IDEM", vehicleId, new BigDecimal("12000.000"), null);

        var first = weighingService.registerEntry(command);
        var replay = weighingService.registerEntry(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.manifestId()).isEqualTo(first.manifestId());
        assertThat(replay.netKg()).isEqualByComparingTo(first.netKg());
        assertThat(manifestCount(vehicleId)).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsIsConflict() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        weighingService.registerEntry(entryCommand("REQ-IDEM-409", vehicleId, new BigDecimal("12000.000"), null));

        var tampered = entryCommand("REQ-IDEM-409", vehicleId, new BigDecimal("13000.000"), null);
        assertThatThrownBy(() -> weighingService.registerEntry(tampered))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void secondInFlightManifestForSameVehicleIsRejected() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        weighingService.registerEntry(entryCommand("REQ-OPEN-1", vehicleId, new BigDecimal("12000.000"), null));

        assertThatThrownBy(() -> weighingService.registerEntry(
                entryCommand("REQ-OPEN-2", vehicleId, new BigDecimal("13000.000"), null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VEHICLE_MANIFEST_IN_FLIGHT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(manifestCount(vehicleId)).isEqualTo(1);
    }

    @Test
    void concurrentEntriesForSameVehicleAllowExactlyOneInFlight() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String plateNo = plateOf(vehicleId);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    weighingService.registerEntry(entryCommand("REQ-CONC-" + index, vehicleId,
                            new BigDecimal("12000.000"), null));
                    return "OK";
                } catch (BizException e) {
                    return e.getCode();
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("VEHICLE_MANIFEST_IN_FLIGHT"::equals).hasSize(threads - 1);
        assertThat(manifestCount(vehicleId)).isEqualTo(1);
    }
}
