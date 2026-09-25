package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 结案终态：重复核销幂等成功；结案后迟到打卡只记事件；核销与失联冻结并发形成唯一终态。
 */
class CompletionAndTerminalTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    @Autowired
    private JdbcTemplate jdbc;

    private String dispatchedInTransit(String plate, String prefix) {
        String no = shipmentNo(apply(prefix + "-apply", plate, "TOXIC", "R-G1", DEPARTURE));
        approve(no);
        dispatch(no, prefix + "-dispatch");
        return no;
    }

    @Test
    void repeatedCompleteIsIdempotentSuccess() {
        String no = dispatchedInTransit("沪A-1001", "c-rpt");
        assertThat(complete(no, "c-done-1").httpStatus()).isEqualTo(200);
        // 不同 requestId 的重复核销也返回幂等成功（结案为终态，不因幂等键判冲突）。
        assertThat(complete(no, "c-done-2").httpStatus()).isEqualTo(200);
        var view = view(no);
        assertThat(view.get("status")).isEqualTo("COMPLETED");
        assertThat(view.get("terminal")).isEqualTo(true);
        // 结案释放占用，同车可再申请。
        assertThat(apply("c-after", "沪A-1001", "TOXIC", "R-G1", DEPARTURE).httpStatus()).isEqualTo(200);
    }

    @Test
    void lateCheckinAfterCloseOnlyRecordsEvent() {
        String no = dispatchedInTransit("沪A-1002", "c-late");
        complete(no, "c-done");
        // 结案后打卡：不改变运单状态、不写途经点，仅记录迟到事件。
        ServiceResult result = checkin(no, "c-late-ck", 0, DEPARTURE.plusMinutes(40));
        assertThat(result.body().get("status")).isEqualTo("COMPLETED");
        assertThat(result.body().get("lateAfterClosed")).isEqualTo(true);
        assertThat(waypoint(no, 0).get("state")).isEqualTo("PENDING");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "LATE_CHECKIN_AFTER_CLOSE".equals(e.get("eventType")))).isTrue();
    }

    @Test
    void completeAfterLostContactRejected() {
        String no = dispatchedInTransit("沪B-2001", "c-lost");
        // 连续 p0、p1 超时 → 失联冻结。
        scan(LocalDateTime.parse("2026-10-02T10:00:00"));
        assertThat(view(no).get("status")).isEqualTo("LOST_CONTACT");
        assertThatThrownBy(() -> complete(no, "c-done-after-lost"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ALREADY_LOST_CONTACT"));
    }

    @Test
    void completedShipmentIsNotFrozenByLaterScan() {
        // 核销赢面：先结案，即使之后到达超时时刻扫描，也不得回改为失联或冻结车辆。
        String no = dispatchedInTransit("沪A-1002", "c-win-complete");
        complete(no, "c-win-done");
        ServiceResult scanResult = scan(LocalDateTime.parse("2026-10-02T10:30:00"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affected =
                (List<Map<String, Object>>) scanResult.body().get("affected");
        assertThat(affected.stream().noneMatch(a -> no.equals(a.get("shipmentNo")))).isTrue();
        assertThat(view(no).get("status")).isEqualTo("COMPLETED");
        assertThat(repository.findVehicle("沪A-1002").orElseThrow().frozen()).isFalse();
    }

    /**
     * 核销与失联冻结并发：两线程同时 complete 与 scan（触发失联），
     * 数据库条件更新保证唯一终态——不得既 COMPLETED 又 LOST_CONTACT。
     * 多轮使用各自独立的合成车辆，避免冻结与全局扫描相互污染。
     * 两侧"谁赢"由真实调度决定，本测试只断言终态唯一与数据自洽，不制造 flaky 的胜负配额。
     */
    @Test
    void completeAndLostContactRaceYieldsSingleTerminal() throws Exception {
        LocalDateTime lostAt = LocalDateTime.parse("2026-10-02T10:00:00");
        int completeWins = 0;
        int lostWins = 0;
        final int rounds = 10;

        for (int round = 0; round < rounds; round++) {
            String plate = "竞赛车-" + round;
            jdbc.update("INSERT INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) VALUES (?, 0, NULL, NULL)",
                    plate);
            final String no = dispatchedInTransit(plate, "c-race-" + round);
            final int roundNo = round;

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<String> completeOutcome = new AtomicReference<>("none");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    await(start);
                    try {
                        complete(no, "c-race-done-" + roundNo);
                        completeOutcome.set("COMPLETED");
                    } catch (ApiException e) {
                        completeOutcome.set("REJECTED:" + e.getCode());
                    }
                });
                pool.submit(() -> {
                    await(start);
                    scan(lostAt);
                });
                start.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }

            String finalStatus = (String) view(no).get("status");
            // 唯一终态断言：状态必须是二者之一，且不得自相矛盾。
            assertThat(finalStatus).isIn("COMPLETED", "LOST_CONTACT");
            boolean frozen = repository.findVehicle(plate).orElseThrow().frozen();
            if ("COMPLETED".equals(finalStatus)) {
                completeWins++;
                assertThat(frozen).isFalse();
                assertThat(repository.findActiveShipment(plate)).isEmpty();
            } else {
                lostWins++;
                assertThat(frozen).isTrue();
                assertThat(repository.findActiveShipment(plate)).isEmpty();
                assertThat(completeOutcome.get()).startsWith("REJECTED");
            }
        }
        // 记录两侧实际胜出场次（不强制配额，避免对调度时序的 flaky 依赖）。
        System.out.println("completeWins=" + completeWins + ", lostWins=" + lostWins);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
