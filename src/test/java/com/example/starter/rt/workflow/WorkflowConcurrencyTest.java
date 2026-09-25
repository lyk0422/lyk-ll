package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.service.WorkflowService;

/**
 * 并发边界 H2 测试：真实多线程 + 行锁，验证并发批准唯一成功、同键并发重放、分次自然键并发去重。
 */
class WorkflowConcurrencyTest extends AbstractIntegrationTest {

    private static final String PATIENT = "P3";
    private static final String TPL = "TPL3";

    @Autowired
    private WorkflowService workflow;

    @Test
    @DisplayName("并发批准两个不同计划：唯一成功，另一个409，守卫表仅一行")
    void concurrentApprovalOnlyOneSucceeds() throws Exception {
        patient(PATIENT, "并发患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, "PLAN_X", 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        createPlanVersion(PATIENT, "PLAN_Y", 1, TPL, 6000L, 200L, 30, 4300L, new BigDecimal("30.00"));
        workflow.audit(PATIENT, "PLAN_X", 1, "C-AUD-X");
        workflow.audit(PATIENT, "PLAN_Y", 1, "C-AUD-Y");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> fx = pool.submit(() -> {
                ready.countDown();
                start.await();
                return workflow.approve(PATIENT, "PLAN_X", 1, "C-APV-X");
            });
            Future<RecordedResponse> fy = pool.submit(() -> {
                ready.countDown();
                start.await();
                return workflow.approve(PATIENT, "PLAN_Y", 1, "C-APV-Y");
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(fx.get(20, TimeUnit.SECONDS).httpStatus(),
                    fy.get(20, TimeUnit.SECONDS).httpStatus());

            long success = statuses.stream().filter(s -> s == 200).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertThat(success).as("恰好一个批准成功").isEqualTo(1);
            assertThat(conflict).as("另一个被409拒绝").isEqualTo(1);

            // 守卫表只有一行，且执行中计划唯一
            var active = planRepository.findActivePlan(PATIENT);
            assertThat(active).isPresent();
            assertThat(active.get().status()).isEqualTo("ACTIVE");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发同键批准同一版本：唯一生效，后到者重放首次结果（200 replayed），无重复批准")
    void concurrentSameRequestIdApprovalReplays() throws Exception {
        patient(PATIENT, "并发患者2");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, "PLAN_Z", 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        workflow.audit(PATIENT, "PLAN_Z", 1, "C2-AUD");

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
                return workflow.approve(PATIENT, "PLAN_Z", 1, "C2-SAME-REQ");
            };
            Future<RecordedResponse> f1 = pool.submit(task::get);
            Future<RecordedResponse> f2 = pool.submit(task::get);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RecordedResponse r1 = f1.get(20, TimeUnit.SECONDS);
            RecordedResponse r2 = f2.get(20, TimeUnit.SECONDS);

            assertThat(r1.httpStatus()).isEqualTo(200);
            assertThat(r2.httpStatus()).isEqualTo(200);
            // 首次响应为 ApprovalResponse record，重放响应为反序列化 Map，分别取 replayed 标记。
            boolean rp1 = StreamHelper.replayed(r1.body());
            boolean rp2 = StreamHelper.replayed(r2.body());
            assertThat(rp1).as("恰好一个为重放").isNotEqualTo(rp2);

            // 幂等表仅一条
            assertThat(workflow.fractionLedger(PATIENT, "PLAN_Z")).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发登记同一分次号（不同requestId）：唯一写入，另一个409，台账恰好1条")
    void concurrentFractionNaturalKeyDedup() throws Exception {
        patient(PATIENT, "并发患者3");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, "PLAN_W", 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        workflow.audit(PATIENT, "PLAN_W", 1, "C3-AUD");
        workflow.approve(PATIENT, "PLAN_W", 1, "C3-APV");
        workflow.verify(PATIENT, "PLAN_W", 1, 200L, 200L, "C3-VER");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordedResponse> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return workflow.recordFraction(PATIENT, "PLAN_W", 1, 200L, "C3-FR-1");
            });
            Future<RecordedResponse> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return workflow.recordFraction(PATIENT, "PLAN_W", 1, 200L, "C3-FR-2");
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(f1.get(20, TimeUnit.SECONDS).httpStatus(),
                    f2.get(20, TimeUnit.SECONDS).httpStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);

            // 台账恰好 1 条，且为第 1 分次
            var ledger = workflow.fractionLedger(PATIENT, "PLAN_W");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).fractionNo()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 测试内小工具：兼容 record（含 replayed() 访问器）与反序列化 Map 两种响应体。 */
    private static final class StreamHelper {
        static boolean replayed(Object body) {
            if (body instanceof java.util.Map<?, ?> map) {
                return Boolean.TRUE.equals(map.get("replayed"));
            }
            if (body instanceof com.example.starter.rt.dto.ApprovalResponse approval) {
                return approval.replayed();
            }
            throw new IllegalStateException("未知响应体类型：" + body.getClass());
        }
    }
}
