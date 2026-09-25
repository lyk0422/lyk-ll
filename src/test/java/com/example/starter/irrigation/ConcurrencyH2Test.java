package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.api.MeteringDtos.MeterReportRequest;
import com.example.starter.irrigation.api.MeteringDtos.MeterSnapshotResponse;
import com.example.starter.irrigation.api.PlanDtos.PublishPlanRequest;
import com.example.starter.irrigation.api.PlanDtos.PlanVersionResponse;
import com.example.starter.irrigation.api.SettlementDtos.SealRequest;
import com.example.starter.irrigation.api.SettlementDtos.SettlementResponse;
import com.example.starter.irrigation.service.ConflictException;
import com.example.starter.irrigation.service.GroupService;
import com.example.starter.irrigation.service.MeteringService;
import com.example.starter.irrigation.service.PlanService;
import com.example.starter.irrigation.service.SettlementService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 真实并发 H2 测试：并发发布唯一生效版本、计量事件并发去重、
 * 计量与封存并发时结转结果以最后已确认快照为准且可复算。
 */
class ConcurrencyH2Test extends AbstractIntegrationTest {

    private static final String GROUP = "G-CONC";
    private static final long TURN = 2960L;
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private GroupService groupService;
    @Autowired
    private PlanService planService;
    @Autowired
    private MeteringService meteringService;
    @Autowired
    private SettlementService settlementService;

    private void seedGroup(int outlets) {
        List<OutletMember> members = new ArrayList<>();
        for (int i = 1; i <= outlets; i++) {
            String code = "D%03d".formatted(i);
            members.add(new OutletMember(code, "斗口" + i, 1L));
        }
        groupService.createGroup(new CreateGroupRequest(GROUP, "并发测试组", members));
    }

    @Test
    void concurrentFirstPublishesProduceExactlyOneEffectiveVersion() {
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            seedGroup(3);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<PlanVersionResponse>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    String requestId = "req-conc-" + i;
                    futures.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return planService.publish(
                                new PublishPlanRequest(requestId, GROUP, TURN, 10L));
                    }));
                }

                int success = 0;
                int conflict = 0;
                PlanVersionResponse winner = null;
                for (Future<PlanVersionResponse> future : futures) {
                    try {
                        PlanVersionResponse response = future.get(30, TimeUnit.SECONDS);
                        success++;
                        winner = response;
                    } catch (Exception e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof ConflictException) {
                            conflict++;
                        } else {
                            throw new AssertionError("并发发布出现非预期异常", cause);
                        }
                    }
                }

                assertThat(success).as("并发首次发布唯一成功").isEqualTo(1);
                assertThat(conflict).isEqualTo(threads - 1);
                assertThat(winner).isNotNull();
                assertThat(winner.versionNo()).isEqualTo(1);

                Integer effectiveCount = jdbc.queryForObject("""
                        SELECT COUNT(1) FROM irr_plan_version
                        WHERE turn_no = ? AND group_code = ? AND status = 'EFFECTIVE'
                        """, Integer.class, TURN, GROUP);
                assertThat(effectiveCount).isEqualTo(1);
                // 失败者不产生任何版本记录
                Integer totalVersions = jdbc.queryForObject("""
                        SELECT COUNT(1) FROM irr_plan_version
                        WHERE turn_no = ? AND group_code = ?
                        """, Integer.class, TURN, GROUP);
                assertThat(totalVersions).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        });
    }

    @Test
    void concurrentReportsWithSameEventIdAcceptOnlyOne() {
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            seedGroup(2);
            planService.publish(new PublishPlanRequest("req-plan", GROUP, TURN, 10L));

            int threads = 6;
            String eventId = "evt-same-" + UUID.randomUUID();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<MeterSnapshotResponse>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    long reading = 5L + i; // 读数各不相同，但只有首个被接受
                    futures.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return meteringService.report(new MeterReportRequest(
                                eventId, GROUP, "D001", TURN, reading));
                    }));
                }

                int accepted = 0;
                int duplicate = 0;
                Long firstReading = null;
                for (Future<MeterSnapshotResponse> future : futures) {
                    MeterSnapshotResponse response = future.get(30, TimeUnit.SECONDS);
                    if (response.duplicate()) {
                        duplicate++;
                    } else {
                        accepted++;
                        firstReading = response.deliveredCubicMeter();
                    }
                }

                assertThat(accepted).as("同 eventId 并发上报仅一个被接受").isEqualTo(1);
                assertThat(duplicate).isEqualTo(threads - 1);
                Integer eventRows = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM irr_meter_event WHERE event_id = ?",
                        Integer.class, eventId);
                assertThat(eventRows).isEqualTo(1);
                Long delivered = jdbc.queryForObject(
                        "SELECT delivered_cubic_meter FROM irr_metering "
                                + "WHERE turn_no = ? AND outlet_code = 'D001'",
                        Long.class, TURN);
                assertThat(delivered).isEqualTo(firstReading);
            } finally {
                pool.shutdownNow();
            }
        });
    }

    @Test
    void settlementConcurrentWithMeteringIsRecomputableAndLateReportsDoNotChangeIt() {
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            seedGroup(3);
            planService.publish(new PublishPlanRequest("req-plan", GROUP, TURN, 10L));
            // 等面积 3 斗口，计划：D001=4, D002=3, D003=3

            ExecutorService pool = Executors.newFixedThreadPool(4);
            CyclicBarrier barrier = new CyclicBarrier(4);
            try {
                // 3 个计量线程与封存线程同时起跑
                List<Future<?>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("evt-c1", GROUP, "D001", TURN, 4L));
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("evt-c2", GROUP, "D002", TURN, 1L));
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("evt-c3", GROUP, "D003", TURN, 2L));
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return settlementService.seal(new SealRequest(GROUP, TURN));
                }));
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }

                SettlementResponse sealed = settlementService.seal(new SealRequest(GROUP, TURN));
                assertThat(sealed.status()).isEqualTo("SEALED");

                // 封存后再到达的计量：登记迟到，不改结转
                MeterSnapshotResponse late = meteringService.report(
                        new MeterReportRequest("evt-after", GROUP, "D001", TURN, 0L));
                assertThat(late.late()).isTrue();

                // 强不变量：封存所见即最后已确认快照（迟到不改快照），结转逐行可复算。
                assertCarryoverRecomputableFromSnapshots(sealed);
            } finally {
                pool.shutdownNow();
            }
        });
    }

    @Test
    void sealFirstThenConcurrentMeteringAllBecomesLateAndSettlementStaysAtZeroDelivery() {
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            seedGroup(2);
            planService.publish(new PublishPlanRequest("req-plan2", GROUP, TURN, 10L));
            // 2 斗口等面积，权重各 0.5，计划 D001=5, D002=5
            settlementService.seal(new SealRequest(GROUP, TURN));

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<MeterSnapshotResponse>> futures = new ArrayList<>();
            try {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("late-1", GROUP, "D001", TURN, 4L));
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("late-2", GROUP, "D001", TURN, 4L));
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("late-3", GROUP, "D002", TURN, 3L));
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return meteringService.report(
                            new MeterReportRequest("late-4", GROUP, "D002", TURN, 3L));
                }));
                for (Future<MeterSnapshotResponse> future : futures) {
                    assertThat(future.get(30, TimeUnit.SECONDS).late()).isTrue();
                }

                // 封存结果恒为零实供
                SettlementResponse sealed = settlementService.getSettlement(TURN, GROUP);
                assertThat(sealed.lines()).extracting(l -> l.outletCode() + "=" + l.deliveredCubicMeter())
                        .containsExactly("D001=0", "D002=0");
                assertThat(sealed.lines()).extracting(l -> l.outletCode() + "=" + l.shortageCubicMeter())
                        .containsExactly("D001=5", "D002=5");
                Integer lateRows = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM irr_late_meter_event WHERE turn_no = ? AND group_code = ?",
                        Integer.class, TURN, GROUP);
                assertThat(lateRows).isEqualTo(4);
            } finally {
                pool.shutdownNow();
            }
        });
    }

    /**
     * 可复算断言：结转明细中的实供量必须等于当前计量快照（迟到事件不改快照），
     * 欠供与结转量均可由计划量与实供量按规则重算得出。
     */
    private void assertCarryoverRecomputableFromSnapshots(SettlementResponse sealed) {
        for (var line : sealed.lines()) {
            List<Long> deliveredRows = jdbc.queryForList(
                    "SELECT delivered_cubic_meter FROM irr_metering "
                            + "WHERE turn_no = ? AND outlet_code = ?",
                    Long.class, TURN, line.outletCode());
            long expectedDelivered = deliveredRows.isEmpty() ? 0L : deliveredRows.get(0);
            assertThat(line.deliveredCubicMeter())
                    .as("%s 封存实供等于最后已确认快照", line.outletCode())
                    .isEqualTo(expectedDelivered);

            long expectedShortage = Math.max(0, line.planCubicMeter() - expectedDelivered);
            assertThat(line.shortageCubicMeter()).isEqualTo(expectedShortage);
            long expectedCap = line.planCubicMeter() * 30 / 100;
            assertThat(line.carryoverCubicMeter())
                    .isEqualTo(Math.min(expectedShortage, expectedCap));
            assertThat(line.voidedCubicMeter())
                    .isEqualTo(expectedShortage - line.carryoverCubicMeter());
        }
    }
}
