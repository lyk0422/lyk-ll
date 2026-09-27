package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.domain.ScheduleItemStatus;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.OccupancyItemView;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleFractionItem;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.SchedulePublishResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;

/**
 * 排程并发边界 H2 测试：真实多线程 + 行锁，验证同设备重叠发布唯一成功、
 * 取消与执行竞态唯一结果、同键并发发布重放不双占。
 */
class SchedulingConcurrencyTest extends AbstractIntegrationTest {

    private static final String TPL = "TPLC";
    private static final String DEVICE = "DVC";
    private static final Instant FUTURE = Instant.parse("2026-12-01T00:00:00Z");

    @Autowired
    private SchedulingService scheduling;

    @Autowired
    private WorkflowService workflow;

    @Autowired
    private SchedulingRepository schedulingRepository;

    /** 准备患者及其已验证合格计划（每患者独立计划编码）。 */
    private void setupVerifiedPlan(String patientId, String planCode) {
        patient(patientId, "并发排程患者" + patientId);
        if (!directoryRepository.templateExists(TPL)) {
            pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        }
        createPlanVersion(patientId, planCode, 1, TPL, 5000L, 200L, 25,
                4400L, new BigDecimal("35.00"));
        assertThat(workflow.audit(patientId, planCode, 1, "C-AUD-" + patientId).httpStatus())
                .isEqualTo(200);
        assertThat(workflow.approve(patientId, planCode, 1, "C-APV-" + patientId).httpStatus())
                .isEqualTo(200);
        assertThat(workflow.verify(patientId, planCode, 1, 200L, 200L, "C-VER-" + patientId)
                .httpStatus()).isEqualTo(200);
    }

    private void device(String deviceId, int level) {
        assertThat(scheduling.registerDevice(
                new DeviceRegisterRequest(deviceId, "合成设备", level, "REG-" + deviceId))
                .httpStatus()).isEqualTo(201);
    }

    private SchedulePublishRequest publishReq(String patientId, String planCode, String deviceId,
                                              String reqId, int fractionNo, Instant windowStart) {
        return new SchedulePublishRequest(patientId, planCode, 1, deviceId,
                List.of(new ScheduleFractionItem(fractionNo, windowStart.atOffset(ZoneOffset.UTC),
                        windowStart.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC))),
                reqId);
    }

    @Test
    @DisplayName("并发同设备重叠发布：唯一成功，另一个409，占用恰好一份")
    void concurrentOverlappingPublishOnlyOneSucceeds() throws Exception {
        setupVerifiedPlan("PC1", "PLAN_C1");
        setupVerifiedPlan("PC2", "PLAN_C2");
        device(DEVICE, 3);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.publish(publishReq("PC1", "PLAN_C1", DEVICE, "CP-1", 1, FUTURE));
            });
            Future<RecordedResponse> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.publish(publishReq("PC2", "PLAN_C2", DEVICE, "CP-2", 1, FUTURE));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(f1.get(20, TimeUnit.SECONDS).httpStatus(),
                    f2.get(20, TimeUnit.SECONDS).httpStatus());

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            // 占用恰好一份（一个批次一个占用项）
            List<OccupancyItemView> occupancy = scheduling.occupancy(DEVICE, null, null);
            assertThat(occupancy).hasSize(1);
            assertThat(occupancy.get(0).status()).isEqualTo(ScheduleItemStatus.SCHEDULED.name());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("取消与执行竞态：唯一结果，要么完成要么取消，状态与之一致")
    void cancelVersusTreatRaceHasUniqueOutcome() throws Exception {
        setupVerifiedPlan("PC3", "PLAN_C3");
        device(DEVICE, 3);
        Instant nowStart = clock.instant().minus(5, ChronoUnit.MINUTES);
        RecordedResponse published = scheduling.publish(
                publishReq("PC3", "PLAN_C3", DEVICE, "CP-3", 1, nowStart));
        assertThat(published.httpStatus()).isEqualTo(201);
        long scheduleId = ((SchedulePublishResponse) published.body()).scheduleId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> treat = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.treat(new TreatmentRequest("PC3", "PLAN_C3", scheduleId, 1,
                        200L, "CT-T"));
            });
            Future<RecordedResponse> cancel = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.cancel(scheduleId, 1, "CT-C");
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RecordedResponse treatResult = treat.get(20, TimeUnit.SECONDS);
            RecordedResponse cancelResult = cancel.get(20, TimeUnit.SECONDS);

            // 唯一结果：恰好一个成功（200），另一个 422
            assertThat(List.of(treatResult.httpStatus(), cancelResult.httpStatus()))
                    .containsExactlyInAnyOrder(200, 422);

            // 终态与胜者一致：治疗胜则 COMPLETED 且台账入账；取消胜则 CANCELLED 且台账为空
            var item = schedulingRepository.findItemsByBatch(scheduleId).get(0);
            if (treatResult.httpStatus() == 200) {
                assertThat(item.status()).isEqualTo(ScheduleItemStatus.COMPLETED.name());
                assertThat(item.deliveredDoseCgy()).isEqualTo(200L);
                assertThat(workflow.fractionLedger("PC3", "PLAN_C3")).hasSize(1);
            } else {
                assertThat(item.status()).isEqualTo(ScheduleItemStatus.CANCELLED.name());
                assertThat(workflow.fractionLedger("PC3", "PLAN_C3")).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发同键发布：唯一生效，另一个重放首次结果，不产生双占")
    void concurrentSameRequestIdPublishReplays() throws Exception {
        setupVerifiedPlan("PC4", "PLAN_C4");
        device(DEVICE, 3);
        SchedulePublishRequest request = publishReq("PC4", "PLAN_C4", DEVICE, "CP-SAME", 1, FUTURE);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.publish(request);
            });
            Future<RecordedResponse> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return scheduling.publish(request);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RecordedResponse r1 = f1.get(20, TimeUnit.SECONDS);
            RecordedResponse r2 = f2.get(20, TimeUnit.SECONDS);

            assertThat(r1.httpStatus()).isEqualTo(201);
            assertThat(r2.httpStatus()).isEqualTo(201);
            assertThat(replayed(r1.body())).as("恰好一个为重放").isNotEqualTo(replayed(r2.body()));

            // 不双占：恰好一个批次一个占用项
            assertThat(scheduling.occupancy(DEVICE, null, null)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 兼容 record 响应体与重放反序列化 Map 两种形态读取 replayed 标记。 */
    private static boolean replayed(Object body) {
        if (body instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("replayed"));
        }
        if (body instanceof SchedulePublishResponse response) {
            return response.replayed();
        }
        throw new IllegalStateException("未知响应体类型：" + body.getClass());
    }
}
