package com.example.starter.pressure;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.TaskNotAcceptedException;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压力调节测试：恰等限值合格、超差联动、调压加注原子性、同刻并发唯一受理、幂等重放/异参409。
 */
class PressureServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 25).atTime(13, 0);

    @Autowired
    private PressureService pressureService;
    @Autowired
    private LinkageTaskRepository linkageTaskRepository;

    private PressureDetectRequest request(String requestId, LocalDateTime time, String pressure) {
        return new PressureDetectRequest("ST01", requestId, time,
                new BigDecimal(pressure), new BigDecimal("100"));
    }

    @Test
    void pressureExactlyAtLimitsIsNormalAndNoLinkage() {
        PressureDetectResponse low = pressureService.detect(request("PR-LO", T, "0.3000"));
        assertEquals("NORMAL", low.grade(), "恰下限0.3000判合格");
        assertFalse(low.accepted());
        assertNull(low.taskId());

        PressureDetectResponse high = pressureService.detect(request("PR-HI", T.plusMinutes(1), "0.5000"));
        assertEquals("NORMAL", high.grade(), "恰上限0.5000判合格");
        assertFalse(high.accepted());
        assertEquals(0, linkageTaskRepository.countByRequest("PR-HI"));
    }

    @Test
    void highPressureTriggersAtomicPressureAndInjectionAdjustment() {
        PressureDetectResponse resp = pressureService.detect(request("PR-1", T, "0.6000"));
        assertEquals("HIGH", resp.grade());
        assertTrue(resp.accepted());
        assertNotNull(resp.taskId());
        assertEquals(0, new BigDecimal("0.6000").compareTo(resp.pressureBeforeMpa()));
        assertEquals(0, new BigDecimal("0.4000").compareTo(resp.pressureAfterMpa()), "调压回归目标0.4");
        assertEquals(0, new BigDecimal("20.000").compareTo(resp.injectionRateAfterMgM3()));
        // 加注量 = 100 m3 × 20 mg/m3 = 2000 mg
        assertEquals(0, new BigDecimal("2000.000").compareTo(resp.injectionDoseMg()));

        long issues = count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ?", resp.taskId());
        assertEquals(2, issues, "同一任务必须同事务下发 PRESSURE 与 INJECTION 两条指令");
        assertEquals(1, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ? AND issue_type = 'PRESSURE'",
                resp.taskId()));
        assertEquals(1, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ? AND issue_type = 'INJECTION'",
                resp.taskId()));
        assertEquals(1, linkageTaskRepository.countCompleted(1L), "任务须为COMPLETED");
    }

    @Test
    void lowPressureTriggersLinkage() {
        PressureDetectResponse resp = pressureService.detect(request("PR-2", T, "0.2999"));
        assertEquals("LOW", resp.grade());
        assertTrue(resp.accepted());
        assertEquals(0, new BigDecimal("0.4000").compareTo(resp.pressureAfterMpa()));
    }

    @Test
    void concurrentSameInstantOnlyOneAccepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                start.await();
                pressureService.detect(request("PR-C" + idx, T, "0.6000"));
                return null;
            });
        }
        start.countDown();

        int accepted = 0;
        int rejected = 0;
        for (Future<?> f : futures) {
            try {
                f.get(10, TimeUnit.SECONDS);
                accepted++;
            } catch (Exception e) {
                rejected++;
            }
        }
        pool.shutdown();

        assertEquals(1, accepted, "同一门站同时刻并发下达只允许一个受理");
        assertEquals(threads - 1, rejected, "其余请求必须未受理");
        assertEquals(1, count("SELECT COUNT(*) FROM linkage_task WHERE station_id = 1 AND detected_at = ?",
                java.sql.Timestamp.valueOf(T)),
                "同时刻只允许一条联动任务落库");
        assertEquals(2, count("SELECT COUNT(*) FROM adjustment_issue ai "
                        + "JOIN linkage_task lt ON ai.task_id = lt.id WHERE lt.station_id = 1 AND lt.detected_at = ?",
                java.sql.Timestamp.valueOf(T)),
                "未受理事务不得留下任何调压/加注指令（原子性）");
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_reading WHERE station_id = 1 AND detected_at = ?",
                java.sql.Timestamp.valueOf(T)),
                "未受理事务的读数也必须回滚");
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentSameRequestSameParamsProcessesOnlyOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        PressureDetectRequest req = request("PR-RPC", T.plusMinutes(30), "0.6000");
        Future<PressureDetectResponse>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                return pressureService.detect(req);
            });
        }
        start.countDown();

        int firstCount = 0;
        int replayCount = 0;
        Long firstTaskId = null;
        for (Future<PressureDetectResponse> f : futures) {
            PressureDetectResponse resp = f.get(15, TimeUnit.SECONDS);
            if (resp.idempotentReplay()) {
                replayCount++;
            } else {
                firstCount++;
                firstTaskId = resp.taskId();
            }
        }
        pool.shutdown();

        for (Future<PressureDetectResponse> f : futures) {
            assertEquals(firstTaskId, f.get(5, TimeUnit.SECONDS).taskId(), "所有并发结果须指向同一任务");
        }
        assertEquals(1, firstCount, "同键同参并发只允许一个请求执行业务");
        assertEquals(threads - 1, replayCount, "其余并发请求全部拿到首次结果");
        assertEquals(1, count("SELECT COUNT(*) FROM linkage_task WHERE request_id = 'PR-RPC'"));
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_reading WHERE request_id = 'PR-RPC'"));
    }

    @Test
    void sameRequestSameParamsReplaysFirstResult() {
        PressureDetectRequest req = request("PR-RP", T, "0.6000");
        PressureDetectResponse first = pressureService.detect(req);
        assertTrue(first.accepted());
        assertFalse(first.idempotentReplay());

        PressureDetectResponse replay = pressureService.detect(req);
        assertTrue(replay.idempotentReplay());
        assertEquals(first.taskId(), replay.taskId(), "重放返回首次任务ID");
        assertEquals(1, count("SELECT COUNT(*) FROM linkage_task WHERE request_id = 'PR-RP'"),
                "重放不得产生第二条任务");
    }

    @Test
    void sameRequestDifferentParamsReturns409() {
        pressureService.detect(request("PR-CF", T, "0.6000"));
        PressureDetectRequest tampered = request("PR-CF", T, "0.2000");
        assertThrows(IdempotentConflictException.class, () -> pressureService.detect(tampered));
        assertEquals(1, count("SELECT COUNT(*) FROM linkage_task WHERE request_id = 'PR-CF'"),
                "同键异参不得新增任务");
    }
}
