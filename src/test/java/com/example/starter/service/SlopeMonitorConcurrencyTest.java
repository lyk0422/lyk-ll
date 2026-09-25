package com.example.starter.service;

import com.example.starter.domain.DisposalChain;
import com.example.starter.exception.ApiException;
import com.example.starter.repository.DisposalRepository;
import com.example.starter.repository.IdempotentRepository;
import com.example.starter.repository.MonitorRepository;
import com.example.starter.web.ServiceResult;
import com.example.starter.web.dto.AlarmEvaluateRequest;
import com.example.starter.web.dto.ArchiveRequest;
import com.example.starter.web.dto.ArrivalReportRequest;
import com.example.starter.web.dto.CreateZoneRequest;
import com.example.starter.web.dto.EvacConfirmRequest;
import com.example.starter.web.dto.PersonLocationRequest;
import com.example.starter.web.dto.ReadingBatchRequest;
import com.example.starter.web.dto.ReadingItem;
import com.example.starter.web.dto.ResumeApplyRequest;
import com.example.starter.web.dto.ResumeDecisionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实多线程并发测试（H2 行/表锁 + 唯一索引，不使用 Map/mock 模拟并发）。
 * 每个用例用闭锁协调同时发起，Future 带 30 秒超时防止挂死，并断言最终数据库状态。
 */
@SpringBootTest
class SlopeMonitorConcurrencyTest {

    private static final BigDecimal WARN = new BigDecimal("5.0");
    private static final BigDecimal ALARM = new BigDecimal("100.0");
    private static final BigDecimal SAFETY = new BigDecimal("2.0");
    private static final int THREADS = 8;

    @Autowired
    private SlopeMonitorService service;
    @Autowired
    private MonitorRepository monitorRepository;
    @Autowired
    private DisposalRepository disposalRepository;
    @Autowired
    private IdempotentRepository idempotentRepository;

    private final LocalDateTime base = LocalDateTime.of(2026, 9, 23, 0, 0);

    @AfterEach
    void clean() {
        monitorRepository.deleteAllForTest();
        idempotentRepository.deleteAllForTest();
    }

    /**
     * 并发红色预警（不同 requestId、同测区同刻）：恰好一条被受理，其余 409，
     * 最终只停产一次、只有一条执行中链。
     */
    @Test
    void concurrentRedAlarms_onlyOneAccepted() throws Exception {
        String zone = "C-RED";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1"));

        List<Object> outcomes = runConcurrently(THREADS, i ->
                service.evaluateAlarm(new AlarmEvaluateRequest(
                        "c-red-" + i, zone, trigger)));

        long success = outcomes.stream().filter(o -> o instanceof ServiceResult sr
                && sr.httpStatus() == 201).count();
        long conflicts = outcomes.stream().filter(this::isConflict409).count();
        assertEquals(1, success, "并发预警必须唯一受理，实际成功数=" + success + "，结果=" + outcomes);
        assertEquals(THREADS - 1, conflicts, "其余并发请求必须全部 409");
        assertEquals("STOPPED", monitorRepository.findZone(zone).getProductionStatus());
        assertEquals(1, disposalRepository.findActiveChainByZone(zone) == null ? 0 : 1);
    }

    /**
     * 同 requestId 同参并发：首条执行，其余原样重放首次结果（同 chainNo），不产生第二条链。
     */
    @Test
    void concurrentSameRequestId_replaysSingleFirstResult() throws Exception {
        String zone = "C-IDEM";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1"));

        List<Object> outcomes = runConcurrently(THREADS, i ->
                service.evaluateAlarm(new AlarmEvaluateRequest(
                        "same-request-id", zone, trigger)));

        List<ServiceResult> results = outcomes.stream()
                .filter(o -> o instanceof ServiceResult)
                .map(o -> (ServiceResult) o).collect(Collectors.toList());
        assertEquals(THREADS, results.size(), "同键同参并发应全部得到首次结果而非异常: " + outcomes);
        long created = results.stream().filter(r -> r.httpStatus() == 201).count();
        assertEquals(THREADS, created);
        long distinctChain = results.stream()
                .map(r -> ((java.util.Map<?, ?>) r.body()).get("chainNo"))
                .distinct().count();
        assertEquals(1, distinctChain, "重放必须返回同一条处置链号");
        assertEquals(1, disposalRepository.findActiveChainByZone(zone) == null ? 0 : 1);
    }

    /**
     * 并发复工双确认结论（不同 requestId，均双通过）：停产/复工唯一终态，
     * 恰好一个批准成功并解锁，其余 409，最终 RESUMED 且无重复结论行。
     */
    @Test
    void concurrentResumeDecisions_onlyOneApproves() throws Exception {
        String zone = "C-RESUME";
        LocalDateTime trigger = base.plusHours(24);
        prepareAppliedResume(zone, trigger);

        List<Object> outcomes = runConcurrently(THREADS, i ->
                service.decideResume(new ResumeDecisionRequest(
                        "c-decide-" + i, disposalRepository.findActiveChainByZone(zone).getChainNo(),
                        trigger.plusHours(25), true, true, null)));

        long success = outcomes.stream().filter(o -> o instanceof ServiceResult sr
                && sr.httpStatus() == 200
                && "APPROVED".equals(((java.util.Map<?, ?>) sr.body()).get("resumeStatus"))).count();
        long conflicts = outcomes.stream().filter(this::isConflict409).count();
        assertEquals(1, success, "并发复工结论必须唯一批准，实际=" + success + "，结果=" + outcomes);
        assertEquals(THREADS - 1, conflicts);
        assertEquals("RESUMED", monitorRepository.findZone(zone).getProductionStatus());
        assertFalse(monitorRepository.findZone(zone).isOperationLocked());
        DisposalChain chain = disposalRepository.findActiveChainByZone(zone);
        assertEquals("APPROVED", chain.getResumeStatus());
    }

    /**
     * 并发归档（不同 requestId）：终态幂等，全部成功但实际归档只发生一次（active_key 只释放一次）。
     */
    @Test
    void concurrentArchive_allSucceedButArchivedOnce() throws Exception {
        String zone = "C-ARCH";
        LocalDateTime trigger = base.plusHours(24);
        prepareApprovedChain(zone, trigger);
        String chainNo = disposalRepository.findActiveChainByZone(zone).getChainNo();

        List<Object> outcomes = runConcurrently(THREADS, i ->
                service.archive(new ArchiveRequest("c-arch-" + i, chainNo,
                        trigger.plusHours(28).plusMinutes(i))));

        long ok = outcomes.stream().filter(o -> o instanceof ServiceResult sr
                && sr.httpStatus() == 200).count();
        assertEquals(THREADS, ok, "归档终态并发应全部幂等成功: " + outcomes);
        assertEquals("ARCHIVED", disposalRepository.findChainByNo(chainNo).getStatus());
        assertTrue(disposalRepository.findActiveChainByZone(zone) == null);
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 多线程同时执行，带 30 秒超时；收集每个线程的返回值或抛出异常。
     */
    private List<Object> runConcurrently(int n, ConcurrentAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return action.run(index);
                } catch (Throwable t) {
                    // 业务异常作为结果收集，便于断言 409 数量与最终状态
                    return t;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return outcomes;
    }

    private boolean isConflict409(Object o) {
        return o instanceof ApiException ex && ex.getHttpStatus() == 409;
    }

    private void prepareRedZone(String zone, LocalDateTime trigger, List<String> persons) {
        service.createZone(new CreateZoneRequest(zone, "并发测区-" + zone, WARN, ALARM, SAFETY));
        List<ReadingItem> items = new ArrayList<>();
        for (int h = 0; h <= 24; h++) {
            items.add(new ReadingItem(zone, trigger.minusHours(24).plusHours(h),
                    BigDecimal.valueOf(5L * h)));
        }
        service.reportReadings(new ReadingBatchRequest(items));
        for (String p : persons) {
            service.reportPersonLocation(new PersonLocationRequest(zone, p, true, trigger.minusMinutes(5)));
        }
    }

    private void prepareAppliedResume(String zone, LocalDateTime trigger) {
        prepareRedZone(zone, trigger, List.of("p1"));
        service.evaluateAlarm(new AlarmEvaluateRequest("c-alarm", zone, trigger));
        DisposalChain chain = disposalRepository.findActiveChainByZone(zone);
        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(10)));
        service.confirmEvacuation(new EvacConfirmRequest(
                "c-confirm", chain.getChainNo(), trigger.plusMinutes(30)));
        List<ReadingItem> safe = new ArrayList<>();
        for (int h = 1; h <= 24; h++) {
            safe.add(new ReadingItem(zone, trigger.plusHours(h), BigDecimal.valueOf(120L + h)));
        }
        service.reportReadings(new ReadingBatchRequest(safe));
        service.applyResume(new ResumeApplyRequest(
                "c-apply", chain.getChainNo(), trigger.plusHours(24)));
    }

    private void prepareApprovedChain(String zone, LocalDateTime trigger) {
        prepareAppliedResume(zone, trigger);
        DisposalChain chain = disposalRepository.findActiveChainByZone(zone);
        service.decideResume(new ResumeDecisionRequest(
                "c-once-decide", chain.getChainNo(), trigger.plusHours(25), true, true, null));
    }

    @FunctionalInterface
    private interface ConcurrentAction {
        Object run(int index);
    }
}
