package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;
import com.example.starter.rt.support.MutableClock;
import com.example.starter.rt.web.ClockConfig;

/**
 * 排程门禁并发边界 H2 测试：真实多线程 + 行锁，验证发布/取消/执行竞态形成唯一结果。
 */
class SchedulingConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-27T01:00:00Z");
    private static final MutableClock MUTABLE = new MutableClock(T0, ClockConfig.BUSINESS_ZONE);

    private static final String TPL = "TPLC";
    private static final String DEVICE = "DEV_CC";

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MUTABLE;
        }
    }

    @Autowired
    private SchedulingService scheduling;

    @Autowired
    private WorkflowService workflow;

    @BeforeEach
    void resetClock() {
        MUTABLE.setInstant(T0);
    }

    private void setupVerifiedPlan(String patientId, String planCode) {
        patient(patientId, "并发排程患者");
        createPlanVersion(patientId, planCode, 1, TPL, 5000L, 200L, 25, 4400L,
                new BigDecimal("35.00"));
        workflow.audit(patientId, planCode, 1, "CC-AUD-" + planCode);
        workflow.approve(patientId, planCode, 1, "CC-APV-" + planCode);
        workflow.verify(patientId, planCode, 1, 200L, 200L, "CC-VER-" + planCode);
    }

    private ScheduleItemRequest window(int fractionNo, String startIso, int minutes) {
        OffsetDateTime start = OffsetDateTime.parse(startIso);
        return new ScheduleItemRequest(fractionNo, start, start.plusMinutes(minutes));
    }

    @Test
    @DisplayName("并发发布同设备重叠窗口（不同患者）：唯一成功占用，另一个422，占用恰好1项")
    void concurrentPublishSameDeviceOverlap() throws Exception {
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        setupVerifiedPlan("PC1", "PLAN_C1");
        setupVerifiedPlan("PC2", "PLAN_C2");
        scheduling.registerDevice(new DeviceRegisterRequest(DEVICE, "合成设备", 3, "CC-DEV"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.publish(new SchedulePublishRequest("PC1", "PLAN_C1", 1, DEVICE,
                        List.of(window(1, "2026-09-27T01:00:00Z", 10)), "CC-PUB-1"));
            });
            Future<RecordedResponse> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.publish(new SchedulePublishRequest("PC2", "PLAN_C2", 1, DEVICE,
                        List.of(window(1, "2026-09-27T01:00:00Z", 10)), "CC-PUB-2"));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(f1.get(20, TimeUnit.SECONDS).httpStatus(),
                    f2.get(20, TimeUnit.SECONDS).httpStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
            // 设备占用恰好 1 项：整批原子占用，负者零占用
            assertThat(scheduling.occupancy(DEVICE)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("取消与执行竞态：唯一结果——要么先执行完成保留实绩，要么先取消后治疗422")
    void concurrentTreatVsCancelUniqueOutcome() throws Exception {
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        setupVerifiedPlan("PC3", "PLAN_C3");
        scheduling.registerDevice(new DeviceRegisterRequest(DEVICE, "合成设备", 3, "CC-DEV3"));
        RecordedResponse published = scheduling.publish(new SchedulePublishRequest(
                "PC3", "PLAN_C3", 3, DEVICE,
                List.of(window(1, "2026-09-27T01:00:00Z", 30)), "CC-PUB-3"));
        long scheduleId = ((ScheduleResponse) published.body()).scheduleId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> treatFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.treat(new TreatmentRequest("PC3", "PLAN_C3", 1, 200L, "CC-TRT"));
            });
            Future<RecordedResponse> cancelFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.cancel(scheduleId, new ScheduleCancelRequest(1, "CC-CXL"));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RecordedResponse treat = treatFuture.get(20, TimeUnit.SECONDS);
            RecordedResponse cancel = cancelFuture.get(20, TimeUnit.SECONDS);

            // 取消一定成功（先取消则直接成功；后取消则取消剩余占用中项，已完成项保留）
            assertThat(cancel.httpStatus()).isEqualTo(200);
            ScheduleResponse schedule = scheduling.schedules("PC3", "PLAN_C3").get(0);
            if (treat.httpStatus() == 200) {
                // 先执行：分次台账 1 条，排程项 COMPLETED 且实绩冻结
                assertThat(workflow.fractionLedger("PC3", "PLAN_C3")).hasSize(1);
                assertThat(schedule.items().get(0).status()).isEqualTo("COMPLETED");
                assertThat(schedule.items().get(0).deliveredDoseCgy()).isEqualTo(200L);
            } else {
                // 先取消：治疗 422，台账为空，排程项 CANCELLED
                assertThat(treat.httpStatus()).isEqualTo(422);
                assertThat(workflow.fractionLedger("PC3", "PLAN_C3")).isEmpty();
                assertThat(schedule.items().get(0).status()).isEqualTo("CANCELLED");
            }
            assertThat(schedule.status()).isEqualTo("CANCELLED");
            assertThat(scheduling.occupancy(DEVICE)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发同键发布：唯一生效，负者重放首次冻结结果，排程恰好1批")
    void concurrentSameRequestIdPublishReplays() throws Exception {
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        setupVerifiedPlan("PC4", "PLAN_C4");
        scheduling.registerDevice(new DeviceRegisterRequest(DEVICE, "合成设备", 3, "CC-DEV4"));

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
                return scheduling.publish(new SchedulePublishRequest("PC4", "PLAN_C4", 1, DEVICE,
                        List.of(window(1, "2026-09-27T01:00:00Z", 10)), "CC-PUB-SAME"));
            };
            Future<RecordedResponse> f1 = pool.submit(task::get);
            Future<RecordedResponse> f2 = pool.submit(task::get);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RecordedResponse r1 = f1.get(20, TimeUnit.SECONDS);
            RecordedResponse r2 = f2.get(20, TimeUnit.SECONDS);
            assertThat(r1.httpStatus()).isEqualTo(201);
            assertThat(r2.httpStatus()).isEqualTo(201);

            boolean replayed1 = replayed(r1.body());
            boolean replayed2 = replayed(r2.body());
            assertThat(replayed1).as("恰好一个为重放").isNotEqualTo(replayed2);

            // 排程恰好 1 批、占用恰好 1 项
            assertThat(scheduling.schedules("PC4", "PLAN_C4")).hasSize(1);
            assertThat(scheduling.occupancy(DEVICE)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 兼容 record 与反序列化 Map 两种响应体取 replayed 标记。 */
    private static boolean replayed(Object body) {
        if (body instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("replayed"));
        }
        if (body instanceof ScheduleResponse response) {
            return response.replayed();
        }
        throw new IllegalStateException("未知响应体类型：" + body.getClass());
    }
}
