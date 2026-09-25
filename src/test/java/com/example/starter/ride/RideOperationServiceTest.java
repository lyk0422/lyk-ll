package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.ride.domain.Ride;
import com.example.starter.ride.dto.ArchiveRequest;
import com.example.starter.ride.dto.DailyCheckRequest;
import com.example.starter.ride.dto.OpenRequest;
import com.example.starter.ride.dto.ReadingRequest;
import com.example.starter.ride.dto.RecheckRequest;
import com.example.starter.ride.dto.SafetyFaultRequest;
import com.example.starter.ride.dto.TrialRunRequest;

/**
 * 游乐设施日检与载客联锁主流程、失败分支与幂等边界测试（真实 H2 MySQL 兼容库）。
 */
class RideOperationServiceTest extends AbstractRideTest {

    @Autowired
    private com.example.starter.ride.service.RideOperationService service;

    @Autowired
    private BusinessClock clock;

    @BeforeEach
    void setUp() {
        resetAndSeed();
    }

    private List<DailyCheckRequest.ItemResult> allItemsQualified() {
        return checklistRepository.findItemsByRide("R001").keySet().stream()
                .map(code -> new DailyCheckRequest.ItemResult(code, true, "正常"))
                .collect(Collectors.toList());
    }

    private ApiResult dailyCheckPass(String requestId) {
        return service.dailyCheck(new DailyCheckRequest(requestId, "R001", allItemsQualified()));
    }

    private ApiResult trialOk(String requestId) {
        return service.trialRun(new TrialRunRequest(requestId, "R001", true, true, true, true));
    }

    @Test
    @DisplayName("日检逐项合格 + 恰 3 次空载试运行后可开放，第 4 次试运行非法")
    void exactlyThreeTrialRunsRequired() {
        assertEquals(200, dailyCheckPass("dc-1").httpStatus());
        assertEquals(RideStatus.TRIAL.name(), reload("R001").getStatus());

        ApiResult t1 = trialOk("tr-1");
        ApiResult t2 = trialOk("tr-2");
        ApiResult t3 = trialOk("tr-3");
        assertEquals(200, t1.httpStatus());
        assertEquals(1, ((Number) ((Map<?, ?>) t1.data()).get("qualifiedCount")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) t2.data()).get("qualifiedCount")).intValue());
        assertEquals(3, ((Number) ((Map<?, ?>) t3.data()).get("qualifiedCount")).intValue());
        assertEquals(RideStatus.READY.name(), reload("R001").getStatus());

        ApiResult opened = service.open(new OpenRequest("op-1", "R001"));
        assertEquals(200, opened.httpStatus());
        assertEquals(RideStatus.OPEN.name(), reload("R001").getStatus());

        // 恰 3 次合法：已开放后再发起试运行被拒。
        ApiResult fourth = service.trialRun(new TrialRunRequest("tr-4", "R001", true, true, true, true));
        assertEquals(400, fourth.httpStatus());
    }

    @Test
    @DisplayName("日检存在不合格项时禁止进入试运行")
    void failedDailyCheckBlocksTrial() {
        List<DailyCheckRequest.ItemResult> items = new ArrayList<>(allItemsQualified());
        items.set(0, new DailyCheckRequest.ItemResult(items.get(0).itemCode(), false, "压杠裂纹"));
        ApiResult result = service.dailyCheck(new DailyCheckRequest("dc-fail", "R001", items));
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertEquals(false, data.get("qualified"));
        assertEquals(RideStatus.PENDING_DAILY_CHECK.name(), reload("R001").getStatus());

        ApiResult trial = service.trialRun(new TrialRunRequest("tr-x", "R001", true, true, true, true));
        assertEquals(400, trial.httpStatus());
    }

    @Test
    @DisplayName("试运行中联锁装置失效：整机立即停运，且不占用试运行名额")
    void safetyDeviceFailureDuringTrialStopsRideImmediately() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        ApiResult fault = service.trialRun(
                new TrialRunRequest("tr-fault", "R001", true, true, false, true));
        assertEquals(200, fault.httpStatus());
        assertEquals(true, ((Map<?, ?>) fault.data()).get("stopped"));
        Ride ride = reload("R001");
        assertEquals(RideStatus.FAULT_STOPPED.name(), ride.getStatus());
        // 故障上报不计数，仍可在复检合格后补足 3 次。
        assertEquals(1, ride.getTrialPassedCount());
    }

    @Test
    @DisplayName("运行中压杠失效立即故障停运，不得既停运又显示开放")
    void safetyFaultDuringOperationStops() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));

        ApiResult fault = service.safetyFault(
                new SafetyFaultRequest("sf-1", "R001", SafetyDeviceType.PRESSURE_BAR.name()));
        assertEquals(200, fault.httpStatus());
        Ride ride = reload("R001");
        assertEquals(RideStatus.FAULT_STOPPED.name(), ride.getStatus());
        assertNull(ride.getArchivedAt());
    }

    @Test
    @DisplayName("恰等阈值合格；严格超过阈值触发缓停")
    void thresholdEqualityPassesAndExceedingTriggersSlowStop() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));

        ApiResult equalThreshold = service.reading(new ReadingRequest("rd-eq", "R001", 800.0d, 20.0d));
        assertEquals(200, equalThreshold.httpStatus());
        assertEquals(false, ((Map<?, ?>) equalThreshold.data()).get("triggeredSlowStop"));
        assertEquals(RideStatus.OPEN.name(), reload("R001").getStatus());

        ApiResult below = service.reading(new ReadingRequest("rd-below", "R001", 799.9d, 19.9d));
        assertEquals(false, ((Map<?, ?>) below.data()).get("triggeredSlowStop"));
        assertEquals(RideStatus.OPEN.name(), reload("R001").getStatus());

        ApiResult over = service.reading(new ReadingRequest("rd-over", "R001", 800.1d, 20.0d));
        assertEquals(true, ((Map<?, ?>) over.data()).get("triggeredSlowStop"));
        assertEquals(RideStatus.SLOW_STOPPED.name(), reload("R001").getStatus());
    }

    @Test
    @DisplayName("当日故障停机后复检合格方可复开，同日复开恰 2 次合法，第 3 次当日禁开")
    void atMostTwoReopensPerDay() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));

        // 第一次故障停运 -> 复检合格 -> 第 1 次复开
        service.safetyFault(new SafetyFaultRequest("sf-1", "R001", SafetyDeviceType.INTERLOCK.name()));
        ApiResult rc1 = service.recheck(new RecheckRequest("rc-1", "R001", true, true, true));
        assertEquals(RideStatus.RECHECK_PASSED.name(), ((Map<?, ?>) rc1.data()).get("status"));
        ApiResult ro1 = service.open(new OpenRequest("op-2", "R001"));
        assertEquals(200, ro1.httpStatus());
        assertEquals(1, ((Number) ((Map<?, ?>) ro1.data()).get("reopenCount")).intValue());
        assertEquals(RideStatus.OPEN.name(), reload("R001").getStatus());

        // 第二次缓停 -> 复检合格 -> 第 2 次复开（恰 2 次合法）
        service.reading(new ReadingRequest("rd-1", "R001", 900.0d, 20.0d));
        service.recheck(new RecheckRequest("rc-2", "R001", true, true, true));
        ApiResult ro2 = service.open(new OpenRequest("op-3", "R001"));
        assertEquals(200, ro2.httpStatus());
        assertEquals(2, ((Number) ((Map<?, ?>) ro2.data()).get("reopenCount")).intValue());

        // 再次故障复检后，第 3 次复开当日禁开
        service.safetyFault(new SafetyFaultRequest("sf-2", "R001", SafetyDeviceType.SEAT_BELT.name()));
        service.recheck(new RecheckRequest("rc-3", "R001", true, true, true));
        ApiResult ro3 = service.open(new OpenRequest("op-4", "R001"));
        assertEquals(400, ro3.httpStatus());
        assertEquals(RideStatus.RECHECK_PASSED.name(), reload("R001").getStatus());
    }

    @Test
    @DisplayName("复检不合格保持停运，不得复开")
    void failedRecheckKeepsStopped() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));
        service.safetyFault(new SafetyFaultRequest("sf-1", "R001", SafetyDeviceType.INTERLOCK.name()));

        ApiResult rc = service.recheck(new RecheckRequest("rc-bad", "R001", true, false, true));
        assertEquals(200, rc.httpStatus());
        assertEquals(false, ((Map<?, ?>) rc.data()).get("qualified"));
        assertEquals(RideStatus.FAULT_STOPPED.name(), reload("R001").getStatus());
    }

    @Test
    @DisplayName("未日检设备无法开放")
    void openForbiddenBeforeDailyCheck() {
        ApiResult result = service.open(new OpenRequest("op-early", "R001"));
        assertEquals(400, result.httpStatus());
        assertEquals(RideStatus.PENDING_DAILY_CHECK.name(), reload("R001").getStatus());
    }

    @Test
    @DisplayName("试运行未满 3 次禁止开放载客")
    void openForbiddenBeforeThreeTrials() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        ApiResult result = service.open(new OpenRequest("op-early", "R001"));
        assertEquals(400, result.httpStatus());
        assertEquals(RideStatus.TRIAL.name(), reload("R001").getStatus());
    }

    @Test
    @DisplayName("并发开放同一设备只有一条成功，最终唯一状态为 OPEN 且仅一条开放事件")
    void concurrentOpenOnlyOneSucceeds() throws Exception {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String requestId = "op-concurrent-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return service.open(new OpenRequest(requestId, "R001"));
            }));
        }
        start.countDown();
        int success = 0;
        int rejected = 0;
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            if (result.httpStatus() == 200) {
                success++;
            } else {
                rejected++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, success, "并发开放仅允许一条成功");
        assertEquals(threads - 1, rejected);
        assertEquals(RideStatus.OPEN.name(), reload("R001").getStatus());
        assertEquals(1L, rideRepository.countOpenEvents("R001", clock.today()));
    }

    @Test
    @DisplayName("停运与开放竞态：故障停运唯一成功，不得既停运又显示开放")
    void stopAndOpenRaceHasUniqueTerminalState() throws Exception {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                // 半数请求并发上报故障停运，半数并发重复开放。
                if (idx % 2 == 0) {
                    return service.safetyFault(new SafetyFaultRequest(
                            "sf-race-" + idx, "R001", SafetyDeviceType.PRESSURE_BAR.name()));
                }
                return service.open(new OpenRequest("op-race-" + idx, "R001"));
            }));
        }
        start.countDown();
        int faultSuccess = 0;
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            if (result.httpStatus() == 200) {
                faultSuccess++;
            }
        }
        pool.shutdownNow();

        // 恰一次故障停运成功；重复开放全部失败。
        assertEquals(1, faultSuccess);
        Ride ride = reload("R001");
        assertEquals(RideStatus.FAULT_STOPPED.name(), ride.getStatus());
        long faultEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ride_status_event WHERE ride_id = 'R001' AND event_type = 'FAULT_STOP'",
                Long.class);
        assertEquals(1L, faultEvents);
        // 首次开放之外没有新增开放事件。
        assertEquals(1L, rideRepository.countOpenEvents("R001", clock.today()));
    }

    @Test
    @DisplayName("同键同参重放返回首次结果；同键异参 409 且不改变状态")
    void idempotentReplayAndConflict() {
        ApiResult first = dailyCheckPass("idem-dc");
        assertEquals(200, first.httpStatus());

        // 同键同参重放：返回首次结果，不新增日检记录。
        ApiResult replay = dailyCheckPass("idem-dc");
        assertEquals(200, replay.httpStatus());
        assertEquals(first.message(), replay.message());
        assertEquals(1L, dailyCheckRepositoryCount());

        // 同键异参：服务层抛出 409 冲突异常（HTTP 层由全局处理器映射为 409），状态不变。
        List<DailyCheckRequest.ItemResult> badItems = new ArrayList<>(allItemsQualified());
        badItems.set(0, new DailyCheckRequest.ItemResult(badItems.get(0).itemCode(), false, "失效"));
        BusinessRuleException conflictEx = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessRuleException.class,
                () -> service.dailyCheck(new DailyCheckRequest("idem-dc", "R001", badItems)));
        assertEquals(409, conflictEx.getHttpStatus());
        Ride ride = reload("R001");
        assertEquals(RideStatus.TRIAL.name(), ride.getStatus());
        assertTrue(ride.isDailyCheckPassed());
        assertEquals(1L, dailyCheckRepositoryCount());
    }

    @Test
    @DisplayName("首次业务失败也固化，同键重放仍返回首次失败码")
    void idempotentStoresBusinessFailure() {
        // 未日检直接开放，首次为 400。
        ApiResult first = service.open(new OpenRequest("idem-fail", "R001"));
        assertEquals(400, first.httpStatus());

        // 补齐日检与试运行后，用同一 requestId 重放，仍返回首次 400。
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        ApiResult replay = service.open(new OpenRequest("idem-fail", "R001"));
        assertEquals(400, replay.httpStatus());
        assertEquals(RideStatus.READY.name(), reload("R001").getStatus());
    }

    @Test
    @DisplayName("闭园归档为终态：重复归档幂等成功，迟到读数只记迟到事件")
    void archiveTerminalAndLateReading() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));

        ApiResult archived = service.archive(new ArchiveRequest("ar-1", "R001"));
        assertEquals(200, archived.httpStatus());
        assertEquals(RideStatus.ARCHIVED.name(), reload("R001").getStatus());
        assertNotNull(reload("R001").getArchivedAt());

        // 同键重复归档：幂等成功，原样返回首次响应（首次响应 alreadyArchived=false）。
        ApiResult replayArchive = service.archive(new ArchiveRequest("ar-1", "R001"));
        assertEquals(200, replayArchive.httpStatus());
        assertEquals(false, ((Map<?, ?>) replayArchive.data()).get("alreadyArchived"));

        // 异键重复归档同样幂等成功，响应标记已归档，且不新增 ARCHIVE 事件。
        ApiResult archiveAgain = service.archive(new ArchiveRequest("ar-2", "R001"));
        assertEquals(200, archiveAgain.httpStatus());
        assertEquals(true, ((Map<?, ?>) archiveAgain.data()).get("alreadyArchived"));
        long archiveEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ride_status_event WHERE ride_id = 'R001' AND event_type = 'ARCHIVE'",
                Long.class);
        assertEquals(1L, archiveEvents);

        // 归档后迟到读数：只登记迟到事件，状态保持 ARCHIVED。
        ApiResult late = service.reading(new ReadingRequest("rd-late", "R001", 9999.0d, 999.0d));
        assertEquals(200, late.httpStatus());
        assertEquals(true, ((Map<?, ?>) late.data()).get("lateAfterArchive"));
        assertEquals(false, ((Map<?, ?>) late.data()).get("triggeredSlowStop"));
        assertEquals(RideStatus.ARCHIVED.name(), reload("R001").getStatus());
        long lateEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ride_status_event WHERE ride_id = 'R001' AND event_type = 'LATE_READING'",
                Long.class);
        assertEquals(1L, lateEvents);

        // 归档后开放/试运行均被拒。
        assertEquals(400, service.open(new OpenRequest("op-after", "R001")).httpStatus());
        assertEquals(400, service.trialRun(
                new TrialRunRequest("tr-after", "R001", true, true, true, true)).httpStatus());
    }

    @Test
    @DisplayName("启停链与试运行记录可复算，数字与事件链一致")
    void chainCanBeRecomputed() {
        dailyCheckPass("dc-1");
        trialOk("tr-1");
        trialOk("tr-2");
        // 一次空载异常（不计合格次数）
        ApiResult anomaly = service.trialRun(new TrialRunRequest("tr-anom", "R001", true, true, true, false));
        assertEquals(false, ((Map<?, ?>) anomaly.data()).get("qualified"));
        trialOk("tr-3");
        service.open(new OpenRequest("op-1", "R001"));
        service.safetyFault(new SafetyFaultRequest("sf-1", "R001", SafetyDeviceType.INTERLOCK.name()));
        service.recheck(new RecheckRequest("rc-1", "R001", true, true, true));
        service.open(new OpenRequest("op-2", "R001"));
        service.archive(new ArchiveRequest("ar-1", "R001"));

        ApiResult replay = service.replayChain("R001");
        assertEquals(200, replay.httpStatus());
        Map<?, ?> data = (Map<?, ?>) replay.data();
        assertEquals(3, ((Number) data.get("trialQualifiedCount")).intValue());
        assertEquals(4L, ((Number) data.get("trialTotalAttempts")).longValue());
        assertEquals(2L, ((Number) data.get("openEventCount")).longValue());
        assertEquals(1L, ((Number) data.get("faultStopCount")).longValue());
        assertEquals(1L, ((Number) data.get("reopenCount")).longValue());
        assertEquals(RideStatus.ARCHIVED.name(), data.get("currentStatus"));
        assertFalse(((List<?>) data.get("events")).isEmpty());
    }

    private long dailyCheckRepositoryCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_check_record", Long.class);
    }
}
