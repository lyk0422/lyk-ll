package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;

/**
 * 排程门禁并发 H2 测试：真实多线程 + 行锁，验证发布/发布、取消/执行、同键发布竞态唯一结果，
 * 不依赖打印或休眠；用 CountDownLatch 协调并设置超时，最终断言数据库状态。
 */
class ScheduleConcurrencyTest extends SchedulingTestBase {

    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 10, 1, 2, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private SchedulingService scheduling2;

    @Autowired
    private WorkflowService workflow2;

    private SchedulePublishRequest oneItem(String requestId, OffsetDateTime start, int minutes) {
        return new SchedulePublishRequest(PATIENT, PLAN, 1, requestId,
                List.of(new ScheduleItemRequest(1, DEVICE, 1, start, start.plusMinutes(minutes))));
    }

    @Test
    @DisplayName("并发发布同设备同窗口：唯一成功占用，另一个422整批零占用，设备仅1条BOOKED")
    void concurrentPublishSameWindowOnlyOneWins() throws Exception {
        approvedVerifiedPlan();
        registerDevice(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling2.publish(oneItem("CC-P1", T0, 10));
            });
            Future<RecordedResponse> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling2.publish(oneItem("CC-P2", T0, 10));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(f1.get(30, TimeUnit.SECONDS).httpStatus(),
                    f2.get(30, TimeUnit.SECONDS).httpStatus());
            assertThat(statuses).as("恰好一个200一个422").containsExactlyInAnyOrder(200, 422);
        } finally {
            pool.shutdownNow();
        }

        assertThat(scheduling2.planSchedules(PATIENT, PLAN, "BOOKED")).hasSize(1);
        assertThat(planRepository.findActivePlan(PATIENT)).isPresent();
    }

    @Test
    @DisplayName("并发同键发布：唯一生效，另一个重放200 replayed=true，无重复占用")
    void concurrentSameRequestIdPublishOneEffect() throws Exception {
        approvedVerifiedPlan();
        registerDevice(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.function.Supplier<RecordedResponse> task = () -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return scheduling2.publish(oneItem("CC-SAME", T0, 10));
            };
            Future<RecordedResponse> f1 = pool.submit(task::get);
            Future<RecordedResponse> f2 = pool.submit(task::get);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RecordedResponse r1 = f1.get(30, TimeUnit.SECONDS);
            RecordedResponse r2 = f2.get(30, TimeUnit.SECONDS);
            assertThat(r1.httpStatus()).isEqualTo(200);
            assertThat(r2.httpStatus()).isEqualTo(200);
            boolean rp1 = ReplayHelper.replayed(r1.body());
            boolean rp2 = ReplayHelper.replayed(r2.body());
            assertThat(rp1).as("恰有一个为重放").isNotEqualTo(rp2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scheduling2.planSchedules(PATIENT, PLAN, "BOOKED")).hasSize(1);
    }

    @Test
    @DisplayName("取消与执行竞态：唯一结果，占用最终为0且台账至多1条，无双重状态")
    void cancelVersusExecutionRaceUniqueOutcome() throws Exception {
        approvedVerifiedPlan();
        registerDevice(2);
        Long scheduleId = ((com.example.starter.rt.dto.SchedulePublishResponse) scheduling2
                .publish(oneItem("CC-CE", T0, 10)).body()).schedules().get(0).id();
        // 时钟固定在窗口内
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 5, 0));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> cancelF = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling2.cancel(new ScheduleCancelRequest(scheduleId, 1, "CC-CANCEL"));
            });
            Future<RecordedResponse> execF = pool.submit(() -> {
                ready.countDown();
                start.await();
                return workflow2.recordFraction(PATIENT, PLAN, 1, 200L, "CC-EXEC");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RecordedResponse cancelR = cancelF.get(30, TimeUnit.SECONDS);
            RecordedResponse execR = execF.get(30, TimeUnit.SECONDS);

            long success = List.of(cancelR.httpStatus(), execR.httpStatus()).stream()
                    .filter(s -> s == 200).count();
            assertThat(success).as("仅一个操作成功").isEqualTo(1);

            String status = scheduling2.planSchedules(PATIENT, PLAN, null).get(0).status();
            if (cancelR.httpStatus() == 200) {
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(execR.httpStatus()).isEqualTo(422);
                assertThat(workflow2.fractionLedger(PATIENT, PLAN)).isEmpty();
            } else {
                assertThat(status).isEqualTo("EXECUTED");
                // 执行先提交后排程版本已为2，取消必失败（409版本冲突或422非BOOKED）
                assertThat(cancelR.httpStatus()).isIn(409, 422);
                assertThat(workflow2.fractionLedger(PATIENT, PLAN)).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("能力下调与发布竞态串行化：结果自洽，等级/占用不矛盾")
    void downgradeVersusPublishRaceConsistent() throws Exception {
        approvedVerifiedPlan();
        registerDevice(3);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> publishF = pool.submit(() -> {
                ready.countDown();
                start.await();
                // 等级3、30分钟窗口
                return scheduling2.publish(new SchedulePublishRequest(PATIENT, PLAN, 1, "CC-DP",
                        List.of(new ScheduleItemRequest(1, DEVICE, 3, T0, T0.plusMinutes(30)))));
            });
            Future<RecordedResponse> downgradeF = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling2.downgradeCapability(
                        new DeviceCapabilityRequest(DEVICE, 1, 1, "CC-DOWN"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RecordedResponse publishR = publishF.get(30, TimeUnit.SECONDS);
            RecordedResponse downgradeR = downgradeF.get(30, TimeUnit.SECONDS);

            // 下调必定成功（仅与设备锁串行）；发布成功与否取决于锁顺序
            assert200(downgradeR);
            assertThat(scheduling2.devices().get(0).capabilityLevel()).isEqualTo(1);
            long booked = scheduling2.planSchedules(PATIENT, PLAN, "BOOKED").size();
            if (publishR.httpStatus() == 200) {
                assertThat(booked).isEqualTo(1);
            } else {
                assertThat(publishR.httpStatus()).isEqualTo(422);
                assertThat(booked).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 兼容 record 与重放 Map 两种响应体读取 replayed。 */
    private static final class ReplayHelper {
        @SuppressWarnings("unchecked")
        static boolean replayed(Object body) {
            if (body instanceof java.util.Map<?, ?> map) {
                return Boolean.TRUE.equals(map.get("replayed"));
            }
            if (body instanceof com.example.starter.rt.dto.SchedulePublishResponse pub) {
                return pub.replayed();
            }
            throw new IllegalStateException("未知响应体：" + body.getClass());
        }
    }
}
