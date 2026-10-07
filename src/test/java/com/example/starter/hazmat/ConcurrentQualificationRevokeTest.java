package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 撤销资格与受控段 IN 确认并发：人员行锁串行化形成一致次序——
 * IN 先完成则换班不回滚、撤销留风险证据；撤销先提交则 IN 重查资格拒绝。
 * 两侧胜负由真实调度决定，只断言次序一致与数据自洽。
 */
class ConcurrentQualificationRevokeTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime SHIFT_START = LocalDateTime.parse("2026-10-02T15:30:00");
    private static final LocalDateTime SHIFT_END = LocalDateTime.parse("2026-10-02T16:00:00");

    @BeforeEach
    void fixClock() {
        clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
    }

    @Test
    void revokeAndInRaceYieldsConsistentOrder() throws Exception {
        final int rounds = 6;
        int inWins = 0;
        int revokeWins = 0;
        String firstEscort = null;

        for (int round = 0; round < rounds; round++) {
            String escort = "ESC-R" + round;
            if (firstEscort == null) {
                firstEscort = escort;
            }
            long qualId = ((Number) createQualification("cr-qual-" + round, escort, "FLAMMABLE",
                    LocalDateTime.parse("2026-10-02T00:00:00"),
                    LocalDateTime.parse("2026-10-03T00:00:00"))
                    .body().get("qualificationId")).longValue();
            String plate = "争用车-" + round;
            jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                    plate);
            String no = shipmentNo(apply("cr-apply-" + round, plate, "FLAMMABLE", "R-G3", DEPARTURE));
            approve(no);
            dispatch(no, "cr-dispatch-" + round);
            setControlledRelayPoint(no, "cr-rp-" + round, 1);
            reserve(no, "cr-res-" + round, 1, escort, SHIFT_START, SHIFT_END);
            // 段已开始打卡（OUT），撤销不连带释放预约，胜负取决于 IN 与撤销的锁次序。
            relayCheckin(no, "cr-out-" + round, "ESC-01", "OUT",
                    LocalDateTime.parse("2026-10-02T23:40:00"));

            final String shipmentNo = no;
            final String escortId = escort;
            final long qualificationId = qualId;
            final int roundNo = round;
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<String> inOutcome = new AtomicReference<>("none");
            AtomicReference<String> revokeOutcome = new AtomicReference<>("none");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    await(start);
                    try {
                        ServiceResult result = relayCheckin(shipmentNo, "cr-in-" + roundNo, escortId, "IN",
                                LocalDateTime.parse("2026-10-02T23:45:00"));
                        inOutcome.set(result.httpStatus() == 200 ? "COMPLETED" : "HTTP" + result.httpStatus());
                    } catch (ApiException e) {
                        inOutcome.set("REJECTED:" + e.getCode());
                    }
                });
                pool.submit(() -> {
                    await(start);
                    try {
                        ServiceResult result = revokeQualification(qualificationId, "cr-rv-" + roundNo, 1);
                        revokeOutcome.set(result.httpStatus() == 200 ? "REVOKED" : "HTTP" + result.httpStatus());
                    } catch (ApiException e) {
                        revokeOutcome.set("REJECTED:" + e.getCode());
                    }
                });
                start.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }

            Map<String, Object> view = view(no);
            Map<String, Object> segment = relaySegments(view).get(0);
            Map<String, Object> qualification = getQualification(qualId).body();
            // 撤销始终成功（版本 1 → 2）。
            assertThat(revokeOutcome.get()).isEqualTo("REVOKED");
            assertThat(qualification.get("revoked")).isEqualTo(true);
            assertThat(qualification.get("versionNo")).isEqualTo(2);

            if ("COMPLETED".equals(inOutcome.get())) {
                inWins++;
                // IN 先完成：换班不回滚，段 COMPLETED，撤销留风险证据。
                assertThat(segment.get("status")).isEqualTo("COMPLETED");
                assertThat(segment.get("inEscortId")).isEqualTo(escortId);
                assertThat(events(view).stream()
                        .anyMatch(e -> "QUALIFICATION_RISK".equals(e.get("eventType")))).isTrue();
            } else {
                revokeWins++;
                // 撤销先提交：IN 重查资格拒绝，段保持进行中，无风险事件（换班未发生）。
                assertThat(inOutcome.get()).isEqualTo("REJECTED:QUALIFICATION_REVOKED");
                assertThat(segment.get("status")).isEqualTo("IN_PROGRESS");
                assertThat(segment.get("inEscortId")).isNull();
                assertThat(events(view).stream()
                        .noneMatch(e -> "QUALIFICATION_RISK".equals(e.get("eventType")))).isTrue();
            }
        }
        System.out.println("inWins=" + inWins + ", revokeWins=" + revokeWins);

        // 无论哪侧胜，资格已撤销：拒绝该人后续预约。
        String plate = "争用车-后续";
        jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                plate);
        String no = shipmentNo(apply("cr-later-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "cr-later-dispatch");
        setControlledRelayPoint(no, "cr-later-rp", 1);
        final String laterEscort = firstEscort;
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> reserve(no, "cr-later-res", 1, laterEscort, SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
