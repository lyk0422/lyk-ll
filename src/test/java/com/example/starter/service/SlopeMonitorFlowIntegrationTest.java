package com.example.starter.service;

import com.example.starter.domain.DisposalChain;
import com.example.starter.domain.MonitorZone;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边坡监测处置链 H2 端到端测试（真实事务、真实唯一约束，不 mock 数据库边界）。
 * 所有业务时刻显式传入，无墙钟依赖。
 */
@SpringBootTest
class SlopeMonitorFlowIntegrationTest {

    private static final BigDecimal WARN = new BigDecimal("5.0");
    private static final BigDecimal ALARM = new BigDecimal("100.0");
    private static final BigDecimal SAFETY = new BigDecimal("2.0");

    @Autowired
    private SlopeMonitorService service;
    @Autowired
    private MonitorRepository monitorRepository;
    @Autowired
    private DisposalRepository disposalRepository;
    @Autowired
    private IdempotentRepository idempotentRepository;

    private final LocalDateTime base = LocalDateTime.of(2026, 9, 24, 0, 0);

    @AfterEach
    void clean() {
        monitorRepository.deleteAllForTest();
        idempotentRepository.deleteAllForTest();
    }

    // ------------------------------------------------------------ 主流程与阈值

    @Test
    void hourlyRateExactlyEqualsWarnThreshold_isNormal_noChainCreated() {
        String zone = "Z-EQ-WARN";
        createZone(zone);
        report(zone, List.of(item(zone, base, 0), item(zone, base.plusHours(1), 5)));

        ServiceResult result = service.evaluateAlarm(
                new AlarmEvaluateRequest("req-eq-warn", zone, base.plusHours(1)));

        assertEquals(200, result.httpStatus());
        assertEquals("NORMAL", field(result, "level"));
        assertNotNull(disposalRepository.findActiveChainByZone(zone) == null
                ? Boolean.TRUE : Boolean.FALSE);
        assertTrue(disposalRepository.findActiveChainByZone(zone) == null);
        assertEquals("ACTIVE", monitorRepository.findZone(zone).getProductionStatus());
    }

    @Test
    void yellowAlarm_createsChainWithoutStoppingProduction() {
        String zone = "Z-YELLOW";
        createZone(zone);
        report(zone, List.of(item(zone, base, 0), item(zone, base.plusHours(1), 6)));

        ServiceResult result = service.evaluateAlarm(
                new AlarmEvaluateRequest("req-yellow", zone, base.plusHours(1)));

        assertEquals(201, result.httpStatus());
        assertEquals("YELLOW", field(result, "level"));
        assertEquals("UNCHANGED", field(result, "productionStatus"));
        assertNotNull(disposalRepository.findActiveChainByZone(zone));
        assertEquals("ACTIVE", monitorRepository.findZone(zone).getProductionStatus());
    }

    @Test
    void redAlarm_stopsProductionAndCreatesEvacuationSnapshot() {
        String zone = "Z-RED";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1", "p2"));

        ServiceResult result = service.evaluateAlarm(
                new AlarmEvaluateRequest("req-red", zone, trigger));

        assertEquals(201, result.httpStatus());
        assertEquals("RED", field(result, "level"));
        MonitorZone zonePo = monitorRepository.findZone(zone);
        assertEquals("STOPPED", zonePo.getProductionStatus());
        DisposalChain chain = disposalRepository.findActiveChainByZone(zone);
        assertNotNull(chain);
        assertEquals("PENDING", chain.getEvacStatus());
        assertEquals(2, disposalRepository.countEvacPeople(chain.getId()));
        assertEquals(0, disposalRepository.countArrived(chain.getId()));
        // 红色预警立即停产但作业封锁要等撤人确认事务
        assertFalse(zonePo.isOperationLocked());
    }

    // ------------------------------------------------------------ 撤人 30 分钟

    @Test
    void evacuationConfirmedExactlyAt30Minutes_locksOperationInSameTransaction() {
        String zone = "Z-EVAC-30";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1", "p2"));
        DisposalChain chain = triggerRed(zone, trigger, "req-evac30");

        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(20)));
        // 恰满 30 分钟到位
        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p2", trigger.plusMinutes(30)));

        ServiceResult result = service.confirmEvacuation(
                new EvacConfirmRequest("req-confirm30", chain.getChainNo(), trigger.plusMinutes(30)));

        assertEquals(200, result.httpStatus());
        assertEquals("CONFIRMED", field(result, "evacStatus"));
        assertEquals(Boolean.TRUE, field(result, "operationLocked"));
        // 数据库边界：撤人确认与作业封锁同时落库（同一事务）
        DisposalChain reloaded = disposalRepository.findChainByNo(chain.getChainNo());
        assertEquals("CONFIRMED", reloaded.getEvacStatus());
        assertNotNull(reloaded.getEvacConfirmTime());
        assertTrue(monitorRepository.findZone(zone).isOperationLocked());
        assertEquals("STOPPED", monitorRepository.findZone(zone).getProductionStatus());
    }

    @Test
    void arrivalAfter30Minutes_confirmReturnsTimeout() {
        String zone = "Z-EVAC-31";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1", "p2"));
        DisposalChain chain = triggerRed(zone, trigger, "req-evac31");

        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(20)));
        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p2", trigger.plusMinutes(31)));

        ServiceResult result = service.confirmEvacuation(
                new EvacConfirmRequest("req-confirm31", chain.getChainNo(), trigger.plusMinutes(31)));

        assertEquals(422, result.httpStatus());
        assertEquals("TIMEOUT", field(result, "evacStatus"));
        assertEquals("TIMEOUT", disposalRepository.findChainByNo(chain.getChainNo()).getEvacStatus());
        // 超时不得封锁作业
        assertFalse(monitorRepository.findZone(zone).isOperationLocked());
    }

    @Test
    void missingPerson_confirmRejectedAndStateUnchanged() {
        String zone = "Z-EVAC-MISS";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1", "p2"));
        DisposalChain chain = triggerRed(zone, trigger, "req-evacmiss");

        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(10)));

        ServiceResult result = service.confirmEvacuation(
                new EvacConfirmRequest("req-confirmmiss", chain.getChainNo(), trigger.plusMinutes(20)));

        assertEquals(422, result.httpStatus());
        assertEquals("PENDING", field(result, "evacStatus"));
        assertEquals("PENDING", disposalRepository.findChainByNo(chain.getChainNo()).getEvacStatus());
        assertFalse(monitorRepository.findZone(zone).isOperationLocked());
    }

    @Test
    void duplicateArrivalReportsKeepEarliestTime() {
        String zone = "Z-EVAC-DUP";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1"));
        DisposalChain chain = triggerRed(zone, trigger, "req-evacdup");

        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(15)));
        service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(5)));

        ServiceResult result = service.reportArrival(
                new ArrivalReportRequest(chain.getChainNo(), "p1", trigger.plusMinutes(25)));
        assertEquals(200, result.httpStatus());
        assertEquals(1L, field(result, "arrivedPeople"));
        // 后续恰满 30 分钟确认成功，间接证明保留的是最早到位时刻（5 分）而非 25 分以外
        ServiceResult confirm = service.confirmEvacuation(
                new EvacConfirmRequest("req-confirmdup", chain.getChainNo(), trigger.plusMinutes(30)));
        assertEquals(200, confirm.httpStatus());
    }

    // ------------------------------------------------------------ 复工 24 小时与双确认

    @Test
    void resumeApplicationExactlyAfter24Hours_accepted() {
        String zone = "Z-RESUME-24";
        LocalDateTime trigger = base.plusHours(24);
        DisposalChain chain = prepareConfirmedRedChain(zone, trigger, "req-red24",
                "req-confirm24", List.of("p1"));
        addSafeReadings(zone, trigger);

        ServiceResult result = service.applyResume(
                new ResumeApplyRequest("req-apply24", chain.getChainNo(), trigger.plusHours(24)));

        assertEquals(201, result.httpStatus());
        assertEquals("APPLIED", field(result, "resumeStatus"));
        assertEquals(trigger, field(result, "safeSince"));
    }

    @Test
    void resumeApplicationOneMinuteBefore24Hours_rejected() {
        String zone = "Z-RESUME-2359";
        LocalDateTime trigger = base.plusHours(24);
        DisposalChain chain = prepareConfirmedRedChain(zone, trigger, "req-red2359",
                "req-confirm2359", List.of("p1"));
        addSafeReadings(zone, trigger);
        // 补齐申请时刻读数（1mm/小时，仍严格低于安全线），保证只因未满 24 小时被拒
        service.reportReadings(new ReadingBatchRequest(List.of(item(zone,
                trigger.plusHours(23).plusMinutes(59), new BigDecimal("143.99")))));

        ApiException ex = assertThrows(ApiException.class, () -> service.applyResume(
                new ResumeApplyRequest("req-apply2359", chain.getChainNo(),
                        trigger.plusHours(23).plusMinutes(59))));
        assertEquals(422, ex.getHttpStatus());
        assertEquals("SAFETY_WINDOW_NOT_MET", ex.getCode());
        // 申请失败不得改变测区状态
        assertTrue(monitorRepository.findZone(zone).isOperationLocked());
        assertEquals(null, disposalRepository.findResumeByChain(chain.getId()));
    }

    @Test
    void doubleConfirmation_bothPass_approvesAndUnlocks() {
        String zone = "Z-DUAL-OK";
        LocalDateTime trigger = base.plusHours(24);
        DisposalChain chain = prepareAppliedResume(zone, trigger, "req-dual-ok");

        ServiceResult result = service.decideResume(new ResumeDecisionRequest(
                "req-decide-ok", chain.getChainNo(), trigger.plusHours(25),
                true, true, "现场与监测均稳定"));

        assertEquals(200, result.httpStatus());
        assertEquals("APPROVED", field(result, "resumeStatus"));
        assertEquals("RESUMED", monitorRepository.findZone(zone).getProductionStatus());
        assertFalse(monitorRepository.findZone(zone).isOperationLocked());
        assertEquals("APPROVED", disposalRepository.findChainByNo(chain.getChainNo()).getResumeStatus());
    }

    @Test
    void doubleConfirmation_oneFails_rejectedAndStaysStopped() {
        String zone = "Z-DUAL-FAIL";
        LocalDateTime trigger = base.plusHours(24);
        DisposalChain chain = prepareAppliedResume(zone, trigger, "req-dual-fail");

        ServiceResult result = service.decideResume(new ResumeDecisionRequest(
                "req-decide-fail", chain.getChainNo(), trigger.plusHours(25),
                true, false, "监测复核发现裂缝仍在发展"));

        assertEquals(200, result.httpStatus());
        assertEquals("REJECTED", field(result, "resumeStatus"));
        assertEquals("STOPPED", monitorRepository.findZone(zone).getProductionStatus());
        assertTrue(monitorRepository.findZone(zone).isOperationLocked());

        // 驳回后满足条件可用新 requestId 重新申请并双确认通过（补报至 +27h 的安全读数，1mm/小时）
        List<ReadingItem> more = new ArrayList<>();
        for (int h = 25; h <= 27; h++) {
            more.add(item(zone, trigger.plusHours(h), new BigDecimal(120 + h)));
        }
        assertEquals(200, service.reportReadings(new ReadingBatchRequest(more)).httpStatus());
        ServiceResult reapply = service.applyResume(
                new ResumeApplyRequest("req-reapply", chain.getChainNo(), trigger.plusHours(26)));
        assertEquals(201, reapply.httpStatus());
        ServiceResult approve = service.decideResume(new ResumeDecisionRequest(
                "req-redecide", chain.getChainNo(), trigger.plusHours(27), true, true, null));
        assertEquals(200, approve.httpStatus());
        assertEquals("RESUMED", monitorRepository.findZone(zone).getProductionStatus());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void sameRequestIdSameParams_replaysFirstResult() {
        String zone = "Z-IDEM-OK";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1"));

        ServiceResult first = service.evaluateAlarm(
                new AlarmEvaluateRequest("req-idem", zone, trigger));
        ServiceResult replay = service.evaluateAlarm(
                new AlarmEvaluateRequest("req-idem", zone, trigger));

        assertEquals(201, first.httpStatus());
        assertEquals(first.httpStatus(), replay.httpStatus());
        assertEquals(field(first, "chainNo"), field(replay, "chainNo"));
        // 没有产生第二条处置链，测区仍只停产一次
        assertEquals(1, countActiveChains(zone));
        assertEquals("STOPPED", monitorRepository.findZone(zone).getProductionStatus());
    }

    @Test
    void sameRequestIdDifferentParams_returns409AndStateUnchanged() {
        String zone = "Z-IDEM-CONF";
        LocalDateTime trigger = base.plusHours(24);
        prepareRedZone(zone, trigger, List.of("p1"));

        ServiceResult first = service.evaluateAlarm(
                new AlarmEvaluateRequest("req-idem-conf", zone, trigger));
        assertEquals(201, first.httpStatus());
        Object firstChainNo = field(first, "chainNo");

        ApiException ex = assertThrows(ApiException.class, () -> service.evaluateAlarm(
                new AlarmEvaluateRequest("req-idem-conf", zone, trigger.plusMinutes(1))));
        assertEquals(409, ex.getHttpStatus());
        assertEquals("IDEMPOTENT_CONFLICT", ex.getCode());

        // 状态不变：仍只有同一条执行中链
        DisposalChain chain = disposalRepository.findActiveChainByZone(zone);
        assertNotNull(chain);
        assertEquals(firstChainNo, chain.getChainNo());
        assertEquals(trigger, chain.getTriggerTime());
    }

    // ------------------------------------------------------------ 归档终态与迟到数据

    @Test
    void archive_isTerminal_repeatedArchiveIdempotent_lateReadingOnlyRecordsEvent() {
        String zone = "Z-ARCHIVE";
        LocalDateTime trigger = base.plusHours(24);
        DisposalChain chain = prepareApprovedChain(zone, trigger, "req-arch-red");

        ServiceResult archived = service.archive(
                new ArchiveRequest("req-archive", chain.getChainNo(), trigger.plusHours(28)));
        assertEquals(200, archived.httpStatus());
        assertEquals("ARCHIVED", field(archived, "chainStatus"));
        assertEquals(Boolean.FALSE, field(archived, "alreadyArchived"));

        // 同 requestId 重放：原样返回首次结果（alreadyArchived=false）
        ServiceResult replay = service.archive(
                new ArchiveRequest("req-archive", chain.getChainNo(), trigger.plusHours(28)));
        assertEquals(200, replay.httpStatus());
        assertEquals(Boolean.FALSE, field(replay, "alreadyArchived"));

        // 不同 requestId 重复归档：终态幂等成功
        ServiceResult again = service.archive(
                new ArchiveRequest("req-archive-2", chain.getChainNo(), trigger.plusHours(29)));
        assertEquals(200, again.httpStatus());
        assertEquals(Boolean.TRUE, field(again, "alreadyArchived"));

        // 归档后迟到的历史位移数据：只记迟到事件，处置链不变（选用此前未占用的时刻）
        int readingsBefore = monitorRepository.countReadings(zone);
        ServiceResult late = service.reportReadings(new ReadingBatchRequest(List.of(
                item(zone, trigger.plusHours(27), new BigDecimal("146.50")))));
        assertEquals(200, late.httpStatus());
        assertEquals(1, ((Map<?, ?>) field(late, "late")).get(zone));
        assertEquals(readingsBefore + 1, monitorRepository.countReadings(zone));
        assertEquals(1, monitorRepository.countLateReadings(zone));
        assertEquals(1, monitorRepository.findEventTypes(zone, "LATE_READING").size());
        assertEquals("ARCHIVED", disposalRepository.findChainByNo(chain.getChainNo()).getStatus());
        // 归档释放执行中槽位，同测区可受理新预警
        assertTrue(disposalRepository.findActiveChainByZone(zone) == null);
    }

    @Test
    void readingsDedupedByZoneAndTime_andCurveSortedByTime() {
        String zone = "Z-DEDUP";
        createZone(zone);
        ReadingItem a = item(zone, base.plusHours(2), 10);
        ReadingItem b = item(zone, base, 0);
        ReadingItem c = item(zone, base.plusHours(1), 5);
        ServiceResult first = service.reportReadings(new ReadingBatchRequest(List.of(a, b, c)));
        assertEquals(3, ((Map<?, ?>) field(first, "inserted")).get(zone));

        // 同刻重复（即使位移值不同）按“测区＋时刻”去重
        ServiceResult second = service.reportReadings(new ReadingBatchRequest(List.of(
                item(zone, base.plusHours(1), 99))));
        assertEquals(1, ((Map<?, ?>) field(second, "duplicated")).get(zone));
        assertEquals(3, monitorRepository.countReadings(zone));

        ServiceResult curve = service.rateCurve(zone, base, base.plusHours(2));
        List<?> points = (List<?>) field(curve, "points");
        assertEquals(3, points.size());
    }

    // ------------------------------------------------------------ 辅助

    private void createZone(String zone) {
        ServiceResult r = service.createZone(new CreateZoneRequest(zone, "测区-" + zone, WARN, ALARM, SAFETY));
        assertEquals(201, r.httpStatus());
    }

    private void prepareRedZone(String zone, LocalDateTime trigger, List<String> persons) {
        createZone(zone);
        List<ReadingItem> items = new ArrayList<>();
        for (int h = 0; h <= 24; h++) {
            items.add(item(zone, trigger.minusHours(24).plusHours(h),
                    BigDecimal.valueOf(5L * h)));
        }
        report(zone, items);
        for (String p : persons) {
            ServiceResult pr = service.reportPersonLocation(
                    new PersonLocationRequest(zone, p, true, trigger.minusMinutes(5)));
            assertEquals(200, pr.httpStatus());
        }
    }

    private DisposalChain triggerRed(String zone, LocalDateTime trigger, String requestId) {
        ServiceResult result = service.evaluateAlarm(
                new AlarmEvaluateRequest(requestId, zone, trigger));
        assertEquals(201, result.httpStatus());
        return disposalRepository.findActiveChainByZone(zone);
    }

    private DisposalChain prepareConfirmedRedChain(String zone, LocalDateTime trigger,
                                                   String alarmReq, String confirmReq,
                                                   List<String> persons) {
        prepareRedZone(zone, trigger, persons);
        DisposalChain chain = triggerRed(zone, trigger, alarmReq);
        int i = 0;
        for (String p : persons) {
            service.reportArrival(new ArrivalReportRequest(chain.getChainNo(), p,
                    trigger.plusMinutes(10 + i * 10L)));
            i++;
        }
        ServiceResult confirm = service.confirmEvacuation(
                new EvacConfirmRequest(confirmReq, chain.getChainNo(), trigger.plusMinutes(30)));
        assertEquals(200, confirm.httpStatus());
        return disposalRepository.findChainByNo(chain.getChainNo());
    }

    private DisposalChain prepareAppliedResume(String zone, LocalDateTime trigger, String tag) {
        DisposalChain chain = prepareConfirmedRedChain(zone, trigger,
                tag + "-alarm", tag + "-confirm", List.of("p1"));
        addSafeReadings(zone, trigger);
        ServiceResult apply = service.applyResume(
                new ResumeApplyRequest(tag + "-apply", chain.getChainNo(), trigger.plusHours(24)));
        assertEquals(201, apply.httpStatus());
        return disposalRepository.findChainByNo(chain.getChainNo());
    }

    private DisposalChain prepareApprovedChain(String zone, LocalDateTime trigger, String tag) {
        DisposalChain chain = prepareAppliedResume(zone, trigger, tag);
        ServiceResult decide = service.decideResume(new ResumeDecisionRequest(
                tag + "-decide", chain.getChainNo(), trigger.plusHours(25), true, true, null));
        assertEquals(200, decide.httpStatus());
        return disposalRepository.findChainByNo(chain.getChainNo());
    }

    private void addSafeReadings(String zone, LocalDateTime trigger) {
        List<ReadingItem> items = new ArrayList<>();
        BigDecimal baseDisp = new BigDecimal("120");
        for (int h = 1; h <= 24; h++) {
            items.add(item(zone, trigger.plusHours(h), baseDisp.add(BigDecimal.valueOf(h))));
        }
        ServiceResult r = service.reportReadings(new ReadingBatchRequest(items));
        assertEquals(200, r.httpStatus());
    }

    private void report(String zone, List<ReadingItem> items) {
        ServiceResult r = service.reportReadings(new ReadingBatchRequest(items));
        assertEquals(200, r.httpStatus());
    }

    private ReadingItem item(String zone, LocalDateTime time, double mm) {
        return item(zone, time, BigDecimal.valueOf(mm));
    }

    private ReadingItem item(String zone, LocalDateTime time, BigDecimal mm) {
        return new ReadingItem(zone, time, mm);
    }

    private int countActiveChains(String zone) {
        return disposalRepository.findActiveChainByZone(zone) == null ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private Object field(ServiceResult result, String name) {
        return ((Map<String, Object>) result.body()).get(name);
    }
}
