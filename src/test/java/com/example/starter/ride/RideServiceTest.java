package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.ArchiveRequest;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 游乐设施日检与载客联锁业务主流程测试（真实 H2，MySQL 兼容模式）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RideServiceTest {

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private long rideId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        rideId = ((Number) service.createRide(
                new CreateRideRequest("云霄飞车", 10, 80)).get("rideId")).longValue();
    }

    private long createRide() {
        return ((Number) service.createRide(
                new CreateRideRequest("旋转木马", 12, 40)).get("rideId")).longValue();
    }

    private DailyCheckRequest passCheck(String requestId) {
        return new DailyCheckRequest(requestId, List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true),
                new CheckItem("制动系统", true)));
    }

    private void dailyCheckOk(long id, String requestId) {
        ApiResult result = service.dailyCheck(id, passCheck(requestId));
        assertEquals(200, result.status());
    }

    private void trialOk(long id, String requestId) {
        ApiResult result = service.trialRun(id, new TrialRunRequest(requestId, true));
        assertEquals(200, result.status());
    }

    private void makeReady(long id, String keyPrefix) {
        dailyCheckOk(id, keyPrefix + "-check");
        trialOk(id, keyPrefix + "-t1");
        trialOk(id, keyPrefix + "-t2");
        trialOk(id, keyPrefix + "-t3");
    }

    private void makeOpen(long id, String keyPrefix) {
        makeReady(id, keyPrefix);
        ApiResult result = service.open(id, new OpenRequest(keyPrefix + "-open"));
        assertEquals(200, result.status());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ApiResult result) {
        try {
            return objectMapper.readValue(result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(long id) {
        return (String) service.getRide(id).get("status");
    }

    @Test
    @DisplayName("试运行恰3次合格方可开放：2次时禁开放，第3次后允许，第4次拒绝")
    void trialRunExactlyThreeRequired() {
        dailyCheckOk(rideId, "k-check");

        trialOk(rideId, "k-t1");
        trialOk(rideId, "k-t2");
        assertEquals("CHECKED", statusOf(rideId));
        // 仅2次试运行，禁开放
        ApiException tooEarly = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open-early")));
        assertEquals(409, tooEarly.status().value());

        ApiResult third = service.trialRun(rideId, new TrialRunRequest("k-t3", true));
        assertEquals("READY", bodyOf(third).get("status"));
        assertEquals(3, bodyOf(third).get("trialCount"));

        ApiResult opened = service.open(rideId, new OpenRequest("k-open"));
        assertEquals("OPEN", bodyOf(opened).get("status"));

        // 第4次试运行：状态机拒绝
        ApiException fourth = assertThrows(ApiException.class,
                () -> service.trialRun(rideId, new TrialRunRequest("k-t4", true)));
        assertEquals(409, fourth.status().value());
        // 试运行记录恰3条，可复算
        assertEquals(3, service.listTrialRuns(rideId).size());
    }

    @Test
    @DisplayName("试运行异常立即故障停运")
    void abnormalTrialStopsRide() {
        dailyCheckOk(rideId, "k-check");
        trialOk(rideId, "k-t1");
        ApiResult result = service.trialRun(rideId, new TrialRunRequest("k-t2", false));
        assertEquals("STOPPED", bodyOf(result).get("status"));
        assertEquals("STOPPED", statusOf(rideId));
    }

    @Test
    @DisplayName("安全装置失效即停：日检中安全带不合格整机停运")
    void safetyDeviceFailureInDailyCheckStopsRide() {
        ApiResult result = service.dailyCheck(rideId, new DailyCheckRequest("k-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", false),
                new CheckItem("联锁装置", true))));
        assertEquals("STOPPED", bodyOf(result).get("status"));
        assertFalse((Boolean) bodyOf(result).get("allPassed"));
        // 停运后不可开放
        ApiException e = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(409, e.status().value());
    }

    @Test
    @DisplayName("安全装置失效即停：开放中联锁装置失效上报立即停运")
    void safetyFaultWhileOpenStopsImmediately() {
        makeOpen(rideId, "k");
        ApiResult result = service.safetyFault(rideId,
                new SafetyFaultRequest("k-fault", "联锁装置"));
        assertEquals("STOPPED", bodyOf(result).get("status"));
        assertEquals("STOPPED", statusOf(rideId));
    }

    @Test
    @DisplayName("恰等阈值合格：载荷/速度等于阈值不缓停，超过即缓停")
    void thresholdEqualityIsPassing() {
        makeOpen(rideId, "k");
        // 恰等阈值合格
        Map<String, Object> equal = service.reading(rideId, new ReadingRequest(10, 80));
        assertFalse((Boolean) equal.get("exceeded"));
        assertEquals("OPEN", equal.get("status"));
        // 低于阈值合格
        Map<String, Object> below = service.reading(rideId, new ReadingRequest(9, 79));
        assertFalse((Boolean) below.get("exceeded"));
        assertEquals("OPEN", statusOf(rideId));
        // 超阈值触发缓停
        Map<String, Object> over = service.reading(rideId, new ReadingRequest(11, 80));
        assertTrue((Boolean) over.get("exceeded"));
        assertEquals("SUSPENDED", statusOf(rideId));
        // 缓停后拒绝继续接收运行读数（非归档态）
        ApiException e = assertThrows(ApiException.class,
                () -> service.reading(rideId, new ReadingRequest(1, 1)));
        assertEquals(409, e.status().value());
    }

    @Test
    @DisplayName("同日复开恰2次合法，第3次当日禁开")
    void reopenAtMostTwicePerDay() {
        makeOpen(rideId, "k");
        // 第1次故障→复检→复开
        service.safetyFault(rideId, new SafetyFaultRequest("k-f1", "压杠"));
        service.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("压杠", true))));
        ApiResult reopen1 = service.reopen(rideId, new ReopenRequest("k-re1"));
        assertEquals("OPEN", bodyOf(reopen1).get("status"));
        assertEquals(1, bodyOf(reopen1).get("reopenCount"));
        // 第2次故障→复检→复开（恰2次合法）
        service.safetyFault(rideId, new SafetyFaultRequest("k-f2", "安全带"));
        service.recheck(rideId, new RecheckRequest("k-r2",
                List.of(new CheckItem("安全带", true))));
        ApiResult reopen2 = service.reopen(rideId, new ReopenRequest("k-re2"));
        assertEquals("OPEN", bodyOf(reopen2).get("status"));
        assertEquals(2, bodyOf(reopen2).get("reopenCount"));
        // 第3次故障→复检合格→复开被拒绝：当日禁开
        service.safetyFault(rideId, new SafetyFaultRequest("k-f3", "联锁装置"));
        service.recheck(rideId, new RecheckRequest("k-r3",
                List.of(new CheckItem("联锁装置", true))));
        ApiException e = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("k-re3")));
        assertEquals(422, e.status().value());
        assertEquals("RECHECKED", statusOf(rideId));
    }

    @Test
    @DisplayName("未日检设备无法开放")
    void notCheckedRideCannotOpen() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(409, e.status().value());
        assertTrue(e.getMessage().contains("未日检"));
        assertEquals("PENDING_CHECK", statusOf(rideId));
    }

    @Test
    @DisplayName("未复检合格不得复开")
    void reopenRequiresRecheck() {
        makeOpen(rideId, "k");
        service.safetyFault(rideId, new SafetyFaultRequest("k-f1", "压杠"));
        ApiException e = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("k-re1")));
        assertEquals(409, e.status().value());
        // 复检不合格保持停运
        ApiResult failed = service.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("压杠", false))));
        assertFalse((Boolean) bodyOf(failed).get("allPassed"));
        assertEquals("STOPPED", statusOf(rideId));
    }

    @Test
    @DisplayName("同键重放：同参返回首次结果且状态不变，异参返回409且状态不变")
    void idempotentReplayAndConflict() {
        DailyCheckRequest request = passCheck("k-same");
        ApiResult first = service.dailyCheck(rideId, request);
        ApiResult replay = service.dailyCheck(rideId, request);
        assertEquals(200, first.status());
        assertEquals(first.body(), replay.body());
        // 只落一条日检记录
        assertEquals(1, service.listDailyChecks(rideId).size());

        // 同键异参 → 409，状态不变
        DailyCheckRequest different = new DailyCheckRequest("k-same", List.of(
                new CheckItem("压杠", false)));
        ApiException e = assertThrows(ApiException.class,
                () -> service.dailyCheck(rideId, different));
        assertEquals(409, e.status().value());
        assertEquals("CHECKED", statusOf(rideId));
        assertEquals(1, service.listDailyChecks(rideId).size());

        // 试运行同键重放不重复计数
        trialOk(rideId, "k-t1");
        ApiResult t1Replay = service.trialRun(rideId, new TrialRunRequest("k-t1", true));
        assertEquals(200, t1Replay.status());
        assertEquals(1, service.listTrialRuns(rideId).size());
        assertEquals(1, service.getRide(rideId).get("trialCount"));
    }

    @Test
    @DisplayName("归档为终态：重复归档幂等成功，迟到数据只记事件，归档后禁止开放")
    void archiveIsTerminal() {
        makeOpen(rideId, "k");
        ApiResult archived = service.archive(rideId, new ArchiveRequest("k-arch"));
        assertEquals("ARCHIVED", bodyOf(archived).get("status"));

        // 同键重复归档 → 首次结果
        ApiResult replay = service.archive(rideId, new ArchiveRequest("k-arch"));
        assertEquals(archived.body(), replay.body());
        // 新键重复归档 → 幂等成功
        ApiResult again = service.archive(rideId, new ArchiveRequest("k-arch-2"));
        assertEquals(200, again.status());
        assertEquals("ARCHIVED", bodyOf(again).get("status"));

        // 迟到运行数据只记迟到事件，状态不变
        Map<String, Object> late = service.reading(rideId, new ReadingRequest(99, 99));
        assertTrue((Boolean) late.get("late"));
        assertEquals("ARCHIVED", statusOf(rideId));
        List<Map<String, Object>> events = service.listEvents(rideId);
        assertTrue(events.stream().anyMatch(ev -> "LATE_READING".equals(ev.get("event_type"))));
        List<Map<String, Object>> readings = service.listReadings(rideId);
        assertEquals(1, readings.size());
        assertTrue((Boolean) readings.get(0).get("late"));

        // 归档后禁止开放、日检、停运
        assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open-2")));
        assertThrows(ApiException.class,
                () -> service.dailyCheck(rideId, passCheck("k-check-2")));
        assertThrows(ApiException.class,
                () -> service.safetyFault(rideId, new SafetyFaultRequest("k-f", "压杠")));
        assertEquals("ARCHIVED", statusOf(rideId));
    }

    @Test
    @DisplayName("缓停后复检合格可复开，计入同日复开次数")
    void suspendedRideReopensAfterRecheck() {
        makeOpen(rideId, "k");
        service.reading(rideId, new ReadingRequest(11, 50));
        assertEquals("SUSPENDED", statusOf(rideId));
        service.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("制动系统", true))));
        ApiResult reopened = service.reopen(rideId, new ReopenRequest("k-re1"));
        assertEquals("OPEN", bodyOf(reopened).get("status"));
        assertEquals(1, bodyOf(reopened).get("reopenCount"));
    }

    @Test
    @DisplayName("启停链事件完整可复算")
    void eventChainIsRecomputable() {
        makeOpen(rideId, "k");
        service.safetyFault(rideId, new SafetyFaultRequest("k-f1", "压杠"));
        service.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("压杠", true))));
        service.reopen(rideId, new ReopenRequest("k-re1"));
        service.archive(rideId, new ArchiveRequest("k-arch"));

        List<String> types = service.listEvents(rideId).stream()
                .map(ev -> (String) ev.get("event_type"))
                .toList();
        assertEquals(List.of("CREATE", "DAILY_CHECK", "TRIAL_RUN", "TRIAL_RUN", "TRIAL_RUN",
                "TRIAL_RUN", "OPEN", "FAULT_STOP", "RECHECK", "REOPEN", "ARCHIVE"), types);
    }
}
