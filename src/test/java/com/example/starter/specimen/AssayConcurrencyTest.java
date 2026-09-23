package com.example.starter.specimen;

import com.example.starter.specimen.error.ApiException;
import com.example.starter.specimen.model.AliquotStatus;
import com.example.starter.specimen.service.AssayService;
import com.example.starter.specimen.service.SpecimenService;
import com.example.starter.specimen.web.dto.AliquotRequest;
import com.example.starter.specimen.web.dto.AssayCreateRequest;
import com.example.starter.specimen.web.dto.AssayResponse;
import com.example.starter.specimen.web.dto.IntakeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发占用测试：真实 H2 与真实线程，验证同一分装上的并发申请不能超额占用，
 * 以及同键并发请求重放首次结果。所有任务通过门闩同时放行，并设置超时。
 */
@SpringBootTest
class AssayConcurrencyTest {

    @Autowired
    private SpecimenService specimenService;

    @Autowired
    private AssayService assayService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM custody_event");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM assay_request");
        jdbc.update("DELETE FROM aliquot");
        jdbc.update("DELETE FROM specimen");
    }

    private String prepareAliquot() {
        specimenService.intake(new IntakeRequest("SP-1", new BigDecimal("100"), "lab-a"));
        return specimenService.aliquot("SP-1",
                        new AliquotRequest("req-al-1", 0, List.of(new BigDecimal("40"))))
                .aliquotIds().get(0);
    }

    private List<Object> runConcurrently(int threads, java.util.function.IntFunction<Object> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待放行超时");
                }
                return task.apply(index);
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return results;
    }

    @Test
    void concurrentReservationsExactlyOneWins() throws Exception {
        String aliquotId = prepareAliquot();
        int threads = 8;
        List<Object> outcomes = runConcurrently(threads, i -> {
            try {
                return assayService.create(new AssayCreateRequest(
                        "req-conc-" + i, aliquotId, 0, new BigDecimal("10"), "PCR", "lab-a"));
            } catch (ApiException e) {
                return e;
            }
        });

        long successes = outcomes.stream().filter(o -> o instanceof AssayResponse).count();
        long conflicts = outcomes.stream()
                .filter(o -> o instanceof ApiException e && e.status() == HttpStatus.CONFLICT)
                .count();
        assertEquals(1, successes, "并发占用只能成功一次");
        assertEquals(threads - 1, conflicts, "其余并发申请必须返回 409");

        Long assayCount = jdbc.queryForObject("SELECT COUNT(*) FROM assay_request", Long.class);
        assertEquals(1, assayCount);
        String status = jdbc.queryForObject("SELECT status FROM aliquot WHERE aliquot_id = ?",
                String.class, aliquotId);
        assertEquals(AliquotStatus.RESERVED.name(), status);
    }

    @Test
    void concurrentSameKeySameParamsReturnsSameAssay() throws Exception {
        String aliquotId = prepareAliquot();
        int threads = 6;
        List<Object> outcomes = runConcurrently(threads, i -> {
            try {
                return assayService.create(new AssayCreateRequest(
                        "req-conc-same", aliquotId, 0, new BigDecimal("10"), "PCR", "lab-a"));
            } catch (ApiException e) {
                return e;
            }
        });

        List<AssayResponse> successes = outcomes.stream()
                .filter(o -> o instanceof AssayResponse)
                .map(o -> (AssayResponse) o)
                .toList();
        assertEquals(threads, successes.size(), "同键同参的并发请求都应拿到首次结果");
        String firstAssayId = successes.get(0).assayId();
        assertTrue(successes.stream().allMatch(r -> r.assayId().equals(firstAssayId)),
                "所有重放必须返回同一个 assayId");

        Long assayCount = jdbc.queryForObject("SELECT COUNT(*) FROM assay_request", Long.class);
        assertEquals(1, assayCount);
        Long idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-conc-same'", Long.class);
        assertEquals(1, idemCount, "同键并发只能落一条幂等记录");
    }
}
