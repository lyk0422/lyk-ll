package com.example.starter.survey.service;

import com.example.starter.survey.AbstractIntegrationTest;
import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实并发（多线程 + H2 行锁/唯一约束）验证：
 * 同船同类型进行中检验唯一受理、同 requestId 并发唯一执行、并发发证原子交接。
 */
class SurveyConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String SHIP1 = "IMO9000001";

    @Autowired
    private SurveyService service;

    /** 不同 requestId 并发申请同船同类型：恰好一个受理，另一个 409。 */
    @Test
    void concurrentApplyOnlyOneAccepted() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Long> acceptedId = new AtomicReference<>();
        AtomicReference<String> rejected = new AtomicReference<>();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String requestId = "r-concurrent-apply-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    Idempotency.Result r = service.applySurvey(
                            new Dtos.ApplySurveyRequest(requestId, SHIP1, "ANNUAL"));
                    acceptedId.compareAndSet(null, surveyId(r.body()));
                } catch (ApiException e) {
                    rejected.compareAndSet(null, e.getStatus().value() + ":" + e.getMessage());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertNotNull(acceptedId.get(), "应有一个申请被受理");
        assertNotNull(rejected.get(), "另一个并发申请应被拒绝");
        assertTrue(rejected.get().startsWith("409"), "拒绝应为 409，实际：" + rejected.get());
        assertEquals(1L, count("survey"), "同船同类型同时刻只允许一个进行中检验");
        assertEquals(2L, count("idempotent_request"), "失败请求也应固化各自 requestId 快照");
    }

    /** 同一 requestId 同参数并发：两边都成功，唯一执行业务并返回同一张检验单。 */
    @Test
    void concurrentSameRequestIdExecutesOnce() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                Idempotency.Result r = service.applySurvey(
                        new Dtos.ApplySurveyRequest("r-same-key", SHIP1, "ANNUAL"));
                return surveyId(r.body());
            }));
        }
        start.countDown();
        Long first = futures.get(0).get(20, TimeUnit.SECONDS);
        Long second = futures.get(1).get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(first, second, "并发同键必须返回首次创建的同一张检验单");
        assertEquals(1L, count("survey"));
        assertEquals(1L, count("idempotent_request"));
    }

    /** 不同 requestId 并发对同一检验单发证：行锁串行，仅一张证书，两边返回同一证书。 */
    @Test
    void concurrentIssueProducesSingleCertificate() throws Exception {
        Long surveyId = setupReadySurvey("r-c-apply", "r-c-board");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String requestId = "r-c-issue-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                Idempotency.Result r = service.issueCertificate(
                        new Dtos.IssueCertificateRequest(requestId, surveyId,
                                LocalDate.of(2026, 6, 16)));
                return certId(r.body());
            }));
        }
        start.countDown();
        Long first = futures.get(0).get(20, TimeUnit.SECONDS);
        Long second = futures.get(1).get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(first, second, "并发发证必须返回同一张证书");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id = 1 AND type_code = 'ANNUAL'",
                Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE valid_flag = 1", Long.class));
    }

    /** 不同 requestId 并发批准同一展期：行锁串行，证书只延长一次。 */
    @Test
    void concurrentApproveExtensionUpdatesCertificateOnce() throws Exception {
        Long surveyId = setupReadySurvey("r-e-apply", "r-e-board");
        service.issueCertificate(new Dtos.IssueCertificateRequest(
                "r-e-issue", surveyId, LocalDate.of(2026, 6, 16)));
        Idempotency.Result extResult = service.applyExtension(
                new Dtos.ApplyExtensionRequest("r-e-ext-apply", SHIP1, "ANNUAL", 3));
        long extensionId = ((Dtos.ExtensionView) extResult.body()).id();
        LocalDate expectedUntil = LocalDate.of(2027, 12, 15);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String requestId = "r-e-ext-approve-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                Idempotency.Result r = service.approveExtension(
                        new Dtos.ApproveExtensionRequest(requestId, extensionId));
                return r.body();
            }));
        }
        start.countDown();
        futures.get(0).get(20, TimeUnit.SECONDS);
        futures.get(1).get(20, TimeUnit.SECONDS);
        pool.shutdown();

        // 批准与证书更新同事务：最终有效期恰延长一次 3 个月，而非两次。
        assertEquals(expectedUntil, service.getValidCertificate(SHIP1, "ANNUAL").validUntil());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_application WHERE certificate_id IN "
                        + "(SELECT id FROM certificate WHERE ship_id = 1 AND type_code = 'ANNUAL') "
                        + "AND approved_flag = 1", Long.class));
    }

    private Long setupReadySurvey(String applyId, String boardId) {
        Idempotency.Result apply = service.applySurvey(
                new Dtos.ApplySurveyRequest(applyId, SHIP1, "ANNUAL"));
        Long surveyId = surveyId(apply.body());
        service.boardSurvey(new Dtos.BoardSurveyRequest(boardId, surveyId,
                LocalDate.of(2026, 6, 15), List.of()));
        return surveyId;
    }

    private Long surveyId(Object body) {
        if (body instanceof Dtos.SurveyView v) {
            return v.id();
        }
        return ((JsonNode) body).get("id").asLong();
    }

    private Long certId(Object body) {
        if (body instanceof Dtos.CertificateView v) {
            return v.id();
        }
        return ((JsonNode) body).get("id").asLong();
    }
}
