package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import com.example.starter.ride.dto.RideDtos.CreateMaintenanceOrderRequest;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.FinishMaintenanceRequest;
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.RemoveLockRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.StartMaintenanceRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检修挂牌流程业务测试（真实 H2，MySQL 兼容模式，可控固定时钟）。
 * 覆盖建单冻结、整机停运、双人签认撤锁、版本乐观锁、幂等指纹与原资格复开门禁。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class MaintenanceServiceTest {

    @Autowired
    private RideService service;

    @Autowired
    private Idempotency.Executor idempotency;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM maintenance_lock");
        jdbc.update("DELETE FROM maintenance_person");
        jdbc.update("DELETE FROM maintenance_order");
        jdbc.update("DELETE FROM idempotency_record");
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
    }

    private long createRide(String name, boolean inspectionRequired) {
        return ((Number) service.createRide(
                new CreateRideRequest(name, 10, 80, inspectionRequired)).get("rideId"))
                .longValue();
    }

    private long createPlainRide() {
        return createRide("检修测试过山车", false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ApiResult result) {
        try {
            return objectMapper.readValue(result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void makeReady(long id, String keyPrefix) {
        assertEquals(200, service.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check",
                List.of(new CheckItem("压杠", true), new CheckItem("安全带", true),
                        new CheckItem("联锁装置", true)))).status());
        for (int i = 1; i <= 3; i++) {
            assertEquals(200, service.trialRun(id,
                    new TrialRunRequest(keyPrefix + "-t" + i, true)).status());
        }
    }

    private long createPreparedOrder(long rideId, String requestId, List<String> persons) {
        ApiResult result = service.createMaintenanceOrder(rideId,
                new CreateMaintenanceOrderRequest(requestId, persons));
        assertEquals(200, result.status());
        Map<String, Object> body = bodyOf(result);
        assertEquals("PREPARED", body.get("status"));
        assertEquals(1, ((Number) body.get("version")).longValue());
        return ((Number) body.get("orderId")).longValue();
    }

    private ApiResult start(long orderId, String requestId, long expectedVersion) {
        return service.startMaintenance(orderId,
                new StartMaintenanceRequest(requestId, expectedVersion));
    }

    private ApiResult removeLock(long orderId, String requestId, String person,
            String lockHolder, String guardian) {
        return service.removeLock(orderId,
                new RemoveLockRequest(requestId, person, lockHolder, guardian));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> locksOf(long orderId) {
        return (List<Map<String, Object>>) service.getMaintenanceOrder(orderId).get("locks");
    }

    @Test
    @DisplayName("主流程：两人建单→启动整机停运各上唯一锁→双人签认撤锁→结束仅清除阻断，仍须原复开流程")
    void fullTagOutFlowRestoresSuspendedAndRequiresOriginalReopenFlow() {
        long rideId = createPlainRide();
        makeReady(rideId, "m");
        long orderId = createPreparedOrder(rideId, "m-create", List.of("钳工甲", "电工乙"));

        // 启动：版本1→2，整机 MAINTENANCE，每人一把未撤锁
        ApiResult started = start(orderId, "m-start", 1);
        assertEquals(200, started.status());
        Map<String, Object> startedBody = bodyOf(started);
        assertEquals("IN_PROGRESS", startedBody.get("status"));
        assertEquals(2, ((Number) startedBody.get("version")).longValue());
        assertEquals("READY", startedBody.get("priorStatus"));
        assertEquals("MAINTENANCE", service.getRide(rideId).get("status"));
        assertEquals(2, locksOf(orderId).size());
        assertEquals(List.of("钳工甲", "电工乙"), startedBody.get("unremovedPersons"));

        // 进行中且有未撤锁：开放入口直接被挂牌门禁拒绝（而非状态机拒绝）
        ApiException openBlocked = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("m-open-blocked")));
        assertEquals(409, openBlocked.status().value());
        assertTrue(openBlocked.getMessage().contains("检修单"));

        // 单人签认不生效：本人与监护员同人
        ApiException samePerson = assertThrows(ApiException.class,
                () -> removeLock(orderId, "m-rm-same", "钳工甲", "钳工甲", "钳工甲"));
        assertEquals(422, samePerson.status().value());
        // 非本人签认不生效，不能代签
        ApiException notSelf = assertThrows(ApiException.class,
                () -> removeLock(orderId, "m-rm-notself", "钳工甲", "电工乙", "监护员丙"));
        assertEquals(422, notSelf.status().value());
        assertTrue(notSelf.getMessage().contains("本人"));
        // 清单外人员无锁可撤
        ApiException outsider = assertThrows(ApiException.class,
                () -> removeLock(orderId, "m-rm-out", "路人丁", "路人丁", "监护员丙"));
        assertEquals(422, outsider.status().value());
        // 失败均未撤锁
        assertEquals(2, ((List<?>) service.getMaintenanceOrder(orderId)
                .get("unremovedPersons")).size());

        // 正确双人签认：本人 + 不同监护员
        assertEquals(200, removeLock(orderId, "m-rm-1", "钳工甲", "钳工甲", "监护员丙").status());
        Map<String, Object> afterFirst = service.getMaintenanceOrder(orderId);
        assertEquals(List.of("电工乙"), afterFirst.get("unremovedPersons"));

        // 仍有未撤锁禁止结束
        ApiException finishEarly = assertThrows(ApiException.class,
                () -> service.finishMaintenance(orderId, new FinishMaintenanceRequest("m-fin-early", 2L)));
        assertEquals(422, finishEarly.status().value());
        assertTrue(finishEarly.getMessage().contains("电工乙"));

        assertEquals(200, removeLock(orderId, "m-rm-2", "电工乙", "电工乙", "监护员丙").status());
        // 结束：版本2→3，仅清除挂牌阻断；READY 进入检修的设备回到 SUSPENDED，不自动开放
        ApiResult finished = service.finishMaintenance(orderId,
                new FinishMaintenanceRequest("m-fin", 2L));
        assertEquals(200, finished.status());
        Map<String, Object> finishedBody = bodyOf(finished);
        assertEquals("FINISHED", finishedBody.get("status"));
        assertEquals(3, ((Number) finishedBody.get("version")).longValue());
        assertEquals("SUSPENDED", service.getRide(rideId).get("status"));
        assertEquals(List.of(), finishedBody.get("unremovedPersons"));

        // 不自动开放：直接复开要求复检合格 → 409
        ApiException reopenBeforeRecheck = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("m-reopen-no")));
        assertEquals(409, reopenBeforeRecheck.status().value());
        // 仍走原复检→复开流程；日检不重置，无需重新日检与试运行
        assertEquals(200, service.recheck(rideId, new RecheckRequest("m-recheck",
                List.of(new CheckItem("压杠", true)))).status());
        ApiResult reopened = service.reopen(rideId, new ReopenRequest("m-reopen"));
        assertEquals(200, reopened.status());
        assertEquals("OPEN", bodyOf(reopened).get("status"));
        assertEquals(1, ((Number) bodyOf(reopened).get("reopenCount")).longValue());
    }

    @Test
    @DisplayName("启动前为原故障停运：结束检修不解除故障，仍须原复检流程")
    void faultStoppedRideRemainsStoppedAfterMaintenance() {
        long rideId = createPlainRide();
        makeReady(rideId, "f");
        assertEquals(200, service.open(rideId, new OpenRequest("f-open")).status());
        assertEquals(200, service.safetyFault(rideId,
                new SafetyFaultRequest("f-fault", "压杠")).status());
        assertEquals("STOPPED", service.getRide(rideId).get("status"));

        long orderId = createPreparedOrder(rideId, "f-create", List.of("钳工甲"));
        assertEquals(200, start(orderId, "f-start", 1).status());
        assertEquals("STOPPED", service.getMaintenanceOrder(orderId).get("priorStatus"));
        assertEquals(200, removeLock(orderId, "f-rm", "钳工甲", "钳工甲", "监护员丙").status());
        assertEquals(200, service.finishMaintenance(orderId,
                new FinishMaintenanceRequest("f-fin", 2L)).status());

        // 结束检修不解除故障
        assertEquals("STOPPED", service.getRide(rideId).get("status"));
        // 继续原复检流程后方可复开
        assertEquals(200, service.recheck(rideId, new RecheckRequest("f-recheck",
                List.of(new CheckItem("压杠", true)))).status());
        assertEquals("OPEN", bodyOf(service.reopen(rideId, new ReopenRequest("f-reopen")))
                .get("status"));
    }

    @Test
    @DisplayName("已载客禁止启动检修；同设备只允许一张进行中检修单")
    void startRejectedWhenOpenOrAnotherOrderActive() {
        long rideId = createPlainRide();
        makeReady(rideId, "b");

        // 第二张 PREPARED 检修单先备着
        long secondOrder = createPreparedOrder(rideId, "b-create-2", List.of("钳工甲"));
        // 已载客 → 拒绝启动
        assertEquals(200, service.open(rideId, new OpenRequest("b-open")).status());
        long orderId = createPreparedOrder(rideId, "b-create-1", List.of("钳工甲", "电工乙"));
        ApiException whileOpen = assertThrows(ApiException.class,
                () -> start(orderId, "b-start-open", 1));
        assertEquals(409, whileOpen.status().value());
        assertTrue(whileOpen.getMessage().contains("已载客"));
        assertEquals("OPEN", service.getRide(rideId).get("status"));
        assertEquals("PREPARED", service.getMaintenanceOrder(orderId).get("status"));

        // 故障停运后：第一张启动成功，第二张被进行中检修单占用拒绝
        service.safetyFault(rideId, new SafetyFaultRequest("b-fault", "压杠"));
        assertEquals(200, start(orderId, "b-start", 1).status());
        ApiException occupied = assertThrows(ApiException.class,
                () -> start(secondOrder, "b-start-2", 1));
        assertEquals(409, occupied.status().value());
        assertTrue(occupied.getMessage().contains("进行中检修单"));
        assertEquals("PREPARED", service.getMaintenanceOrder(secondOrder).get("status"));
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order WHERE ride_id = ? AND status = 'IN_PROGRESS'",
                Integer.class, rideId);
        assertEquals(1, activeCount);
    }

    @Test
    @DisplayName("版本乐观锁：旧 expectedVersion 启动/结束均409，成功加1")
    void staleExpectedVersionConflicts() {
        long rideId = createPlainRide();
        long orderId = createPreparedOrder(rideId, "v-create", List.of("钳工甲", "电工乙"));

        ApiException staleStart = assertThrows(ApiException.class,
                () -> start(orderId, "v-start-stale", 7));
        assertEquals(409, staleStart.status().value());
        assertEquals(1, ((Number) service.getMaintenanceOrder(orderId).get("version")).longValue());
        assertEquals("PENDING_CHECK", service.getRide(rideId).get("status"),
                "版本冲突不得停运设备");

        assertEquals(200, start(orderId, "v-start", 1).status());
        // 结束携带旧版本1 → 409
        ApiException staleFinish = assertThrows(ApiException.class,
                () -> service.finishMaintenance(orderId, new FinishMaintenanceRequest("v-fin-stale", 1L)));
        assertEquals(409, staleFinish.status().value());
        assertEquals("IN_PROGRESS", service.getMaintenanceOrder(orderId).get("status"));

        // 撤完锁后用当前版本2结束成功，版本变为3
        removeLock(orderId, "v-rm-1", "钳工甲", "钳工甲", "监护员丙");
        removeLock(orderId, "v-rm-2", "电工乙", "电工乙", "监护员丙");
        assertEquals(200, service.finishMaintenance(orderId,
                new FinishMaintenanceRequest("v-fin", 2L)).status());
        // 已结束单据再次结束（即使带版本3）→ 409
        ApiException again = assertThrows(ApiException.class,
                () -> service.finishMaintenance(orderId, new FinishMaintenanceRequest("v-fin-again", 3L)));
        assertEquals(409, again.status().value());
    }

    @Test
    @DisplayName("挂牌人员1至20名不同人员：空/超员/重复均422，姓名去空白")
    void personsMustBeOneToTwentyDistinct() {
        long rideId = createPlainRide();
        ApiException empty = assertThrows(ApiException.class,
                () -> service.createMaintenanceOrder(rideId,
                        new CreateMaintenanceOrderRequest("p-empty", List.of())));
        assertEquals(422, empty.status().value());

        List<String> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add("人员" + i);
        }
        ApiException oversize = assertThrows(ApiException.class,
                () -> service.createMaintenanceOrder(rideId,
                        new CreateMaintenanceOrderRequest("p-21", tooMany)));
        assertEquals(422, oversize.status().value());

        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.createMaintenanceOrder(rideId,
                        new CreateMaintenanceOrderRequest("p-dup", List.of("钳工甲", "钳工甲"))));
        assertEquals(422, duplicate.status().value());

        // 20 人合法；姓名 trim 后重复仍判重
        List<String> twenty = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            twenty.add("人员" + i);
        }
        ApiResult ok = service.createMaintenanceOrder(rideId,
                new CreateMaintenanceOrderRequest("p-20", twenty));
        assertEquals(200, ok.status());
        ApiException trimDup = assertThrows(ApiException.class,
                () -> service.createMaintenanceOrder(rideId,
                        new CreateMaintenanceOrderRequest("p-trim", List.of(" 钳工甲 ", "钳工甲"))));
        assertEquals(422, trimDup.status().value());
    }

    @Test
    @DisplayName("撤锁一次性：重复撤锁409，历史双人签认不可覆盖；冻结清单不随后续请求变化")
    void lockRemovalIsOneShotAndHistoryImmutable() {
        long rideId = createPlainRide();
        long orderId = createPreparedOrder(rideId, "o-create", List.of("钳工甲", "电工乙"));
        start(orderId, "o-start", 1);
        assertEquals(200, removeLock(orderId, "o-rm", "钳工甲", "钳工甲", "监护员丙").status());

        // 换请求键再次撤同一把锁 → 409，签认不被覆盖
        ApiException again = assertThrows(ApiException.class,
                () -> removeLock(orderId, "o-rm-again", "钳工甲", "钳工甲", "监护员丁"));
        assertEquals(409, again.status().value());
        Map<String, Object> lock = locksOf(orderId).stream()
                .filter(l -> "钳工甲".equals(l.get("personName"))).findFirst().orElseThrow();
        assertEquals("监护员丙", lock.get("guardian"), "历史监护员签认不可覆盖");
        assertEquals("钳工甲", lock.get("lockHolder"));
        assertTrue((Boolean) lock.get("removed"));

        // 历史冻结清单仍为两人，未撤锁只剩电工乙
        Map<String, Object> view = service.getMaintenanceOrder(orderId);
        assertEquals(List.of("钳工甲", "电工乙"), view.get("persons"));
        assertEquals(List.of("电工乙"), view.get("unremovedPersons"));

        // 未启动的单据不能撤锁
        long otherOrder = createPreparedOrder(rideId, "o-create-2", List.of("钳工甲"));
        ApiException notStarted = assertThrows(ApiException.class,
                () -> removeLock(otherOrder, "o-rm-prepared", "钳工甲", "钳工甲", "监护员丙"));
        assertEquals(409, notStarted.status().value());
    }

    @Test
    @DisplayName("幂等：同参重放冻结响应，异参409，失败不占键；指纹覆盖完整清单与人员字段")
    void idempotencyReplaysAndFingerprintsCoverAllFields() {
        long rideId = createPlainRide();
        CreateMaintenanceOrderRequest createReq =
                new CreateMaintenanceOrderRequest("i-create", List.of("钳工甲", "电工乙"));
        ApiResult first = service.createMaintenanceOrder(rideId, createReq);
        ApiResult replay = service.createMaintenanceOrder(rideId, createReq);
        assertEquals(200, first.status());
        assertEquals(first.body(), replay.body());
        long orderId = ((Number) bodyOf(first).get("orderId")).longValue();
        assertEquals(1, service.listMaintenanceOrders(rideId).size());

        // 同键异参（清单换人/换序）→ 409 且不新建单
        ApiException diffPerson = assertThrows(ApiException.class,
                () -> service.createMaintenanceOrder(rideId,
                        new CreateMaintenanceOrderRequest("i-create", List.of("钳工甲", "路人丁"))));
        assertEquals(409, diffPerson.status().value());
        ApiException diffOrder = assertThrows(ApiException.class,
                () -> service.createMaintenanceOrder(rideId,
                        new CreateMaintenanceOrderRequest("i-create", List.of("电工乙", "钳工甲"))));
        assertEquals(409, diffOrder.status().value());
        assertEquals(1, service.listMaintenanceOrders(rideId).size());

        // 启动同参重放：冻结 version=2 响应，不重复建锁
        StartMaintenanceRequest startReq = new StartMaintenanceRequest("i-start", 1L);
        ApiResult start1 = service.startMaintenance(orderId, startReq);
        ApiResult start2 = service.startMaintenance(orderId, startReq);
        assertEquals(start1.body(), start2.body());
        assertEquals(2, locksOf(orderId).size());

        // 撤锁同参重放：冻结成功响应
        RemoveLockRequest rmReq = new RemoveLockRequest("i-rm", "钳工甲", "钳工甲", "监护员丙");
        ApiResult rm1 = service.removeLock(orderId, rmReq);
        ApiResult rm2 = service.removeLock(orderId, rmReq);
        assertEquals(rm1.body(), rm2.body());
        // 同键异参（换监护员）→ 409，签认未被改写
        ApiException rmDiff = assertThrows(ApiException.class,
                () -> service.removeLock(orderId,
                        new RemoveLockRequest("i-rm", "钳工甲", "钳工甲", "监护员丁")));
        assertEquals(409, rmDiff.status().value());
        assertEquals("监护员丙", locksOf(orderId).stream()
                .filter(l -> "钳工甲".equals(l.get("personName"))).findFirst().orElseThrow()
                .get("guardian"));

        // 失败不占键：单人签认请求失败后，同键换成合法双人参数可成功
        ApiException sameFails = assertThrows(ApiException.class,
                () -> removeLock(orderId, "i-rm-2", "电工乙", "电工乙", "电工乙"));
        assertEquals(422, sameFails.status().value());
        Integer failedKey = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'i-rm-2'",
                Integer.class);
        assertEquals(0, failedKey, "失败不占幂等键");
        assertEquals(200, removeLock(orderId, "i-rm-2", "电工乙", "电工乙", "监护员丙").status());

        // 同 requestId 跨检修单重放：orderId 纳入指纹，即使请求体相同也判异参 409
        assertEquals(200, service.finishMaintenance(orderId,
                new FinishMaintenanceRequest("i-fin", 2L)).status());
        long secondOrder = ((Number) bodyOf(service.createMaintenanceOrder(rideId,
                new CreateMaintenanceOrderRequest("i-create-2", List.of("钳工甲"))))
                .get("orderId")).longValue();
        ApiException crossOrder = assertThrows(ApiException.class,
                () -> service.startMaintenance(secondOrder,
                        new StartMaintenanceRequest("i-start", 1L)));
        assertEquals(409, crossOrder.status().value());
        assertEquals("PREPARED", service.getMaintenanceOrder(secondOrder).get("status"));

        // 指纹结构化覆盖人员字段：人员与监护员任何差异都产生不同指纹
        assertNotEquals(
                idempotency.fingerprint("r", new RemoveLockRequest("r", "甲", "甲", "乙")),
                idempotency.fingerprint("r", new RemoveLockRequest("r", "甲", "甲", "丙")));
        assertNotEquals(
                idempotency.fingerprint("r", new RemoveLockRequest("r", "甲", "甲", "乙")),
                idempotency.fingerprint("r", new RemoveLockRequest("r", "乙", "乙", "甲")));
        assertNotEquals(
                idempotency.fingerprint("r", new CreateMaintenanceOrderRequest("r", List.of("甲", "乙"))),
                idempotency.fingerprint("r", new CreateMaintenanceOrderRequest("r", List.of("甲|乙"))));
    }

    @Test
    @DisplayName("检修不重置当日复开次数：检修前已复开1次，检修后再复开为第2次，达上限当日禁开")
    void maintenanceDoesNotResetDailyReopenCount() {
        long rideId = createPlainRide();
        makeReady(rideId, "c");
        service.open(rideId, new OpenRequest("c-open"));
        service.safetyFault(rideId, new SafetyFaultRequest("c-f1", "压杠"));
        service.recheck(rideId, new RecheckRequest("c-r1", List.of(new CheckItem("压杠", true))));
        assertEquals(1, ((Number) bodyOf(service.reopen(rideId, new ReopenRequest("c-re1")))
                .get("reopenCount")).longValue());

        // 第二次故障停运期间检修
        service.safetyFault(rideId, new SafetyFaultRequest("c-f2", "安全带"));
        long orderId = createPreparedOrder(rideId, "c-create", List.of("钳工甲"));
        start(orderId, "c-start", 1);
        removeLock(orderId, "c-rm", "钳工甲", "钳工甲", "监护员丙");
        service.finishMaintenance(orderId, new FinishMaintenanceRequest("c-fin", 2L));
        assertEquals("STOPPED", service.getRide(rideId).get("status"));

        service.recheck(rideId, new RecheckRequest("c-r2", List.of(new CheckItem("压杠", true))));
        ApiResult secondReopen = service.reopen(rideId, new ReopenRequest("c-re2"));
        assertEquals(2, ((Number) bodyOf(secondReopen).get("reopenCount")).longValue());

        // 第三次复开仍受原上限限制
        service.safetyFault(rideId, new SafetyFaultRequest("c-f3", "联锁装置"));
        service.recheck(rideId, new RecheckRequest("c-r3", List.of(new CheckItem("压杠", true))));
        ApiException thirdDenied = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("c-re3")));
        assertEquals(422, thirdDenied.status().value());
        assertTrue(thirdDenied.getMessage().contains("上限"));
    }

    @Test
    @DisplayName("受检设备检修结束后复开仍须有效年度证书；证书在挂牌期间撤销则复开拒绝")
    void inspectionRideStillRequiresCertificateAfterMaintenance() {
        long rideId = createRide("受检检修大摆锤", true);
        makeReady(rideId, "s");
        ApiResult cert = service.issueCertificate(rideId,
                new IssueCertificateRequest("s-cert", null, null));
        assertEquals(200, cert.status());

        long orderId = createPreparedOrder(rideId, "s-create", List.of("钳工甲", "电工乙"));
        start(orderId, "s-start", 1);
        removeLock(orderId, "s-rm-1", "钳工甲", "钳工甲", "监护员丙");
        // 挂牌期间撤销证书，撤锁与结束不受影响
        ApiException revokeStale = assertThrows(ApiException.class,
                () -> service.revokeCertificate(rideId,
                        new RevokeCertificateRequest("s-rev-stale", 99L, null)));
        assertEquals(409, revokeStale.status().value());
        assertEquals(200, service.revokeCertificate(rideId,
                new RevokeCertificateRequest("s-rev", 1L, "检修期间抽检")).status());
        removeLock(orderId, "s-rm-2", "电工乙", "电工乙", "监护员丙");
        service.finishMaintenance(orderId, new FinishMaintenanceRequest("s-fin", 2L));
        assertEquals("SUSPENDED", service.getRide(rideId).get("status"));

        // 原复检流程走完，但证书缺失门禁仍拒绝复开
        service.recheck(rideId, new RecheckRequest("s-recheck",
                List.of(new CheckItem("压杠", true))));
        ApiException noCert = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("s-reopen-no")));
        assertEquals(422, noCert.status().value());
        assertTrue(noCert.getMessage().contains("年度证书"));

        // 重新签发证书后原复开入口放行
        service.issueCertificate(rideId, new IssueCertificateRequest("s-cert-2", null, null));
        ApiResult reopened = service.reopen(rideId, new ReopenRequest("s-reopen"));
        assertEquals(200, reopened.status());
        assertEquals("OPEN", bodyOf(reopened).get("status"));
    }

    @Test
    @DisplayName("检修单不存在404；进行中检修单禁止归档绕行")
    void orderNotFoundAndArchiveBlockedDuringMaintenance() {
        long rideId = createPlainRide();
        ApiException noOrder = assertThrows(ApiException.class,
                () -> service.startMaintenance(999999, new StartMaintenanceRequest("x-start", 1L)));
        assertEquals(404, noOrder.status().value());
        ApiException noOrderFinish = assertThrows(ApiException.class,
                () -> service.getMaintenanceOrder(999998));
        assertEquals(404, noOrderFinish.status().value());

        long orderId = createPreparedOrder(rideId, "x-create", List.of("钳工甲"));
        start(orderId, "x-start", 1);
        ApiException archiveBlocked = assertThrows(ApiException.class,
                () -> service.archive(rideId,
                        new com.example.starter.ride.dto.RideDtos.ArchiveRequest("x-arch")));
        assertEquals(409, archiveBlocked.status().value());
        assertEquals("MAINTENANCE", service.getRide(rideId).get("status"));
    }

    @Test
    @DisplayName("查询展示冻结清单与每个未撤锁人，历史检修单全部可复算")
    void viewsShowFrozenRosterAndUnremovedPersons() {
        long rideId = createPlainRide();
        long first = createPreparedOrder(rideId, "q-create-1", List.of("钳工甲"));
        start(first, "q-start-1", 1);
        removeLock(first, "q-rm-1", "钳工甲", "钳工甲", "监护员丙");
        service.finishMaintenance(first, new FinishMaintenanceRequest("q-fin-1", 2L));

        long second = createPreparedOrder(rideId, "q-create-2", List.of("钳工甲", "电工乙"));
        start(second, "q-start-2", 1);

        List<Map<String, Object>> orders = service.listMaintenanceOrders(rideId);
        assertEquals(2, orders.size());
        Map<String, Object> activeView = service.getMaintenanceOrder(second);
        assertEquals(List.of("钳工甲", "电工乙"), activeView.get("unremovedPersons"));
        assertEquals(2, ((List<?>) activeView.get("locks")).size());
        // 历史单据：清单与签认仍可查，状态保持 FINISHED
        Map<String, Object> historyView = service.getMaintenanceOrder(first);
        assertEquals("FINISHED", historyView.get("status"));
        assertEquals(List.of("钳工甲"), historyView.get("persons"));
        Map<String, Object> historyLock = locksOf(first).get(0);
        assertTrue((Boolean) historyLock.get("removed"));
        assertEquals("监护员丙", historyLock.get("guardian"));
    }
}
