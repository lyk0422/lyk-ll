package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 撤销资格与受控段 IN 并发：资格行锁形成一致次序。
 * IN 先成交则换班不回滚、留风险证据且拒绝该人后续预约；撤销先成交则 IN 被拒绝。
 * 两侧胜负由真实调度决定，只断言次序一致与数据自洽。
 */
class ConcurrentQualificationRevokeRaceTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime QUAL_FROM = LocalDateTime.parse("2026-10-02T14:00:00");
    private static final LocalDateTime QUAL_TO = LocalDateTime.parse("2026-10-03T03:00:00");
    private static final LocalDateTime SHIFT_START = LocalDateTime.parse("2026-10-02T15:30:00");
    private static final LocalDateTime SHIFT_END = LocalDateTime.parse("2026-10-02T15:50:00");

    @Test
    void revokeAndInRaceYieldsConsistentOrder() throws Exception {
        final int rounds = 6;
        int inWins = 0;
        int revokeWins = 0;

        for (int round = 0; round < rounds; round++) {
            // 上一轮可能残留 ACTIVE 预约（撤销先成交的分支），逐轮清理避免顺序依赖。
            jdbc.execute("DELETE FROM escort_reservation");
            jdbc.execute("DELETE FROM escort_qualification");
            clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
            long qid = ((Number) createQualification("cq-qual-" + round, "ESC-B", "FLAMMABLE",
                    QUAL_FROM, QUAL_TO).body().get("qualificationId")).longValue();
            String plate = "竞争车-" + round;
            jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                    plate);
            String no = shipmentNo(apply("cq-apply-" + round, plate, "FLAMMABLE", "R-G3", DEPARTURE));
            approve(no);
            dispatch(no, "cq-dispatch-" + round);
            ServiceResult set = setRelayPoint(no, "cq-rp-" + round, 1, true);
            long segmentId = ((Number) set.body().get("relaySegmentId")).longValue();
            createReservation(no, segmentId, "cq-res-" + round, "ESC-B", SHIFT_START, SHIFT_END);
            relayCheckin(no, "cq-out-" + round, "ESC-01", "OUT",
                    LocalDateTime.parse("2026-10-02T23:40:00"));

            final String shipmentNo = no;
            final long qualificationId = qid;
            final int roundNo = round;
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<String> inOutcome = new AtomicReference<>("none");
            AtomicReference<String> revokeOutcome = new AtomicReference<>("none");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    await(start);
                    try {
                        ServiceResult result = relayCheckin(shipmentNo, "cq-in-" + roundNo, "ESC-B", "IN",
                                LocalDateTime.parse("2026-10-02T23:45:00"));
                        inOutcome.set(result.httpStatus() == 200 ? "COMPLETED" : "HTTP" + result.httpStatus());
                    } catch (ApiException e) {
                        inOutcome.set("REJECTED:" + e.getCode());
                    }
                });
                pool.submit(() -> {
                    await(start);
                    try {
                        ServiceResult result = revokeQualification(qualificationId, "cq-rvk-" + roundNo, 1);
                        revokeOutcome.set("HTTP" + result.httpStatus());
                    } catch (ApiException e) {
                        revokeOutcome.set("REJECTED:" + e.getCode());
                    }
                });
                start.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }

            // 撤销本身始终成功（版本 1 匹配）。
            assertThat(revokeOutcome.get()).isEqualTo("HTTP200");
            Map<String, Object> view = view(no);
            Map<String, Object> segment = relaySegments(view).stream()
                    .filter(s -> Integer.valueOf(1).equals(s.get("relaySeq"))).findFirst().orElseThrow();
            Map<String, Object> reservation = reservations(view).get(0);
            boolean riskEvidence = events(view).stream()
                    .anyMatch(e -> "QUALIFICATION_REVOKED_RISK".equals(e.get("eventType")));
            if ("COMPLETED".equals(inOutcome.get())) {
                inWins++;
                // IN 先成交：已成功换班不回滚，预约核销；撤销留风险证据。
                assertThat(segment.get("status")).isEqualTo("COMPLETED");
                assertThat(reservation.get("status")).isEqualTo("CONSUMED");
                assertThat(riskEvidence).isTrue();
            } else {
                revokeWins++;
                // 撤销先成交：IN 重查资格已撤销被拒绝，换班未生效，无风险事件。
                assertThat(inOutcome.get()).isEqualTo("REJECTED:QUALIFICATION_REVOKED");
                assertThat(segment.get("status")).isEqualTo("IN_PROGRESS");
                assertThat(reservation.get("status")).isEqualTo("ACTIVE");
                assertThat(riskEvidence).isFalse();
            }
            // 两种次序下：该人员后续预约均被拒绝（资格已撤销）。
            String plate2 = "后续车-" + round;
            jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                    plate2);
            String no2 = shipmentNo(apply("cq-apply2-" + round, plate2, "FLAMMABLE", "R-G3", DEPARTURE));
            approve(no2);
            dispatch(no2, "cq-dispatch2-" + round);
            ServiceResult set2 = setRelayPoint(no2, "cq-rp2-" + round, 1, true);
            long segment2 = ((Number) set2.body().get("relaySegmentId")).longValue();
            assertThatThrownBy(() -> createReservation(no2, segment2, "cq-res2-" + roundNo, "ESC-B",
                    SHIFT_START, SHIFT_END))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));
        }
        System.out.println("inWins=" + inWins + ", revokeWins=" + revokeWins);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
