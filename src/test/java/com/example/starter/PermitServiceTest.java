package com.example.starter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.domain.PermitStatus;
import com.example.starter.dto.FilingRequest;
import com.example.starter.dto.PermitResponse;
import com.example.starter.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 申请与审批：时段边界、药量上限、幂等重放、未审批备案拒绝、并发唯一获批。
 */
class PermitServiceTest extends BaseIntegrationTest {

    @Test
    void windowBoundary_0559Rejected_0600Accepted() {
        assertEquals(PermitStatus.REJECTED,
                apply("w-0559", ZONE_EAST, "2026-09-26T05:59:00Z", "10").status());
        assertEquals(PermitStatus.PENDING,
                apply("w-0600", ZONE_EAST, "2026-09-26T06:00:00Z", "10").status());
    }

    @Test
    void windowBoundary_2159Accepted_2200Rejected() {
        assertEquals(PermitStatus.PENDING,
                apply("w-2159", ZONE_EAST, "2026-09-26T21:59:00Z", "10").status());
        assertEquals(PermitStatus.REJECTED,
                apply("w-2200", ZONE_EAST, "2026-09-26T22:00:00Z", "10").status());
        assertEquals(PermitStatus.REJECTED,
                apply("w-2300", ZONE_EAST, "2026-09-26T23:00:00Z", "10").status());
    }

    @Test
    void chargeLimit_equalAccepted_overRejected() {
        assertEquals(PermitStatus.PENDING,
                apply("c-eq", ZONE_EAST, "2026-09-26T08:00:00Z", "120").status());
        PermitResponse over = apply("c-over", ZONE_EAST, "2026-09-26T08:00:00Z", "120.001");
        assertEquals(PermitStatus.REJECTED, over.status());
        assertTrue(over.rejectReason().contains("上限"));
    }

    @Test
    void idempotentApply_sameKeySameParamsReturnsFirst() {
        PermitResponse first = apply("i-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        PermitResponse replay = apply("i-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        assertEquals(first.id(), replay.id());
        assertEquals(first.status(), replay.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_permit WHERE request_id = 'i-1'", Integer.class));
    }

    @Test
    void idempotentApply_sameKeyDifferentParamsReturns409() {
        apply("i-2", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        ApiException ex = assertThrows(ApiException.class,
                () -> apply("i-2", ZONE_EAST, "2026-09-26T09:00:00Z", "10"));
        assertEquals(409, ex.getStatus().value());
        // 流程状态不被改变
        assertEquals("PENDING", statusOf(apply("i-2", ZONE_EAST,
                "2026-09-26T08:00:00Z", "10").id()));
    }

    @Test
    void filingWithoutApprovalRejected() {
        PermitResponse pending = apply("f-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        ApiException ex = assertThrows(ApiException.class,
                () -> filingService.file(new FilingRequest("f-1-F", pending.id())));
        assertEquals(422, ex.getStatus().value());
        assertEquals("PENDING", statusOf(pending.id()));
    }

    @Test
    void approveThenFileTransitionsToFiled() {
        long permitId = applyAndApprove("f-2", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        assertEquals("APPROVED", statusOf(permitId));
        filingService.file(new FilingRequest("f-2-F", permitId));
        assertEquals("FILED", statusOf(permitId));
    }

    @Test
    void sameZoneSameTimeOnlyOneApproved() {
        long first = applyAndApprove("s-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        PermitResponse second = apply("s-2", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        ApiException ex = assertThrows(ApiException.class,
                () -> permitService.approve(second.id()));
        assertEquals(409, ex.getStatus().value());
        assertEquals("APPROVED", statusOf(first));
        assertEquals("PENDING", statusOf(second.id()));
    }

    @Test
    void concurrentApproveOnlyOneWins() throws Exception {
        PermitResponse a = apply("cc-1", ZONE_EAST, "2026-09-26T10:00:00Z", "10");
        PermitResponse b = apply("cc-2", ZONE_EAST, "2026-09-26T10:00:00Z", "10");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> taskA = () -> approveTask(a.id(), ready, go);
        Callable<String> taskB = () -> approveTask(b.id(), ready, go);
        try {
            Future<String> fa = pool.submit(taskA);
            Future<String> fb = pool.submit(taskB);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<String> results = List.of(
                    fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("OK"::equals).count(),
                    "并发审批只能一个获批: " + results);
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        } finally {
            pool.shutdownNow();
        }
        // 最终数据：一个 APPROVED，一个仍为 PENDING，槽位只有一行
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_permit WHERE status = 'APPROVED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approved_slot", Integer.class));
    }

    private String approveTask(long permitId, CountDownLatch ready, CountDownLatch go)
            throws InterruptedException {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        try {
            permitService.approve(permitId);
            return "OK";
        } catch (ApiException e) {
            return e.getStatus().value() == 409 ? "CONFLICT" : "ERROR:" + e.getMessage();
        }
    }
}
