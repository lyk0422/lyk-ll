package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.dto.CreateDeviceRequest;
import com.example.starter.rt.dto.DeviceOccupancyItem;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleBatchResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.service.ScheduleService;
import com.example.starter.rt.service.WorkflowService;

/**
 * 排程并发边界 H2 测试：真实多线程 + 行锁，验证同设备窗口占用唯一成功、
 * 取消与执行竞态唯一结果、同键并发重放。
 */
class ScheduleConcurrencyTest extends AbstractIntegrationTest {

    private static final String TPL = "TPL_C";

    @Autowired
    private WorkflowService workflow;

    @Autowired
    private ScheduleService scheduleService;

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private void verifiedPlan(String patientId, String planCode, int fractionCount) {
        patient(patientId, "并发排程患者");
        String templateCode = TPL + "_" + patientId;
        pelvisTemplate(templateCode, 4500L, new BigDecimal("40.00"));
        createPlanVersion(patientId, planCode, 1, templateCode, 200L * fractionCount, 200L,
                fractionCount, 4400L, new BigDecimal("35.00"));
        workflow.audit(patientId, planCode, 1, rid());
        workflow.approve(patientId, planCode, 1, rid());
        workflow.verify(patientId, planCode, 1, 200L, 200L, rid());
    }

    private SchedulePublishRequest publishRequest(String patientId, String planCode,
                                                  String deviceId, String requestId,
                                                  int fractionNo, Instant start) {
        return new SchedulePublishRequest(patientId, planCode, 1, deviceId, 1,
                List.of(new ScheduleItemRequest(fractionNo, start,
                        start.plus(10, ChronoUnit.MINUTES))), requestId);
    }

    @Test
    @DisplayName("并发发布同设备同窗口（不同患者）：唯一占用成功，另一个422，占用仅一批")
    void concurrentPublishSameDeviceWindow() throws Exception {
        verifiedPlan("PC_A", "PLAN_CA", 5);
        verifiedPlan("PC_B", "PLAN_CB", 5);
        scheduleService.createDevice(new CreateDeviceRequest("DEV_CC", "合成加速器", 3));
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> fa = pool.submit(() -> {
                ready.countDown();
                go.await();
                return scheduleService.publish(
                        publishRequest("PC_A", "PLAN_CA", "DEV_CC", "CC-PUB-A", 1, start));
            });
            Future<RecordedResponse> fb = pool.submit(() -> {
                ready.countDown();
                go.await();
                return scheduleService.publish(
                        publishRequest("PC_B", "PLAN_CB", "DEV_CC", "CC-PUB-B", 1, start));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = List.of(fa.get(20, TimeUnit.SECONDS).httpStatus(),
                    fb.get(20, TimeUnit.SECONDS).httpStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 422);

            List<DeviceOccupancyItem> occupancy = scheduleService.occupancy("DEV_CC", null, null);
            assertThat(occupancy).hasSize(1);
            assertThat(occupancy.get(0).status()).isEqualTo("ACTIVE");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("取消与执行竞态：唯一结果，要么执行完成释放，要么取消释放，不能两者都生效")
    void concurrentCancelVsTreat() throws Exception {
        verifiedPlan("PC_C", "PLAN_CC", 5);
        scheduleService.createDevice(new CreateDeviceRequest("DEV_CT", "合成加速器", 3));
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        RecordedResponse published = scheduleService.publish(
                publishRequest("PC_C", "PLAN_CC", "DEV_CT", "CC-PUB-C", 1, start));
        assertThat(published.httpStatus()).isEqualTo(200);
        long batchId = ((ScheduleBatchResponse) published.body()).batchId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> cancelFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return scheduleService.cancel(batchId, new ScheduleCancelRequest(1, "CC-CXL"));
            });
            Future<RecordedResponse> treatFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return scheduleService.treat(new TreatmentRequest("PC_C", "PLAN_CC", 1, 200L, "CC-TRT"));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            RecordedResponse cancelResult = cancelFuture.get(20, TimeUnit.SECONDS);
            RecordedResponse treatResult = treatFuture.get(20, TimeUnit.SECONDS);

            long successes = List.of(cancelResult.httpStatus(), treatResult.httpStatus())
                    .stream().filter(s -> s == 200).count();
            assertThat(successes).as("取消与执行恰好一个成功").isEqualTo(1);

            List<DeviceOccupancyItem> occupancy = scheduleService.occupancy("DEV_CT", null, null);
            assertThat(occupancy).hasSize(1);
            if (treatResult.httpStatus() == 200) {
                // 执行胜：台账 1 条，明细 COMPLETED 实绩冻结，取消被拒
                assertThat(workflow.fractionLedger("PC_C", "PLAN_CC")).hasSize(1);
                assertThat(occupancy.get(0).status()).isEqualTo("COMPLETED");
                assertThat(cancelResult.httpStatus()).isIn(409, 422);
            } else {
                // 取消胜：无台账，明细 CANCELLED，执行被拒
                assertThat(cancelResult.httpStatus()).isEqualTo(200);
                assertThat(workflow.fractionLedger("PC_C", "PLAN_CC")).isEmpty();
                assertThat(occupancy.get(0).status()).isEqualTo("CANCELLED");
                assertThat(treatResult.httpStatus()).isEqualTo(422);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发同键同参发布：唯一生效，另一个重放首次冻结结果，仅一批")
    void concurrentSameRequestIdPublish() throws Exception {
        verifiedPlan("PC_D", "PLAN_CD", 5);
        scheduleService.createDevice(new CreateDeviceRequest("DEV_CD", "合成加速器", 3));
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        SchedulePublishRequest request =
                publishRequest("PC_D", "PLAN_CD", "DEV_CD", "CC-SAME", 1, start);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return scheduleService.publish(request);
            });
            Future<RecordedResponse> f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return scheduleService.publish(request);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            RecordedResponse r1 = f1.get(20, TimeUnit.SECONDS);
            RecordedResponse r2 = f2.get(20, TimeUnit.SECONDS);
            assertThat(r1.httpStatus()).isEqualTo(200);
            assertThat(r2.httpStatus()).isEqualTo(200);

            boolean replayed1 = replayed(r1.body());
            boolean replayed2 = replayed(r2.body());
            assertThat(replayed1).as("恰好一个为重放").isNotEqualTo(replayed2);
            assertThat(scheduleService.schedulesOfPlan("PC_D", "PLAN_CD")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 首次响应为 ScheduleBatchResponse，重放响应为反序列化 Map。 */
    @SuppressWarnings("unchecked")
    private static boolean replayed(Object body) {
        if (body instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("replayed"));
        }
        return ((ScheduleBatchResponse) body).replayed();
    }
}
