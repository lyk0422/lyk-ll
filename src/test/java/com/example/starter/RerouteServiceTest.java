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

import com.example.starter.service.FacilityService;
import com.example.starter.service.ReceiptService;
import com.example.starter.service.RerouteService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;
import com.example.starter.support.TestClockConfig;

/**
 * 在途改投：保留原称重与净重、去向版本递增、expectedVersion 过期 409、
 * 与当前版本回执并发只能先成一个。
 */
class RerouteServiceTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private RerouteService rerouteService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private FacilityService facilityService;

    /**
     * 登记一张 OTHER 品类、净重 10000kg、去向 F-INCINERATION 的在途联单（毛重 18000 - 皮重 8000）。
     */
    private long registerOtherManifest(String requestId) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        return weighingService.registerEntry(new WeighingService.EntryCommand(
                requestId, plateOf(vehicleId), "示例小区", "OTHER",
                new BigDecimal("18000.000"), "F-INCINERATION", null)).manifestId();
    }

    @Test
    void rerouteCreatesNewDestinationVersionAndKeepsWeighing() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-1");

        var result = rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-1", manifestId, "F-INCINERATION-2", 1));

        assertThat(result.destinationVersion()).isEqualTo(2);
        assertThat(result.facilityCode()).isEqualTo("F-INCINERATION-2");
        assertThat(result.replayed()).isFalse();

        // 原称重、小区、品类与净重不变，不重新称重
        var row = jdbc.sql("""
                SELECT community, category, gross_kg, tare_kg, net_kg, destination_version, status
                FROM manifest WHERE id = :id
                """).param("id", manifestId)
                .query((rs, i) -> rs.getString("community") + "|" + rs.getString("category") + "|"
                        + rs.getBigDecimal("gross_kg") + "|" + rs.getBigDecimal("tare_kg") + "|"
                        + rs.getBigDecimal("net_kg") + "|" + rs.getInt("destination_version") + "|"
                        + rs.getString("status"))
                .single();
        assertThat(row).isEqualTo("示例小区|OTHER|18000.000|8000.000|10000.000|2|IN_TRANSIT");

        // 去向链完整：版本 1 原设施，版本 2 新设施
        List<String> chain = jdbc.sql("""
                SELECT f.code || ':' || d.version FROM manifest_destination d
                JOIN facility f ON f.id = d.facility_id
                WHERE d.manifest_id = :id ORDER BY d.version
                """).param("id", manifestId)
                .query(String.class)
                .list();
        assertThat(chain).containsExactly("F-INCINERATION:1", "F-INCINERATION-2:2");
    }

    @Test
    void rerouteToSameFacilityIsRejected() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-2");

        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-2", manifestId, "F-INCINERATION", 1)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REROUTE_SAME_FACILITY");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void rerouteToFacilityOutsideCatalogIsRejected() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-3");

        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-3", manifestId, "F-NOT-EXIST", 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));

        // 目录内但已停用同样拒绝
        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-3B", manifestId, "F-HAZARDOUS", 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));
    }

    @Test
    void rerouteToSuspendedFacilityIsRejected() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-4");
        // 新设施当前处于停收区间
        facilityService.revise(new FacilityService.RevisionCommand(
                "REQ-RR-REV-4", "F-INCINERATION-2", 1, null, null,
                new FacilityService.SuspensionWindow(
                        TestClockConfig.BASE_INSTANT.minus(Duration.ofHours(1)),
                        TestClockConfig.BASE_INSTANT.plus(Duration.ofHours(1))), false));

        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-4", manifestId, "F-INCINERATION-2", 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_SUSPENDED"));
    }

    @Test
    void rerouteWithWrongCategoryAcceptanceIsRejected() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-5");

        // F-KITCHEN 不接收 OTHER
        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-5", manifestId, "F-KITCHEN", 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FACILITY_CATEGORY_NOT_ACCEPTED"));
    }

    @Test
    void rerouteOnClosedManifestIsRejected() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-6");
        receiptService.registerReceipt(manifestId, "REQ-RR-RCPT-6", new BigDecimal("10000.000"));

        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-6", manifestId, "F-INCINERATION-2", 1)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MANIFEST_NOT_IN_TRANSIT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void staleExpectedVersionIsConflictAndFailureDoesNotBurnKey() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-7");
        rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-7A", manifestId, "F-INCINERATION-2", 1));

        // 过期版本 409，且失败不占键
        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-7B", manifestId, "F-INCINERATION", 1)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DESTINATION_VERSION_STALE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        var retried = rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-7B", manifestId, "F-INCINERATION", 2));
        assertThat(retried.destinationVersion()).isEqualTo(3);
        assertThat(retried.facilityCode()).isEqualTo("F-INCINERATION");
    }

    @Test
    void rerouteIsIdempotentAndConflictOnDifferentParams() {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-8");
        var first = rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-8", manifestId, "F-INCINERATION-2", 1));

        var replay = rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-8", manifestId, "F-INCINERATION-2", 1));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.destinationVersion()).isEqualTo(first.destinationVersion());

        assertThatThrownBy(() -> rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-RR-8", manifestId, "F-KITCHEN", 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void concurrentRerouteAndReceiptExactlyOneWins() throws Exception {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-9");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        // 线程 1：当前版本回执（受控前版本 1，旧式调用即可入账）
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                receiptService.registerReceipt(manifestId, "REQ-RR-RCPT-9", new BigDecimal("10000.000"));
                return "RECEIPT_OK";
            } catch (BizException e) {
                return e.getCode();
            }
        }));
        // 线程 2：改投
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                rerouteService.reroute(new RerouteService.RerouteCommand(
                        "REQ-RR-9", manifestId, "F-INCINERATION-2", 1));
                return "REROUTE_OK";
            } catch (BizException e) {
                return e.getCode();
            }
        }));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // 只能改投先成或回执先成，不能既闭环又改变目的地
        boolean receiptWon = outcomes.contains("RECEIPT_OK");
        boolean rerouteWon = outcomes.contains("REROUTE_OK");
        assertThat(receiptWon).isNotEqualTo(rerouteWon);
        String state = jdbc.sql("""
                SELECT status || '|' || destination_version FROM manifest WHERE id = :id
                """).param("id", manifestId).query(String.class).single();
        Integer receiptRows = jdbc.sql("SELECT COUNT(*) FROM receipt WHERE manifest_id = :id")
                .param("id", manifestId).query(Integer.class).single();
        Integer rerouteRows = jdbc.sql(
                "SELECT COUNT(*) FROM manifest_destination WHERE manifest_id = :id AND version > 1")
                .param("id", manifestId).query(Integer.class).single();
        if (receiptWon) {
            assertThat(state).isEqualTo("RECEIVED|1");
            assertThat(receiptRows).isEqualTo(1);
            assertThat(rerouteRows).isZero();
        } else {
            assertThat(state).isEqualTo("IN_TRANSIT|2");
            assertThat(receiptRows).isZero();
            assertThat(rerouteRows).isEqualTo(1);
        }
    }

    @Test
    void concurrentReroutesForSameManifestAllowExactlyOne() throws Exception {
        long manifestId = registerOtherManifest("REQ-RR-ENTRY-10");

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
                    rerouteService.reroute(new RerouteService.RerouteCommand(
                            "REQ-RR-10-" + index, manifestId, "F-INCINERATION-2", 1));
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
        Integer version = jdbc.sql("SELECT destination_version FROM manifest WHERE id = :id")
                .param("id", manifestId).query(Integer.class).single();
        assertThat(version).isEqualTo(2);
    }
}
