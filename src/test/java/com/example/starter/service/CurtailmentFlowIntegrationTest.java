package com.example.starter.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.domain.AlarmRow;
import com.example.starter.domain.CommandRow;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.AlarmView;
import com.example.starter.web.dto.CommandAccepted;
import com.example.starter.web.dto.CommandDetail;
import com.example.starter.web.dto.CommandRequest;
import com.example.starter.web.dto.CurvePoint;
import com.example.starter.web.dto.ReportAccepted;
import com.example.starter.web.dto.ReportRequest;
import com.example.starter.web.dto.SealAccepted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限电指令全流程 H2 集成测试：覆盖恰等目标不降机、尾差归属、10 分钟恰超时告警、乱序归位、
 * 新旧指令原子切换、同键重放、机组＋时刻去重、并发唯一受理与封存终态。
 */
@SpringBootTest
class CurtailmentFlowIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Autowired
    private CurtailmentService curtailmentService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    private long farmId;
    private long t1;
    private long t2;
    private long t3;
    private long t4;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alarm_event");
        jdbc.update("DELETE FROM power_report");
        jdbc.update("DELETE FROM command_allocation");
        jdbc.update("DELETE FROM curtailment_command");
        jdbc.update("DELETE FROM turbine");
        jdbc.update("DELETE FROM farm");
        farmId = insertFarm("FARM-T", "测试风场");
        // 额定 2000/2000/1500/1500，可用 1800/1500/1200/900，合计 5400，15% 下限 300/300/225/225
        t1 = insertTurbine("WTG-01", 2000, 1800);
        t2 = insertTurbine("WTG-02", 2000, 1500);
        t3 = insertTurbine("WTG-03", 1500, 1200);
        t4 = insertTurbine("WTG-04", 1500, 900);
    }

    private long insertFarm(String code, String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO farm (code, name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertTurbine(String code, int capacityKw, int availableKw) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO turbine (farm_id, code, capacity_kw, available_kw)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, farmId);
            ps.setString(2, code);
            ps.setInt(3, capacityKw);
            ps.setInt(4, availableKw);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private CommandRequest command(String requestId, int targetKw) {
        return new CommandRequest(requestId, farmId, targetKw, T0);
    }

    @Test
    void exactTargetNoReduction() {
        // 全场可用出力恰等于目标：不降机
        CommandAccepted accepted = curtailmentService.receive(command("cmd-eq", 5400));
        assertFalse(accepted.curtailed());
        assertEquals(0, accepted.reductionKw());
        assertEquals(5400, accepted.totalAvailableKw());
        assertEquals(List.of(1800, 1500, 1200, 900),
                accepted.allocations().stream()
                        .map(CommandAccepted.AllocationView::targetKw).toList());
    }

    @Test
    void proportionalSplitPersistedWithTailToMaxHeadroom() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-split", 4000));
        assertTrue(accepted.curtailed());
        assertEquals(1400, accepted.reductionKw());
        // 可降空间 1500/1200/975/675，floor 后 1318/1114/887/683，尾差 2 归入机组 1 → 1316
        assertEquals(List.of(1316, 1114, 887, 683),
                accepted.allocations().stream()
                        .map(CommandAccepted.AllocationView::targetKw).toList());
        // 分摊明细落库且合计恰等于指令目标
        Integer sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(target_kw),0) FROM command_allocation WHERE command_id = ?",
                Integer.class, accepted.commandId());
        assertEquals(4000, sum);
        Integer minTarget = jdbc.queryForObject(
                "SELECT MIN(target_kw - min_kw) FROM command_allocation WHERE command_id = ?",
                Integer.class, accepted.commandId());
        assertTrue(minTarget >= 0, "任何机组不得越过 15% 下限");
    }

    @Test
    void exactlyAtFifteenPercentFloorIsLegal() {
        // 单机恰降至 15%：目标 = 全场最低技术出力之和 1050
        CommandAccepted accepted = curtailmentService.receive(command("cmd-floor", 1050));
        assertEquals(List.of(300, 300, 225, 225),
                accepted.allocations().stream()
                        .map(CommandAccepted.AllocationView::targetKw).toList());
    }

    @Test
    void belowFloorRejectedWith422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> curtailmentService.receive(command("cmd-low", 1049)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void idempotentReplaySameKeySameParams() {
        CommandAccepted first = curtailmentService.receive(command("cmd-idem", 4000));
        CommandAccepted replay = curtailmentService.receive(command("cmd-idem", 4000));
        assertTrue(replay.replayed());
        assertEquals(first.commandId(), replay.commandId());
        assertEquals(first.allocations(), replay.allocations());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_command WHERE request_id = 'cmd-idem'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void sameKeyDifferentParamsConflict409() {
        curtailmentService.receive(command("cmd-cf", 4000));
        ApiException ex = assertThrows(ApiException.class,
                () -> curtailmentService.receive(command("cmd-cf", 3900)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void atomicSwitchOldSupersededNewExecuting() {
        CommandAccepted first = curtailmentService.receive(command("cmd-old", 4000));
        CommandAccepted second = curtailmentService.receive(command("cmd-new", 3600));
        assertEquals(first.commandId(), second.supersededCommandId());
        assertEquals(CommandRow.STATUS_SUPERSEDED, jdbc.queryForObject(
                "SELECT status FROM curtailment_command WHERE id = ?",
                String.class, first.commandId()));
        assertEquals(CommandRow.STATUS_EXECUTING, jdbc.queryForObject(
                "SELECT status FROM curtailment_command WHERE id = ?",
                String.class, second.commandId()));
        Integer executing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_command WHERE farm_id = ? AND status = 'EXECUTING'",
                Integer.class, farmId);
        assertEquals(1, executing, "同一风场同时刻只允许一个执行中指令");
    }

    @Test
    void concurrentReceiveOnlyOneAccepted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable task = () -> {
            String requestId = "cmd-race-" + Thread.currentThread().getName();
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                curtailmentService.receive(command(requestId, 4000));
                success.incrementAndGet();
            } catch (ApiException e) {
                assertEquals(HttpStatus.CONFLICT, e.status());
                conflict.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(1, success.get(), "并发接收仅唯一受理");
        assertEquals(1, conflict.get());
        Integer executing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_command WHERE farm_id = ? AND status = 'EXECUTING'",
                Integer.class, farmId);
        assertEquals(1, executing);
    }

    @Test
    void timeoutExactlyTenMinutesRaisesAlarmAndReallocates() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-to", 4000));
        int targetT2 = accepted.allocations().stream()
                .filter(a -> a.turbineId() == t2).findFirst().orElseThrow().targetKw();
        assertEquals(1114, targetT2);
        // 恰满 10 分钟判定：下发后 10 分钟整仍未达目标 → 告警 + 重分摊
        ReportAccepted report = reportService.submit(new ReportRequest(
                "rpt-timeout", accepted.commandId(), t2, T0.plusMinutes(10), 1400));
        assertTrue(report.alarmRaised());
        assertTrue(report.reallocated());
        List<AlarmView> alarms = reportService.alarms(accepted.commandId());
        assertEquals(1, alarms.size());
        assertEquals(AlarmRow.TYPE_CURTAIL_TIMEOUT, alarms.get(0).type());
        // 重分摊生成第 2 轮，合计仍恰等于指令目标
        CommandDetail detail = curtailmentService.detail(accepted.commandId());
        assertEquals(2, detail.rounds().size());
        int round2Sum = detail.rounds().get(1).allocations().stream()
                .mapToInt(CommandAccepted.AllocationView::targetKw).sum();
        assertEquals(4000, round2Sum);
    }

    @Test
    void reportJustBeforeDeadlineNoAlarm() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-9m", 4000));
        // 9 分 59 秒：未达 10 分钟阈值，不告警
        ReportAccepted report = reportService.submit(new ReportRequest(
                "rpt-9m", accepted.commandId(), t2, T0.plusMinutes(9).plusSeconds(59), 1400));
        assertFalse(report.alarmRaised());
        assertFalse(report.reallocated());
        assertTrue(reportService.alarms(accepted.commandId()).isEmpty());
    }

    @Test
    void reachedTargetAtDeadlineNoAlarm() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-ok", 4000));
        // 恰满 10 分钟但已达标（实际不高于目标）：不告警
        ReportAccepted report = reportService.submit(new ReportRequest(
                "rpt-ok", accepted.commandId(), t2, T0.plusMinutes(10), 1114));
        assertFalse(report.alarmRaised());
        assertTrue(reportService.alarms(accepted.commandId()).isEmpty());
    }

    @Test
    void outOfOrderReportsRepositionedByTime() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-curve", 4000));
        reportService.submit(new ReportRequest("rpt-c3", accepted.commandId(), t1,
                T0.plusMinutes(3), 1700));
        reportService.submit(new ReportRequest("rpt-c1", accepted.commandId(), t1,
                T0.plusMinutes(1), 1750));
        reportService.submit(new ReportRequest("rpt-c2", accepted.commandId(), t2,
                T0.plusMinutes(2), 1450));
        List<CurvePoint> curve = reportService.curve(accepted.commandId());
        assertEquals(List.of(T0.plusMinutes(1), T0.plusMinutes(2), T0.plusMinutes(3)),
                curve.stream().map(CurvePoint::reportTime).toList());
        assertEquals(List.of(1750, 1450, 1700),
                curve.stream().map(CurvePoint::actualKw).toList());
    }

    @Test
    void duplicateTurbineTimePointDedupedAndConflict() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-dup", 4000));
        ReportAccepted first = reportService.submit(new ReportRequest(
                "rpt-d1", accepted.commandId(), t1, T0.plusMinutes(1), 1750));
        // 同机组同时刻同值（不同 requestId）：去重返回
        ReportAccepted dup = reportService.submit(new ReportRequest(
                "rpt-d2", accepted.commandId(), t1, T0.plusMinutes(1), 1750));
        assertTrue(dup.deduplicated());
        assertEquals(first.reportId(), dup.reportId());
        // 同机组同时刻异值：409
        ApiException ex = assertThrows(ApiException.class, () -> reportService.submit(
                new ReportRequest("rpt-d3", accepted.commandId(), t1, T0.plusMinutes(1), 1700)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM power_report WHERE command_id = ?",
                Integer.class, accepted.commandId());
        assertEquals(1, count);
    }

    @Test
    void reportIdempotentReplay() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-ridem", 4000));
        ReportRequest request = new ReportRequest("rpt-idem", accepted.commandId(), t1,
                T0.plusMinutes(1), 1750);
        ReportAccepted first = reportService.submit(request);
        ReportAccepted replay = reportService.submit(request);
        assertTrue(replay.deduplicated());
        assertEquals(first.reportId(), replay.reportId());
        // 同键异参：409
        ApiException ex = assertThrows(ApiException.class, () -> reportService.submit(
                new ReportRequest("rpt-idem", accepted.commandId(), t1, T0.plusMinutes(1), 1700)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void sealIsTerminalAndLateReportOnlyRecorded() {
        CommandAccepted accepted = curtailmentService.receive(command("cmd-seal", 4000));
        SealAccepted sealed = curtailmentService.seal(accepted.commandId());
        assertEquals(CommandRow.STATUS_SEALED, sealed.status());
        assertFalse(sealed.alreadySealed());
        assertNotNull(sealed.sealedAt());
        // 重复封存：幂等成功，封存时刻不变（H2 TIMESTAMP 为微秒精度，按毫秒比较）
        SealAccepted again = curtailmentService.seal(accepted.commandId());
        assertTrue(again.alreadySealed());
        assertEquals(sealed.sealedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                again.sealedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        // 封存后迟到回报：只记迟到事件，不产生新一轮分摊
        ReportAccepted late = reportService.submit(new ReportRequest(
                "rpt-late", accepted.commandId(), t1, T0.plusMinutes(30), 1600));
        assertTrue(late.late());
        List<AlarmView> alarms = reportService.alarms(accepted.commandId());
        assertEquals(1, alarms.size());
        assertEquals(AlarmRow.TYPE_LATE_REPORT, alarms.get(0).type());
        CommandDetail detail = curtailmentService.detail(accepted.commandId());
        assertEquals(1, detail.rounds().size(), "封存后不得重分摊");
        // 迟到回报已落库并标记 late
        Boolean lateFlag = jdbc.queryForObject(
                "SELECT late FROM power_report WHERE request_id = 'rpt-late'", Boolean.class);
        assertTrue(lateFlag);
    }
}
