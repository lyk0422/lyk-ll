package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 两运单并发预约同一接班员的重叠班段：资格行锁串行化，恰一张单预约成功，
 * 另一张 409 且不占班段——同一人不能同时接下两张单。
 */
class ConcurrentReservationRaceTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime QUAL_FROM = LocalDateTime.parse("2026-10-02T14:00:00");
    private static final LocalDateTime QUAL_TO = LocalDateTime.parse("2026-10-03T03:00:00");
    private static final LocalDateTime SHIFT_START = LocalDateTime.parse("2026-10-02T15:30:00");
    private static final LocalDateTime SHIFT_END = LocalDateTime.parse("2026-10-02T16:00:00");

    @Test
    void concurrentReservationsForSameEscortOnlyOneWins() throws Exception {
        final int rounds = 6;
        for (int round = 0; round < rounds; round++) {
            clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
            createQualification("cr-qual-" + round, "ESC-B", "FLAMMABLE", QUAL_FROM, QUAL_TO);
            String[] shipments = new String[2];
            long[] segments = new long[2];
            for (int i = 0; i < 2; i++) {
                String plate = "预约车-" + round + "-" + i;
                jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                        plate);
                String no = shipmentNo(apply("cr-apply-" + round + "-" + i, plate, "FLAMMABLE", "R-G3", DEPARTURE));
                approve(no);
                dispatch(no, "cr-dispatch-" + round + "-" + i);
                ServiceResult set = setRelayPoint(no, "cr-rp-" + round + "-" + i, 1, true);
                shipments[i] = no;
                segments[i] = ((Number) set.body().get("relaySegmentId")).longValue();
            }

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<String>[] outcomes = new AtomicReference[]{
                    new AtomicReference<>("none"), new AtomicReference<>("none")};
            ExecutorService pool = Executors.newFixedThreadPool(2);
            final int roundNo = round;
            try {
                for (int i = 0; i < 2; i++) {
                    final int idx = i;
                    pool.submit(() -> {
                        await(start);
                        try {
                            ServiceResult result = createReservation(shipments[idx], segments[idx],
                                    "cr-res-" + roundNo + "-" + idx, "ESC-B", SHIFT_START, SHIFT_END);
                            outcomes[idx].set("HTTP" + result.httpStatus());
                        } catch (ApiException e) {
                            outcomes[idx].set("REJECTED:" + e.getCode());
                        }
                    });
                }
                start.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }

            // 恰一张单预约成功，另一张因班段内部重叠 409。
            long successes = java.util.Arrays.stream(outcomes).map(AtomicReference::get)
                    .filter(o -> "HTTP200".equals(o)).count();
            long overlaps = java.util.Arrays.stream(outcomes).map(AtomicReference::get)
                    .filter(o -> "REJECTED:ESCORT_SHIFT_OVERLAP".equals(o)).count();
            assertThat(successes).as("round %d outcomes: %s,%s", round,
                    outcomes[0].get(), outcomes[1].get()).isEqualTo(1);
            assertThat(overlaps).isEqualTo(1);
            // 数据自洽：ESC-B 仅一条 ACTIVE 班段占用。
            Integer active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM escort_reservation WHERE escort_id = 'ESC-B' AND status = 'ACTIVE'",
                    Integer.class);
            assertThat(active).isEqualTo(1);
            jdbc.execute("DELETE FROM escort_reservation");
            jdbc.execute("DELETE FROM escort_qualification");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
