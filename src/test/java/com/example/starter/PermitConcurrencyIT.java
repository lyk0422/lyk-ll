package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Responses.TreeRegisterResponse;
import com.example.starter.domain.WorkType;
import com.example.starter.repo.PermitRepository;
import com.example.starter.service.BusinessException;
import com.example.starter.service.PermitService;
import com.example.starter.service.SegmentService;
import com.example.starter.service.ServiceResult;
import com.example.starter.service.TreeService;

/**
 * 并发唯一：同一路段同时刻只允许一张执行中许可，由数据库唯一约束保证。
 */
class PermitConcurrencyIT extends BaseIntegrationTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private TreeService treeService;
    @Autowired
    private PermitService permitService;
    @Autowired
    private PermitRepository permitRepository;

    @Test
    void concurrentPermitApplicationsAllowOnlyOneExecuting() throws Exception {
        segmentService.register(new SegmentRegisterRequest("seg-c1", "SEG-C1", "并发路"));
        ServiceResult treeResult = treeService.register(new TreeRegisterRequest(
                "tree-c1", "SEG-C1", "T-C1", 30.0, 2, 40, 2));
        long planId = ((TreeRegisterResponse) treeResult.body()).planId();
        long segmentId = jdbc.queryForObject(
                "SELECT id FROM road_segment WHERE code = 'SEG-C1'", Long.class);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        try {
                            return permitService.apply(new PermitApplyRequest(
                                    "permit-c1-" + i, "SEG-C1", planId, WorkType.HIGH_ALTITUDE));
                        } catch (BusinessException e) {
                            return e;
                        }
                    })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<Object> future : futures) {
                Object outcome = future.get(15, TimeUnit.SECONDS);
                if (outcome instanceof ServiceResult) {
                    success++;
                } else {
                    BusinessException e = (BusinessException) outcome;
                    assertEquals(409, e.status());
                    assertEquals("PERMIT_EXECUTING_EXISTS", e.code());
                    conflict++;
                }
            }
            assertEquals(1, success);
            assertEquals(1, conflict);
            assertEquals(1, permitRepository.countExecutingBySegment(segmentId));
            assertEquals(1, permitRepository.count());
        } finally {
            pool.shutdownNow();
        }
    }
}
