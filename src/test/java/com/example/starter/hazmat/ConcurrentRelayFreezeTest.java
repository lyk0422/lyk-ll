package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 换班完成与禁行冻结并发：恰在禁行起点前后，接班打卡与监控扫描竞争，
 * 行锁 + 终态 CAS 保证唯一终态——不得既完成换班又被冻结，也不得既冻结又继续计程。
 * 两侧胜负由真实调度决定，只断言终态唯一与数据自洽。
 */
class ConcurrentRelayFreezeTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-03T00:00:00");

    @Test
    void handoverAndCurfewFreezeRaceYieldsSingleTerminal() throws Exception {
        final int rounds = 6;
        int handoverWins = 0;
        int freezeWins = 0;

        for (int round = 0; round < rounds; round++) {
            String plate = "接力车-" + round;
            jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                    plate);
            String no = shipmentNo(apply("race-apply-" + round, plate, "FLAMMABLE", "R-G3", DEPARTURE));
            approve(no);
            dispatch(no, "race-dispatch-" + round);
            setRelayPoint(no, "race-rp-" + round, 1);
            // 交班已打卡，只剩接班一脚；业务时钟固定在禁行起点前 1 分钟。
            clock.setTime(LocalDateTime.parse("2026-10-02T23:59:00"));
            relayCheckin(no, "race-out-" + round, "ESC-01", "OUT",
                    LocalDateTime.parse("2026-10-02T23:50:00"));

            final String shipmentNo = no;
            final int roundNo = round;
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<String> handoverOutcome = new AtomicReference<>("none");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    await(start);
                    try {
                        ServiceResult result = relayCheckin(shipmentNo, "race-in-" + roundNo, "ESC-02", "IN",
                                LocalDateTime.parse("2026-10-02T23:58:00"));
                        handoverOutcome.set(result.httpStatus() == 200 ? "COMPLETED" : "HTTP" + result.httpStatus());
                    } catch (ApiException e) {
                        handoverOutcome.set("REJECTED:" + e.getCode());
                    }
                });
                pool.submit(() -> {
                    await(start);
                    scan(CURFEW_START);
                });
                start.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }

            Map<String, Object> view = view(no);
            String finalStatus = (String) view.get("status");
            Map<String, Object> segment = relaySegments(view).stream()
                    .filter(s -> Integer.valueOf(1).equals(s.get("relaySeq")))
                    .findFirst().orElseThrow();
            if ("CURFEW_FROZEN".equals(finalStatus)) {
                freezeWins++;
                // 冻结终态自洽：处置登记同事务落库，接力段 FROZEN，接班打卡未生效。
                assertThat(disposals(view)).hasSize(1);
                assertThat(segment.get("status")).isEqualTo("FROZEN");
                assertThat(segment.get("inEscortId")).isNull();
                assertThat(handoverOutcome.get()).startsWith("REJECTED");
            } else {
                handoverWins++;
                // 换班赢面：接力段 COMPLETED，运单未冻结，无处置登记。
                assertThat(segment.get("status")).isEqualTo("COMPLETED");
                assertThat(finalStatus).isIn("DISPATCHED", "IN_TRANSIT");
                assertThat(disposals(view)).isEmpty();
                assertThat(handoverOutcome.get()).isEqualTo("COMPLETED");
            }
        }
        System.out.println("handoverWins=" + handoverWins + ", freezeWins=" + freezeWins);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
