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
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.LockRemovalRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceCreateRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceFinishRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceStartRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检修挂牌流程业务测试（真实 H2，MySQL 兼容模式）：
 * 建档→启动(整机停运+唯一未撤锁)→双人签认撤锁→全部撤除后结束；
 * 开放/复开入口检查未结束检修及未撤锁，结束不替代原资格复开流程。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class MaintenanceServiceTest {

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private RideService rideService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private long rideId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM maintenance_lock_removal");
        jdbc.update("DELETE FROM maintenance_lock");
        jdbc.update("DELETE FROM maintenance_person");
        jdbc.update("DELETE FROM maintenance_order");
        jdbc.update("DELETE FROM supervision_inspection_item");
        jdbc.update("DELETE FROM supervision_inspection");
        jdbc.update("DELETE FROM overhaul_record");
        jdbc.update("DELETE FROM current_certificate");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        rideId = ((Number) rideService.createRide(
                new CreateRideRequest("云霄飞车", 10, 80)).get("rideId")).longValue();
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
        return (String) rideService.getRide(id).get("status");
    }

    private void dailyCheckOk(long id, String requestId) {
        ApiResult result = rideService.dailyCheck(id, new DailyCheckRequest(requestId, List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        assertEquals(200, result.status());
    }

    private void makeReady(long id, String keyPrefix) {
        dailyCheckOk(id, keyPrefix + "-check");
        for (int i = 1; i <= 3; i++) {
            ApiResult r = rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t" + i, true));
            assertEquals(200, r.status());
        }
    }

    private void makeOpen(long id, String keyPrefix) {
        makeReady(id, keyPrefix);
        ApiResult result = rideService.open(id, new OpenRequest(keyPrefix + "-open"));
        assertEquals(200, result.status());
    }

    private long createOrder(String requestId, String... personnel) {
        ApiResult result = maintenanceService.create(rideId,
                new MaintenanceCreateRequest(requestId, List.of(personnel)));
        assertEquals(200, result.status());
        return ((Number) bodyOf(result).get("maintenanceId")).longValue();
    }

    private void startOk(long maintenanceId, String requestId, long expectedVersion) {
        ApiResult result = maintenanceService.start(rideId, maintenanceId,
                new MaintenanceStartRequest(requestId, expectedVersion));
        assertEquals(200, result.status());
    }

    private void removeLockOk(long maintenanceId, String requestId, String person,
            String supervisor, long expectedVersion) {
        ApiResult result = maintenanceService.removeLock(rideId, maintenanceId,
                new LockRemovalRequest(requestId, person, supervisor, expectedVersion));
        assertEquals(200, result.status());
    }

    @Test
    @DisplayName("建档：1至20名不同人员，重复人员422，初始PREPARED版本1")
    void createValidatesDistinctPersonnel() {
        ApiResult result = maintenanceService.create(rideId,
                new MaintenanceCreateRequest("m-c1", List.of("张三", "李四")));
        Map<String, Object> body = bodyOf(result);
        assertEquals("PREPARED", body.get("status"));
        assertEquals(1, body.get("version"));
        assertEquals(List.of("张三", "李四"), body.get("personnel"));

        ApiException dup = assertThrows(ApiException.class, () -> maintenanceService.create(
                rideId, new MaintenanceCreateRequest("m-c2", List.of("张三", "张三"))));
        assertEquals(422, dup.status().value());

        ApiException empty = assertThrows(ApiException.class, () -> maintenanceService.create(
                rideId, new MaintenanceCreateRequest("m-c3", List.of())));
        assertEquals(422, empty.status().value());

        List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "人员" + i).toList();
        ApiException over = assertThrows(ApiException.class, () -> maintenanceService.create(
                rideId, new MaintenanceCreateRequest("m-c4", tooMany)));
        assertEquals(422, over.status().value());
    }

    @Test
    @DisplayName("启动：整机停运并为每人建立唯一未撤锁，清单冻结，版本加1")
    void startStopsRideAndCreatesUniqueLocks() {
        makeReady(rideId, "k");
        long mid = createOrder("m-c", "张三", "李四");

        ApiResult result = maintenanceService.start(rideId, mid,
                new MaintenanceStartRequest("m-s", 1L));
        Map<String, Object> body = bodyOf(result);
        assertEquals("IN_PROGRESS", body.get("status"));
        assertEquals(2, body.get("version"));
        assertEquals("STOPPED", statusOf(rideId));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locks = (List<Map<String, Object>>) body.get("locks");
        assertEquals(2, locks.size());
        assertTrue(locks.stream().allMatch(l -> Boolean.FALSE.equals(l.get("removed"))));
        assertEquals(List.of("张三", "李四"), body.get("unremovedPersonnel"));
        // 每人唯一未撤锁：数据库唯一约束
        Integer lockCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock WHERE maintenance_id = ?",
                Integer.class, mid);
        assertEquals(2, lockCount);
    }

    @Test
    @DisplayName("已载客或其他检修占用时拒绝启动；归档设备拒绝建档与启动")
    void startRejectedWhenOpenOrOccupied() {
        makeOpen(rideId, "k");
        long mid = createOrder("m-c", "张三");
        ApiException carrying = assertThrows(ApiException.class, () -> maintenanceService.start(
                rideId, mid, new MaintenanceStartRequest("m-s", 1L)));
        assertEquals(409, carrying.status().value());
        assertTrue(carrying.getMessage().contains("已载客"));

        // 停运后第一张单启动成功，第二张单因占用被拒绝
        rideService.safetyFault(rideId, new SafetyFaultRequest("k-f", "压杠"));
        startOk(mid, "m-s2", 1L);
        long mid2 = createOrder("m-c2", "李四");
        ApiException occupied = assertThrows(ApiException.class, () -> maintenanceService.start(
                rideId, mid2, new MaintenanceStartRequest("m-s3", 1L)));
        assertEquals(409, occupied.status().value());
        assertTrue(occupied.getMessage().contains("只允许一张进行中"));

        // 归档设备拒绝建档
        long archivedRide = ((Number) rideService.createRide(
                new CreateRideRequest("旋转木马", 12, 40)).get("rideId")).longValue();
        rideService.archive(archivedRide,
                new com.example.starter.ride.dto.RideDtos.ArchiveRequest("k-arch"));
        ApiException archived = assertThrows(ApiException.class, () -> maintenanceService.create(
                archivedRide, new MaintenanceCreateRequest("m-c3", List.of("王五"))));
        assertEquals(409, archived.status().value());
    }

    @Test
    @DisplayName("撤锁：本人与不同监护员共同确认生效，单人/重复身份/代签均拒绝")
    void lockRemovalRequiresTwoDistinctPersons() {
        makeReady(rideId, "k");
        long mid = createOrder("m-c", "张三", "李四");
        startOk(mid, "m-s", 1L);

        // 本人与监护员同一人 → 422
        ApiException same = assertThrows(ApiException.class, () -> maintenanceService.removeLock(
                rideId, mid, new LockRemovalRequest("m-u0", "张三", "张三", 2L)));
        assertEquals(422, same.status().value());

        // 替未归还人员代签（人员不在冻结清单）→ 422
        ApiException proxy = assertThrows(ApiException.class, () -> maintenanceService.removeLock(
                rideId, mid, new LockRemovalRequest("m-u1", "王五", "赵六", 2L)));
        assertEquals(422, proxy.status().value());
        assertTrue(proxy.getMessage().contains("代签"));

        // 合法双人签认：本人张三 + 监护员李四
        ApiResult removed = maintenanceService.removeLock(rideId, mid,
                new LockRemovalRequest("m-u2", "张三", "李四", 2L));
        Map<String, Object> body = bodyOf(removed);
        assertEquals(3, body.get("version"));
        assertEquals(List.of("李四"), body.get("unremovedPersonnel"));

        // 重复撤锁 → 409，签认不可覆盖
        ApiException again = assertThrows(ApiException.class, () -> maintenanceService.removeLock(
                rideId, mid, new LockRemovalRequest("m-u3", "张三", "李四", 3L)));
        assertEquals(409, again.status().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock_removal", Integer.class));
    }

    @Test
    @DisplayName("全部锁撤除后方可结束；结束只清除挂牌阻断，不自动开放、不解除故障停运")
    void finishOnlyAfterAllLocksRemoved() {
        makeOpen(rideId, "k");
        rideService.safetyFault(rideId, new SafetyFaultRequest("k-f", "安全带"));
        assertEquals("STOPPED", statusOf(rideId));
        long mid = createOrder("m-c", "张三", "李四");
        startOk(mid, "m-s", 1L);

        // 存在未撤锁时结束 → 409
        ApiException early = assertThrows(ApiException.class, () -> maintenanceService.finish(
                rideId, mid, new MaintenanceFinishRequest("m-fin0", 2L)));
        assertEquals(409, early.status().value());
        assertTrue(early.getMessage().contains("未撤锁"));

        removeLockOk(mid, "m-u1", "张三", "李四", 2L);
        // 仍有一把未撤锁 → 409
        ApiException stillOne = assertThrows(ApiException.class, () -> maintenanceService.finish(
                rideId, mid, new MaintenanceFinishRequest("m-fin1", 3L)));
        assertEquals(409, stillOne.status().value());

        removeLockOk(mid, "m-u2", "李四", "张三", 3L);
        ApiResult finished = maintenanceService.finish(rideId, mid,
                new MaintenanceFinishRequest("m-fin2", 4L));
        Map<String, Object> body = bodyOf(finished);
        assertEquals("FINISHED", body.get("status"));
        assertEquals(5, body.get("version"));
        assertEquals(List.of(), body.get("unremovedPersonnel"));

        // 结束不自动开放、不解除原故障停运：设备仍STOPPED，须继续原复检流程
        assertEquals("STOPPED", statusOf(rideId));
        ApiException openDenied = assertThrows(ApiException.class,
                () -> rideService.open(rideId, new OpenRequest("k-open-2")));
        assertEquals(409, openDenied.status().value());
        // 原复检流程：复检合格→复开
        rideService.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("安全带", true))));
        ApiResult reopened = rideService.reopen(rideId, new ReopenRequest("k-re1"));
        assertEquals("OPEN", bodyOf(reopened).get("status"));
        assertEquals(1, bodyOf(reopened).get("reopenCount"));
    }

    @Test
    @DisplayName("未撤锁拒开：PREPARED与进行中检修单均阻断开放，结束后仍须原资格复开")
    void unfinishedMaintenanceBlocksOpenAndReopen() {
        makeReady(rideId, "k");
        // PREPARED（未启动）检修单即阻断开放
        long mid = createOrder("m-c", "张三", "李四");
        ApiException preparedBlock = assertThrows(ApiException.class,
                () -> rideService.open(rideId, new OpenRequest("k-open")));
        assertEquals(409, preparedBlock.status().value());
        assertTrue(preparedBlock.getMessage().contains("未结束检修单"));

        // 启动后设备停运，撤锁未完成时复检合格也不能复开
        startOk(mid, "m-s", 1L);
        removeLockOk(mid, "m-u1", "张三", "李四", 2L);
        rideService.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("压杠", true))));
        assertEquals("RECHECKED", statusOf(rideId));
        ApiException lockBlock = assertThrows(ApiException.class,
                () -> rideService.reopen(rideId, new ReopenRequest("k-re1")));
        assertEquals(409, lockBlock.status().value());
        assertTrue(lockBlock.getMessage().contains("未结束检修单"));

        // 全部撤锁并结束后，原资格复开成功（两人检修后仍须原资格复开）
        removeLockOk(mid, "m-u2", "李四", "张三", 3L);
        ApiResult finished = maintenanceService.finish(rideId, mid,
                new MaintenanceFinishRequest("m-fin", 4L));
        assertEquals(200, finished.status());
        ApiResult reopened = rideService.reopen(rideId, new ReopenRequest("k-re2"));
        assertEquals("OPEN", bodyOf(reopened).get("status"));
    }

    @Test
    @DisplayName("结束不重置日检与当日复开次数")
    void finishDoesNotResetCheckDateOrReopenCount() {
        makeOpen(rideId, "k");
        // 第1次故障→复检→复开，复开次数=1
        rideService.safetyFault(rideId, new SafetyFaultRequest("k-f1", "压杠"));
        rideService.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("压杠", true))));
        rideService.reopen(rideId, new ReopenRequest("k-re1"));
        assertEquals(1, rideService.getRide(rideId).get("reopenCount"));

        // 检修全流程
        rideService.safetyFault(rideId, new SafetyFaultRequest("k-f2", "安全带"));
        long mid = createOrder("m-c", "张三");
        startOk(mid, "m-s", 1L);
        removeLockOk(mid, "m-u1", "张三", "李四", 2L);
        maintenanceService.finish(rideId, mid, new MaintenanceFinishRequest("m-fin", 3L));

        // 日检日期与复开次数保持不变
        Map<String, Object> view = rideService.getRide(rideId);
        assertEquals(1, view.get("reopenCount"));
        assertEquals("2026-09-26", view.get("checkDate"));
        // 复开仍计入原当日次数：第2次复开合法（恰2次）
        rideService.recheck(rideId, new RecheckRequest("k-r2",
                List.of(new CheckItem("安全带", true))));
        ApiResult reopened = rideService.reopen(rideId, new ReopenRequest("k-re2"));
        assertEquals(2, bodyOf(reopened).get("reopenCount"));
    }

    @Test
    @DisplayName("单据版本：修改提交expectedVersion，成功加1、旧版本409")
    void versionOptimisticLocking() {
        makeReady(rideId, "k");
        long mid = createOrder("m-c", "张三");
        // 旧版本启动 → 409
        ApiException stale = assertThrows(ApiException.class, () -> maintenanceService.start(
                rideId, mid, new MaintenanceStartRequest("m-s0", 9L)));
        assertEquals(409, stale.status().value());
        assertTrue(stale.getMessage().contains("版本冲突"));

        startOk(mid, "m-s", 1L);
        // 旧版本撤锁 → 409
        ApiException staleUnlock = assertThrows(ApiException.class,
                () -> maintenanceService.removeLock(rideId, mid,
                        new LockRemovalRequest("m-u0", "张三", "李四", 1L)));
        assertEquals(409, staleUnlock.status().value());
        // 正确版本撤锁 → 版本加1
        removeLockOk(mid, "m-u1", "张三", "李四", 2L);
        // 旧版本结束 → 409
        ApiException staleFinish = assertThrows(ApiException.class, () -> maintenanceService.finish(
                rideId, mid, new MaintenanceFinishRequest("m-f0", 2L)));
        assertEquals(409, staleFinish.status().value());
        ApiResult finished = maintenanceService.finish(rideId, mid,
                new MaintenanceFinishRequest("m-f1", 3L));
        assertEquals(4, bodyOf(finished).get("version"));
    }

    @Test
    @DisplayName("幂等：同键同参重放冻结响应，异参409，失败不占键")
    void idempotencyReplayConflictAndFailureNotOccupying() {
        makeOpen(rideId, "k");
        // 同键同参重放建档：返回首次结果，只落一张单
        MaintenanceCreateRequest create = new MaintenanceCreateRequest("m-c", List.of("张三", "李四"));
        ApiResult first = maintenanceService.create(rideId, create);
        ApiResult replay = maintenanceService.create(rideId, create);
        assertEquals(first.body(), replay.body());
        assertEquals(1, maintenanceService.listMaintenances(rideId).size());

        // 同键异参（清单不同）→ 409，指纹覆盖完整人员清单
        ApiException conflict = assertThrows(ApiException.class, () -> maintenanceService.create(
                rideId, new MaintenanceCreateRequest("m-c", List.of("张三", "王五"))));
        assertEquals(409, conflict.status().value());
        assertEquals(1, maintenanceService.listMaintenances(rideId).size());

        long mid = ((Number) bodyOf(first).get("maintenanceId")).longValue();
        // 失败不占键：设备OPEN（已载客）时启动失败，同requestId在停运后可成功
        ApiException failed = assertThrows(ApiException.class, () -> maintenanceService.start(
                rideId, mid, new MaintenanceStartRequest("m-s", 1L)));
        assertEquals(409, failed.status().value());
        rideService.safetyFault(rideId, new SafetyFaultRequest("k-f", "压杠"));
        ApiResult started = maintenanceService.start(rideId, mid,
                new MaintenanceStartRequest("m-s", 1L));
        assertEquals(200, started.status());
        // 同键重放启动：冻结首次成功响应
        ApiResult startedReplay = maintenanceService.start(rideId, mid,
                new MaintenanceStartRequest("m-s", 1L));
        assertEquals(started.body(), startedReplay.body());
    }

    @Test
    @DisplayName("查询显示每个未撤锁人，历史清单与双人签认可复算")
    void queryShowsEveryUnremovedPerson() {
        makeReady(rideId, "k");
        long mid = createOrder("m-c", "张三", "李四", "王五");
        startOk(mid, "m-s", 1L);
        removeLockOk(mid, "m-u1", "张三", "李四", 2L);

        Map<String, Object> detail = maintenanceService.getMaintenance(rideId, mid);
        assertEquals(List.of("李四", "王五"), detail.get("unremovedPersonnel"));
        assertEquals(List.of("张三", "李四", "王五"), detail.get("personnel"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locks = (List<Map<String, Object>>) detail.get("locks");
        assertEquals(3, locks.size());
        Map<String, Object> zhangsan = locks.stream()
                .filter(l -> "张三".equals(l.get("personName"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, zhangsan.get("removed"));
        assertEquals("张三", zhangsan.get("confirmedBy"));
        assertEquals("李四", zhangsan.get("supervisedBy"));
        // 签认记录不可覆盖：仅一条
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock_removal", Integer.class));
        // 设备维度列表包含该检修单
        assertEquals(1, maintenanceService.listMaintenances(rideId).size());
        // 不存在设备/检修单 → 404
        ApiException notFound = assertThrows(ApiException.class,
                () -> maintenanceService.getMaintenance(rideId, 99999L));
        assertEquals(404, notFound.status().value());
    }

    @Test
    @DisplayName("故障停运与开放保持原唯一终态：检修中禁止安全流程外开放，事件链可复算")
    void eventChainIncludesMaintenanceEvents() {
        makeReady(rideId, "k");
        long mid = createOrder("m-c", "张三");
        startOk(mid, "m-s", 1L);
        removeLockOk(mid, "m-u1", "张三", "李四", 2L);
        maintenanceService.finish(rideId, mid, new MaintenanceFinishRequest("m-fin", 3L));

        List<String> types = rideService.listEvents(rideId).stream()
                .map(ev -> (String) ev.get("event_type")).toList();
        assertTrue(types.containsAll(
                List.of("MAINT_CREATE", "MAINT_START", "MAINT_UNLOCK", "MAINT_FINISH")));
        // 结束后设备仍STOPPED（启动检修时的整机停运），不自动开放
        assertEquals("STOPPED", statusOf(rideId));
        assertFalse(types.contains("OPEN"));
    }
}
