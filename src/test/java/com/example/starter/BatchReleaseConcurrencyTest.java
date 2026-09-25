package com.example.starter;

import com.example.starter.repository.BatchReleaseRepository;
import com.example.starter.service.BatchReleaseService;
import com.example.starter.service.ServiceResponse;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ApplicationRequest;
import com.example.starter.web.dto.ObjectionRequest;
import com.example.starter.web.dto.SimpleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批签发并发与幂等边界测试（真实并发 + H2 唯一约束与条件更新）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BatchReleaseConcurrencyTest {

    private static final String ENTERPRISE = "合成企业B";
    private static final String PRODUCT = "合成疫苗Y";

    @Autowired
    private BatchReleaseService service;
    @Autowired
    private BatchReleaseRepository batchRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM late_document_event");
        jdbc.update("DELETE FROM enterprise_suspension");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM publicity_record");
        jdbc.update("DELETE FROM inspection_item");
        jdbc.update("DELETE FROM document_item");
        jdbc.update("DELETE FROM review_process");
        jdbc.update("DELETE FROM vaccine_batch");
        clock.set(TestClockConfig.BASE_TIME);
    }

    @Test
    void concurrentApplicationsForSameBatchAreUniquelyAccepted() throws Exception {
        List<Callable<ServiceResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String requestId = "req-con-app-" + i;
            tasks.add(() -> service.apply(new ApplicationRequest(requestId, "B2026100", ENTERPRISE, PRODUCT,
                    allDocs(), passingInspections())));
        }
        List<ServiceResponse> results = runConcurrently(tasks);

        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 201).count(),
                "并发申报须唯一受理");
        assertEquals(7, results.stream().filter(r -> r.httpStatus() == 409).count());
        assertEquals(1, countRows("vaccine_batch"));
        assertEquals(1, countRows("review_process"));
    }

    @Test
    void concurrentIdenticalReplayKeepsSingleProcess() throws Exception {
        ApplicationRequest request = new ApplicationRequest("req-con-same", "B2026101", ENTERPRISE, PRODUCT,
                allDocs(), passingInspections());
        List<Callable<ServiceResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.apply(request));
        }
        List<ServiceResponse> results = runConcurrently(tasks);

        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 201).count());
        assertEquals(1, countRows("review_process"));
        assertEquals(1, countRows("idempotency_record"));
    }

    @Test
    void issueAndObjectionRaceYieldsUniqueTerminalState() throws Exception {
        applyAndEvaluatePass("B2026110", "req-a110");
        // 公示恰满7个自然日：签发与异议并发，只能形成唯一终态
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)));
        List<ServiceResponse> results = runConcurrently(List.of(
                () -> service.issue("B2026110", new SimpleRequest("req-iss-110")),
                () -> service.object("B2026110", new ObjectionRequest("req-obj-110", "合成异议"))));

        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 200).count());
        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 409).count());
        // 唯一终态：已签发，公示期满生效，异议未受理（不得既签发又受理异议）
        assertEquals("ISSUED",
                batchRepository.findProcessByBatchNo("B2026110").orElseThrow().status().name());
        Integer objections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM publicity_record p JOIN review_process r ON r.id = p.process_id "
                        + "JOIN vaccine_batch b ON b.id = r.batch_id WHERE b.batch_no = 'B2026110' AND p.status = 'OBJECTION'",
                Integer.class);
        assertEquals(0, objections == null ? 0 : objections);
    }

    @Test
    void objectionBeforeExpiryBlocksConcurrentIssue() throws Exception {
        applyAndEvaluatePass("B2026111", "req-a111");
        // 公示期内：异议与签发并发，唯一终态为异议复核
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(1)));
        List<ServiceResponse> results = runConcurrently(List.of(
                () -> service.object("B2026111", new ObjectionRequest("req-obj-111", "合成异议")),
                () -> service.issue("B2026111", new SimpleRequest("req-iss-111"))));

        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 200).count());
        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 409).count());
        assertEquals("OBJECTION_REVIEW",
                batchRepository.findProcessByBatchNo("B2026111").orElseThrow().status().name());
    }

    @Test
    void concurrentIssuesAfterExpirySingleAcceptance() throws Exception {
        applyAndEvaluatePass("B2026112", "req-a112");
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)));
        List<Callable<ServiceResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String requestId = "req-con-iss-" + i;
            tasks.add(() -> service.issue("B2026112", new SimpleRequest(requestId)));
        }
        List<ServiceResponse> results = runConcurrently(tasks);

        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 200).count());
        assertEquals(5, results.stream().filter(r -> r.httpStatus() == 409).count());
        assertEquals("ISSUED",
                batchRepository.findProcessByBatchNo("B2026112").orElseThrow().status().name());
    }

    @Test
    void concurrentObjectionsWithinPeriodSingleAcceptance() throws Exception {
        applyAndEvaluatePass("B2026113", "req-a113");
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(1)));
        List<Callable<ServiceResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String requestId = "req-con-obj-" + i;
            String reason = "合成异议" + i;
            tasks.add(() -> service.object("B2026113", new ObjectionRequest(requestId, reason)));
        }
        List<ServiceResponse> results = runConcurrently(tasks);

        assertEquals(1, results.stream().filter(r -> r.httpStatus() == 200).count());
        assertEquals(5, results.stream().filter(r -> r.httpStatus() == 409).count());
        assertEquals("OBJECTION_REVIEW",
                batchRepository.findProcessByBatchNo("B2026113").orElseThrow().status().name());
    }

    /**
     * 真实并发执行：所有任务就绪后同时放行，带超时保护。
     */
    private List<ServiceResponse> runConcurrently(List<Callable<ServiceResponse>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ServiceResponse>> futures = new ArrayList<>();
            for (Callable<ServiceResponse> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "并发放行超时");
                    try {
                        return task.call();
                    } catch (ApiException e) {
                        return new ServiceResponse(e.getStatus(), "{\"error\":true}");
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发就绪超时");
            start.countDown();
            List<ServiceResponse> results = new ArrayList<>();
            for (Future<ServiceResponse> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void applyAndEvaluatePass(String batchNo, String requestPrefix) {
        service.apply(new ApplicationRequest(requestPrefix + "-app", batchNo, ENTERPRISE, PRODUCT,
                allDocs(), passingInspections()));
        service.evaluate(batchNo, new SimpleRequest(requestPrefix + "-eval"));
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static List<ApplicationRequest.DocumentItem> allDocs() {
        return List.of(new ApplicationRequest.DocumentItem("LICENSE"),
                new ApplicationRequest.DocumentItem("QUALITY_STD"),
                new ApplicationRequest.DocumentItem("SELF_TEST_REPORT"),
                new ApplicationRequest.DocumentItem("PROCESS_RECORD"),
                new ApplicationRequest.DocumentItem("SUMMARY"));
    }

    private static List<ApplicationRequest.InspectionItem> passingInspections() {
        return List.of(
                new ApplicationRequest.InspectionItem("POTENCY", null, new BigDecimal("10"), new BigDecimal("8")),
                new ApplicationRequest.InspectionItem("STERILITY", null, new BigDecimal("5"), new BigDecimal("5")),
                new ApplicationRequest.InspectionItem("ABNORMAL_TOXICITY", null, new BigDecimal("2"), new BigDecimal("1")));
    }
}
