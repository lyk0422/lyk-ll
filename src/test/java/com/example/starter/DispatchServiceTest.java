package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.QueueEntry;

/**
 * 派单：队列优先级复算（影响户数降序、同数按接报先后、重要等级优先）、班组唯一执行单、并发派单、幂等。
 */
class DispatchServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService dispatch;

    private long report(String requestId, String feeder, String point, int users,
            String importantUser, LocalDateTime at) {
        return reports.report(new FaultReportRequest(requestId, feeder, point, users, importantUser, at))
                .workOrderId();
    }

    @Test
    void 派单队列按影响户数降序且同数按接报先后() {
        long a = report("q-1", "F02", "点A", 100, null, T0);
        long b = report("q-2", "F02", "点B", 300, null, T0.plusMinutes(5));
        long c = report("q-3", "F02", "点C", 300, null, T0.plusMinutes(1));
        List<QueueEntry> queue = dispatch.queue("F02");
        assertEquals(List.of(c, b, a), queue.stream().map(QueueEntry::workOrderId).toList());
    }

    @Test
    void 重要用户分级优先于影响户数() {
        long normal = report("q-4", "F01", "点D", 500, null, T0);
        long special = report("q-5", "F01", "点E", 50, "市一医院", T0.plusMinutes(1));
        List<QueueEntry> queue = dispatch.queue("F01");
        assertEquals(List.of(special, normal), queue.stream().map(QueueEntry::workOrderId).toList());
    }

    @Test
    void 班组同时刻只允许一张执行中工单冲突时挂单() {
        long team = teamId("抢修一班");
        long first = report("q-6", "F01", "点F", 100, null, T0);
        long second = report("q-7", "F01", "点G", 200, null, T0.plusMinutes(1));
        DispatchResponse r1 = dispatch.dispatch(new DispatchRequest("d-1", first, team));
        DispatchResponse r2 = dispatch.dispatch(new DispatchRequest("d-2", second, team));
        assertEquals("DISPATCHED", r1.result());
        assertEquals("QUEUED", r2.result());
        assertEquals("EXECUTING", orderStatus(first));
        assertEquals("QUEUED", orderStatus(second));
        assertEquals(1, count("work_order", "executing_team_id = ?", team));
    }

    @Test
    void 并发派单同一班组仅一单执行() throws Exception {
        long team = teamId("抢修二班");
        long order1 = report("q-8", "F01", "点H", 100, null, T0);
        long order2 = report("q-9", "F01", "点I", 200, null, T0.plusMinutes(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<DispatchResponse> f1 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return dispatch.dispatch(new DispatchRequest("dc-1", order1, team));
            });
            Future<DispatchResponse> f2 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return dispatch.dispatch(new DispatchRequest("dc-2", order2, team));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            DispatchResponse r1 = f1.get(10, TimeUnit.SECONDS);
            DispatchResponse r2 = f2.get(10, TimeUnit.SECONDS);
            List<String> results = List.of(r1.result(), r2.result());
            assertTrue(results.contains("DISPATCHED"), "应有一单派单成功: " + results);
            assertTrue(results.contains("QUEUED"), "应有一单挂单等待: " + results);
            assertEquals(1, count("work_order", "executing_team_id = ? AND status = 'EXECUTING'", team));
            assertEquals(1, count("work_order", "team_id = ? AND status = 'QUEUED'", team));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 派单幂等重放与同键异参409() {
        long team = teamId("抢修一班");
        long order = report("q-10", "F01", "点J", 100, null, T0);
        DispatchResponse first = dispatch.dispatch(new DispatchRequest("d-3", order, team));
        DispatchResponse replay = dispatch.dispatch(new DispatchRequest("d-3", order, team));
        assertTrue(replay.replayed());
        assertEquals(first.result(), replay.result());
        assertEquals(1, count("dispatch_record", "request_id = 'd-3'"));
        long other = report("q-11", "F01", "点K", 100, null, T0.plusMinutes(1));
        assertThrows(ConflictException.class,
                () -> dispatch.dispatch(new DispatchRequest("d-3", other, team)));
        assertEquals(1, count("dispatch_record", "request_id = 'd-3'"));
    }
}
