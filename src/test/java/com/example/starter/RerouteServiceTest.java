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

import com.example.starter.service.ManifestService;
import com.example.starter.service.ReceiptService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;

/**
 * 在途改投：去向版本链、改投校验规则、幂等与并发改投/回执互斥。
 */
class RerouteServiceTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ManifestService manifestService;

    @Autowired
    private ReceiptService receiptService;

    /**
     * 登记一张进场净重 10000kg 的执行中联单（毛重 18000 - 皮重 8000），去向 F-INCINERATION。
     */
    private long registerInTransitManifest(String requestId) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        return weighingService.registerEntry(new WeighingService.EntryCommand(
                requestId, plateOf(vehicleId), "示例小区", "OTHER",
                new BigDecimal("18000.000"), "F-INCINERATION", null)).manifestId();
    }

    @Test
    void rerouteCreatesNewDestinationVersionAndKeepsWeighing() {
        long manifestId = registerInTransitManifest("REQ-RR-ENTRY");

        var result = manifestService.reroute(manifestId, "REQ-RR-1", "F-INCINERATION-2", 1);

        assertThat(result.destinationVersion()).isEqualTo(2);
        assertThat(result.facilityCode()).isEqualTo("F-INCINERATION-2");
        assertThat(result.replayed()).isFalse();

        var detail = manifestService.detail(manifestId);
        assertThat(detail.destinationVersion()).isEqualTo(2);
        assertThat(detail.currentFacilityCode()).isEqualTo("F-INCINERATION-2");
        assertThat(detail.status()).isEqualTo("IN_TRANSIT");
        // 原称重、小区、品类与净重不变，不重新称重
        assertThat(detail.netKg()).isEqualByComparingTo("10000.000");
        assertThat(detail.category()).isEqualTo("OTHER");
        assertThat(detail.community()).isEqualTo("示例小区");
        assertThat(detail.destinations()).hasSize(2);
        assertThat(detail.destinations().get(0).version()).isEqualTo(1);
        assertThat(detail.destinations().get(0).facilityCode()).isEqualTo("F-INCINERATION");
        assertThat(detail.destinations().get(1).version()).isEqualTo(2);
        assertThat(detail.destinations().get(1).facilityCode()).isEqualTo("F-INCINERATION-2");
        assertThat(detail.destinations().get(1).requestId()).isEqualTo("REQ-RR-1");
    }

    @Test
    void rerouteToSameFacilityIsRejected() {
        long manifestId = registerInTransitManifest("REQ-RR-SAME-ENTRY");

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-SAME", "F-INCINERATION", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REROUTE_SAME_FACILITY");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void rerouteToFacilityOutsideCatalogIsRejected() {
        long manifestId = registerInTransitManifest("REQ-RR-NOCAT-ENTRY");

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-NOCAT", "F-NOPE", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));
        // 停用设施同样视为目录外
        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-INACTIVE", "F-HAZARDOUS", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));
    }

    @Test
    void rerouteToSuspendedFacilityIsRejected() {
        long manifestId = registerInTransitManifest("REQ-RR-SUSP-ENTRY");
        jdbc.sql("INSERT INTO facility_suspension (facility_id, start_at, end_at)"
                        + " SELECT id, :startAt, :endAt FROM facility WHERE code = 'F-INCINERATION-2'")
                .param("startAt", java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(1))))
                .param("endAt", java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(1))))
                .update();

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-SUSP", "F-INCINERATION-2", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_SUSPENDED"));
        assertThat(manifestService.detail(manifestId).destinationVersion()).isEqualTo(1);
    }

    @Test
    void rerouteToFacilityNotAcceptingCategoryIsRejected() {
        long manifestId = registerInTransitManifest("REQ-RR-CAT-ENTRY");
        // F-INCINERATION-2 只收 RECYCLABLE，不接收联单的 OTHER
        jdbc.sql("INSERT INTO facility_category (facility_id, category)"
                        + " SELECT id, 'RECYCLABLE' FROM facility WHERE code = 'F-INCINERATION-2'")
                .update();

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-CAT", "F-INCINERATION-2", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_CATEGORY_REJECTED"));
    }

    @Test
    void rerouteWithStaleExpectedVersionIsConflict() {
        long manifestId = registerInTransitManifest("REQ-RR-V-ENTRY");
        manifestService.reroute(manifestId, "REQ-RR-V1", "F-INCINERATION-2", 1);

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-V2", "F-RECYCLING", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(manifestService.detail(manifestId).destinationVersion()).isEqualTo(2);
    }

    @Test
    void rerouteOnClosedManifestIsRejected() {
        long manifestId = registerInTransitManifest("REQ-RR-CLOSED-ENTRY");
        receiptService.registerReceipt(manifestId, "REQ-RR-CLOSED-R", new BigDecimal("10000.000"));

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-CLOSED", "F-INCINERATION-2", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MANIFEST_NOT_IN_TRANSIT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void rerouteOnMissingManifestIsNotFound() {
        assertThatThrownBy(() -> manifestService.reroute(999999L, "REQ-RR-404", "F-INCINERATION-2", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MANIFEST_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void rerouteIsIdempotentByRequestId() {
        long manifestId = registerInTransitManifest("REQ-RR-IDEM-ENTRY");
        var first = manifestService.reroute(manifestId, "REQ-RR-IDEM", "F-INCINERATION-2", 1);

        var replay = manifestService.reroute(manifestId, "REQ-RR-IDEM", "F-INCINERATION-2", 1);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.destinationVersion()).isEqualTo(first.destinationVersion());
        assertThat(manifestService.detail(manifestId).destinationVersion()).isEqualTo(2);
    }

    @Test
    void rerouteSameRequestIdDifferentParamsIsConflict() {
        long manifestId = registerInTransitManifest("REQ-RR-409-ENTRY");
        manifestService.reroute(manifestId, "REQ-RR-409", "F-INCINERATION-2", 1);

        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-409", "F-RECYCLING", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void failedRerouteDoesNotOccupyRequestId() {
        long manifestId = registerInTransitManifest("REQ-RR-FAIL-ENTRY");
        assertThatThrownBy(() -> manifestService.reroute(manifestId, "REQ-RR-FAIL", "F-INCINERATION", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("REROUTE_SAME_FACILITY"));

        // 失败不占键：同 requestId 改投合法设施可成功
        var result = manifestService.reroute(manifestId, "REQ-RR-FAIL", "F-INCINERATION-2", 1);
        assertThat(result.destinationVersion()).isEqualTo(2);
    }

    @Test
    void concurrentRerouteAndReceiptAreMutuallyExclusive() throws Exception {
        long manifestId = registerInTransitManifest("REQ-RR-RACE-ENTRY");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> receiptFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                receiptService.registerReceipt(manifestId, "REQ-RR-RACE-R",
                        new BigDecimal("10000.000"), "F-INCINERATION", 1);
                return "RECEIPT_OK";
            } catch (BizException e) {
                return e.getCode();
            }
        });
        Future<String> rerouteFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                manifestService.reroute(manifestId, "REQ-RR-RACE-RR", "F-INCINERATION-2", 1);
                return "REROUTE_OK";
            } catch (BizException e) {
                return e.getCode();
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String receiptOutcome = receiptFuture.get(15, TimeUnit.SECONDS);
        String rerouteOutcome = rerouteFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 只能改投先成或回执先成，不能既闭环又改变目的地
        assertThat(receiptOutcome).isIn("RECEIPT_OK", "MANIFEST_NOT_IN_TRANSIT");
        assertThat(rerouteOutcome).isIn("REROUTE_OK", "VERSION_CONFLICT", "MANIFEST_NOT_IN_TRANSIT");
        long successes = (receiptOutcome.equals("RECEIPT_OK") ? 1 : 0) + (rerouteOutcome.equals("REROUTE_OK") ? 1 : 0);
        assertThat(successes).isEqualTo(1);

        var detail = manifestService.detail(manifestId);
        if ("RECEIPT_OK".equals(receiptOutcome)) {
            assertThat(detail.status()).isEqualTo("RECEIVED");
            assertThat(detail.destinationVersion()).isEqualTo(1);
            assertThat(detail.receipts()).hasSize(1);
            assertThat(detail.destinations()).hasSize(1);
        } else {
            assertThat(rerouteOutcome).isEqualTo("REROUTE_OK");
            assertThat(detail.status()).isEqualTo("IN_TRANSIT");
            assertThat(detail.destinationVersion()).isEqualTo(2);
            assertThat(detail.receipts()).isEmpty();
            assertThat(detail.destinations()).hasSize(2);
        }
    }

    @Test
    void concurrentReroutesAllowExactlyOne() throws Exception {
        long manifestId = registerInTransitManifest("REQ-RR-CONC-ENTRY");

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
                    manifestService.reroute(manifestId, "REQ-RR-CONC-" + index, "F-INCINERATION-2", 1);
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
        var detail = manifestService.detail(manifestId);
        assertThat(detail.destinationVersion()).isEqualTo(2);
        assertThat(detail.destinations()).hasSize(2);
    }
}
