package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参 409。
 */
class IdempotencyIT extends BaseIntegrationTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private TreeService treeService;
    @Autowired
    private PermitService permitService;
    @Autowired
    private PermitRepository permitRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private long planId;

    @BeforeEach
    void setUpPlan() {
        segmentService.register(new SegmentRegisterRequest("seg-i1", "SEG-I1", "幂等路"));
        ServiceResult treeResult = treeService.register(new TreeRegisterRequest(
                "tree-i1", "SEG-I1", "T-I1", 30.0, 2, 40, 2));
        planId = ((TreeRegisterResponse) treeResult.body()).planId();
    }

    private String permitNoOf(ServiceResult result) {
        Object body = result.body();
        if (body instanceof JsonNode node) {
            return node.get("permitNo").asText();
        }
        return objectMapper.valueToTree(body).get("permitNo").asText();
    }

    private long idemRecordCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id LIKE 'idem-%'",
                Long.class);
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        PermitApplyRequest request = new PermitApplyRequest(
                "idem-1", "SEG-I1", planId, WorkType.HIGH_ALTITUDE);
        ServiceResult first = permitService.apply(request);
        ServiceResult second = permitService.apply(request);
        assertEquals(first.status(), second.status());
        assertEquals(permitNoOf(first), permitNoOf(second));
        assertEquals(1, permitRepository.count());
        assertEquals(1, idemRecordCount());
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        permitService.apply(new PermitApplyRequest("idem-2", "SEG-I1", planId, WorkType.HIGH_ALTITUDE));
        BusinessException e = assertThrows(BusinessException.class, () ->
                permitService.apply(new PermitApplyRequest("idem-2", "SEG-I1", planId, WorkType.GROUND)));
        assertEquals(409, e.status());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
    }

    @Test
    void concurrentSameKeySameParamsExecutesOnce() throws Exception {
        PermitApplyRequest request = new PermitApplyRequest(
                "idem-3", "SEG-I1", planId, WorkType.HIGH_ALTITUDE);
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ServiceResult>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return permitService.apply(request);
                    })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String firstPermitNo = permitNoOf(futures.get(0).get(15, TimeUnit.SECONDS));
            String secondPermitNo = permitNoOf(futures.get(1).get(15, TimeUnit.SECONDS));
            assertEquals(firstPermitNo, secondPermitNo);
            assertEquals(1, permitRepository.count());
            assertEquals(1, idemRecordCount());
        } finally {
            pool.shutdownNow();
        }
    }
}
