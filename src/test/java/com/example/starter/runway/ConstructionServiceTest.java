package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 施工段闭锁与可用区域的业务/H2 集成测试：
 * 覆盖登记/生效/验收主流程、重叠并集、单段验收、失败分支、版本与幂等、恢复竞争及并发边界。
 */
@SpringBootTest
class ConstructionServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-05T00:00:00Z");

    @Autowired
    private ConstructionService constructionService;
    @Autowired
    private RunwayService runwayService;
    @Autowired
    private RoutineService routineService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acceptance_credential");
        jdbc.update("DELETE FROM construction_segment");
        jdbc.update("DELETE FROM construction_order");
        jdbc.update("DELETE FROM controlled_runway");
        jdbc.update("DELETE FROM routine_completion_report");
        jdbc.update("DELETE FROM routine_check_item");
        jdbc.update("DELETE FROM routine_plan");
        jdbc.update("DELETE FROM flight_occupancy");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM runway_lock");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
        // 重建种子日历版本1（空占用窗）
        jdbc.update("MERGE INTO flight_calendar (version, published_at, request_id, created_at) KEY (version)"
                + " VALUES (1, ?, NULL, ?)", Timestamp.from(T0), Timestamp.from(T0));
    }

    private ControlledRunwayResponse register(String runway, int lengthMeters) {
        return constructionService.registerRunway(
                new RunwayRegisterRequest("reg-" + runway, runway, lengthMeters));
    }

    private ConstructionSegmentInput seg(int no, int start, int end) {
        return new ConstructionSegmentInput(no, start, end);
    }

    private ConstructionOrderResponse createOrder(String reqId, String runway,
                                                  ConstructionSegmentInput... segments) {
        return constructionService.createOrder(
                new ConstructionOrderCreateRequest(reqId, runway, List.of(segments)));
    }

    private ConstructionOrderResponse activate(long orderId, int expectedVersion) {
        return constructionService.activateOrder(orderId,
                new ConstructionActivateRequest("act-" + orderId, expectedVersion,
                        T0.plus(Duration.ofMinutes(1))));
    }

    private AcceptanceResponse accept(long orderId, int segmentNo, String reqId, String inspector) {
        return constructionService.submitAcceptance(orderId, segmentNo,
                new AcceptanceRequest(reqId, inspector, "CLR-" + inspector,
                        T0.plus(Duration.ofMinutes(30))));
    }

    /** 主流程：登记→施工单→生效→逐段双人验收→全部完成，可用区间随验收恢复。 */
    @Test
    void mainFlowRegisterActivateAcceptComplete() {
        ControlledRunwayResponse runway = register("RW-C1", 1000);
        assertEquals(1, runway.version());
        assertEquals(1000, runway.lengthMeters());

        ConstructionOrderResponse order = createOrder("co-1", "RW-C1", seg(1, 100, 400), seg(2, 400, 700));
        assertEquals("PENDING", order.status());
        assertEquals(1, order.version());
        assertEquals(2, order.segments().size());

        // 未生效不闭锁：全跑道可用
        AvailableAreasResponse before = constructionService.getAvailableAreas("RW-C1");
        assertEquals(1, before.available().size());
        assertEquals(1000, before.availableTotalMeters());

        ConstructionOrderResponse active = activate(order.id(), 1);
        assertEquals("ACTIVE", active.status());
        assertEquals(2, active.version());
        assertNotNull(active.activatedAt());

        AvailableAreasResponse locked = constructionService.getAvailableAreas("RW-C1");
        assertEquals(List.of(new IntervalView(100, 700, 600)), locked.unavailable());
        assertEquals(List.of(new IntervalView(0, 100, 100), new IntervalView(700, 1000, 300)),
                locked.available());
        assertEquals(400, locked.availableTotalMeters());

        // 段1：第一人凭据后仍闭锁，第二人（不同验收人）后完成
        AcceptanceResponse first = accept(order.id(), 1, "ac-1a", "inspector-A");
        assertEquals("LOCKED", first.segmentStatus());
        assertFalse(first.late());
        AcceptanceResponse second = accept(order.id(), 1, "ac-1b", "inspector-B");
        assertEquals("COMPLETED", second.segmentStatus());
        assertEquals("ACTIVE", second.orderStatus(), "段2未完成，施工单仍生效");

        AvailableAreasResponse afterSeg1 = constructionService.getAvailableAreas("RW-C1");
        assertEquals(List.of(new IntervalView(400, 700, 300)), afterSeg1.unavailable());

        accept(order.id(), 2, "ac-2a", "inspector-A");
        AcceptanceResponse last = accept(order.id(), 2, "ac-2b", "inspector-C");
        assertEquals("COMPLETED", last.segmentStatus());
        assertEquals("COMPLETED", last.orderStatus());

        ConstructionOrderResponse done = constructionService.getOrder(order.id());
        assertEquals("COMPLETED", done.status());
        assertNotNull(done.completedAt());
        AvailableAreasResponse freed = constructionService.getAvailableAreas("RW-C1");
        assertEquals(1, freed.available().size());
        assertEquals(1000, freed.availableTotalMeters());
        assertTrue(freed.unavailable().isEmpty());
    }

    /** 题干示例：1000米跑道两施工区间重叠，只验收一段时重叠区域仍不可用。 */
    @Test
    void overlappingOrdersUnionAndSingleSegmentAcceptance() {
        register("RW-C2", 1000);
        ConstructionOrderResponse orderA = createOrder("co-a", "RW-C2", seg(1, 100, 400));
        ConstructionOrderResponse orderB = createOrder("co-b", "RW-C2", seg(1, 300, 600));
        activate(orderA.id(), 1);
        activate(orderB.id(), 1);

        // 并集 [100,600)，重叠不重复计长度
        AvailableAreasResponse both = constructionService.getAvailableAreas("RW-C2");
        assertEquals(List.of(new IntervalView(100, 600, 500)), both.unavailable());
        assertEquals(500, both.unavailableTotalMeters());
        assertEquals(List.of(new IntervalView(0, 100, 100), new IntervalView(600, 1000, 400)),
                both.available());

        // 只验收单A的段：A完成，但 [300,400) 仍被单B占用
        accept(orderA.id(), 1, "ac-a1", "inspector-A");
        AcceptanceResponse doneA = accept(orderA.id(), 1, "ac-a2", "inspector-B");
        assertEquals("COMPLETED", doneA.orderStatus());

        AvailableAreasResponse afterA = constructionService.getAvailableAreas("RW-C2");
        assertEquals(List.of(new IntervalView(300, 600, 300)), afterA.unavailable());
        assertEquals(List.of(new IntervalView(0, 300, 300), new IntervalView(600, 1000, 400)),
                afterA.available());
        assertEquals(700, afterA.availableTotalMeters());

        // 验收单B后全跑道恢复可用
        accept(orderB.id(), 1, "ac-b1", "inspector-A");
        accept(orderB.id(), 1, "ac-b2", "inspector-B");
        AvailableAreasResponse all = constructionService.getAvailableAreas("RW-C2");
        assertEquals(List.of(new IntervalView(0, 1000, 1000)), all.available());
        assertEquals(1000, all.availableTotalMeters());
    }

    /** 同单内部重叠拒绝（422），端点相接允许；不同单可以重叠。 */
    @Test
    void sameOrderOverlapRejectedEndpointTouchAllowed() {
        register("RW-C3", 1000);
        ApiException overlap = assertThrows(ApiException.class,
                () -> createOrder("co-ov", "RW-C3", seg(1, 0, 500), seg(2, 499, 800)));
        assertEquals("CONSTRUCTION_SEGMENT_OVERLAP", overlap.getCode());
        assertEquals(422, overlap.getStatus().value());

        ConstructionOrderResponse touching = createOrder("co-touch", "RW-C3",
                seg(1, 0, 500), seg(2, 500, 800));
        assertEquals("PENDING", touching.status());

        // 不同单可以重叠
        ConstructionOrderResponse other = createOrder("co-other", "RW-C3", seg(1, 400, 600));
        assertEquals("PENDING", other.status());
    }

    /** 段校验：段号重复、区间越界、段数超上限均拒绝。 */
    @Test
    void segmentValidationFailures() {
        register("RW-C4", 1000);
        ApiException dupNo = assertThrows(ApiException.class,
                () -> createOrder("co-dup", "RW-C4", seg(1, 0, 100), seg(1, 200, 300)));
        assertEquals("CONSTRUCTION_SEGMENT_NO_DUPLICATE", dupNo.getCode());

        ApiException badRange = assertThrows(ApiException.class,
                () -> createOrder("co-range", "RW-C4", seg(1, 500, 500)));
        assertEquals("CONSTRUCTION_SEGMENT_RANGE_INVALID", badRange.getCode());

        ApiException beyond = assertThrows(ApiException.class,
                () -> createOrder("co-beyond", "RW-C4", seg(1, 900, 1001)));
        assertEquals("CONSTRUCTION_SEGMENT_RANGE_INVALID", beyond.getCode());

        List<ConstructionSegmentInput> eleven = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            eleven.add(seg(i, (i - 1) * 10, (i - 1) * 10 + 5));
        }
        ApiException tooMany = assertThrows(ApiException.class,
                () -> constructionService.createOrder(
                        new ConstructionOrderCreateRequest("co-many", "RW-C4", eleven)));
        assertEquals("CONSTRUCTION_SEGMENT_COUNT_INVALID", tooMany.getCode());
    }

    /** 未生效可撤回；已生效不得撤回、不得修改缩段。 */
    @Test
    void withdrawOnlyWhenPendingAndActiveIsImmutable() {
        register("RW-C5", 1000);
        ConstructionOrderResponse pending = createOrder("co-w1", "RW-C5", seg(1, 0, 100));
        ConstructionOrderResponse withdrawn = constructionService.withdrawOrder(pending.id(),
                new ConstructionWithdrawRequest("wd-1", 1));
        assertEquals("WITHDRAWN", withdrawn.status());
        assertEquals(2, withdrawn.version());
        assertNotNull(withdrawn.withdrawnAt());

        ConstructionOrderResponse active = createOrder("co-w2", "RW-C5", seg(1, 200, 300));
        activate(active.id(), 1);
        ApiException modifyActive = assertThrows(ApiException.class,
                () -> constructionService.modifyOrder(active.id(),
                        new ConstructionOrderModifyRequest("mod-x", 2, List.of(seg(1, 200, 250)))));
        assertEquals("CONSTRUCTION_ORDER_NOT_PENDING", modifyActive.getCode());
        assertEquals(409, modifyActive.getStatus().value());

        ApiException withdrawActive = assertThrows(ApiException.class,
                () -> constructionService.withdrawOrder(active.id(),
                        new ConstructionWithdrawRequest("wd-x", 2)));
        assertEquals("CONSTRUCTION_ORDER_NOT_PENDING", withdrawActive.getCode());

        // 已撤回单据同样不得再修改
        ApiException modifyWithdrawn = assertThrows(ApiException.class,
                () -> constructionService.modifyOrder(pending.id(),
                        new ConstructionOrderModifyRequest("mod-y", 2, List.of(seg(1, 0, 50)))));
        assertEquals("CONSTRUCTION_ORDER_NOT_PENDING", modifyWithdrawn.getCode());
    }

    /** 版本：新增版本1起，修改带 expectedVersion 成功加1，旧版409。 */
    @Test
    void optimisticVersionOnModify() {
        register("RW-C6", 1000);
        ConstructionOrderResponse order = createOrder("co-v1", "RW-C6", seg(1, 0, 100));
        assertEquals(1, order.version());

        ApiException stale = assertThrows(ApiException.class,
                () -> constructionService.modifyOrder(order.id(),
                        new ConstructionOrderModifyRequest("mod-s", 2, List.of(seg(1, 0, 200)))));
        assertEquals("CONSTRUCTION_VERSION_CONFLICT", stale.getCode());
        assertEquals(409, stale.getStatus().value());

        ConstructionOrderResponse modified = constructionService.modifyOrder(order.id(),
                new ConstructionOrderModifyRequest("mod-ok", 1, List.of(seg(1, 0, 200))));
        assertEquals(2, modified.version());
        assertEquals(200, modified.segments().get(0).endMeters());

        ApiException staleAgain = assertThrows(ApiException.class,
                () -> constructionService.modifyOrder(order.id(),
                        new ConstructionOrderModifyRequest("mod-s2", 1, List.of(seg(1, 0, 300)))));
        assertEquals("CONSTRUCTION_VERSION_CONFLICT", staleAgain.getCode());

        // 生效也带版本：旧版409
        ApiException staleActivate = assertThrows(ApiException.class,
                () -> constructionService.activateOrder(order.id(),
                        new ConstructionActivateRequest("act-s", 1, T0)));
        assertEquals("CONSTRUCTION_VERSION_CONFLICT", staleActivate.getCode());
    }

    /** 幂等：同键同参重放首次结果，同键异参409，失败不占键。 */
    @Test
    void idempotencyReplayConflictAndFailureNotOccupyingKey() {
        register("RW-C7", 1000);
        ConstructionOrderCreateRequest valid = new ConstructionOrderCreateRequest("idem-c1", "RW-C7",
                List.of(seg(1, 0, 100)));
        ConstructionOrderResponse first = constructionService.createOrder(valid);
        ConstructionOrderResponse replay = constructionService.createOrder(valid);
        assertEquals(first.id(), replay.id());
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM construction_order WHERE request_id = 'idem-c1'", Integer.class);
        assertEquals(1, orderCount);

        ApiException conflict = assertThrows(ApiException.class,
                () -> createOrder("idem-c1", "RW-C7", seg(1, 0, 200)));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 失败不占键：先以重叠段失败，再同键以合法参数成功
        ApiException failed = assertThrows(ApiException.class,
                () -> createOrder("idem-c2", "RW-C7", seg(1, 0, 500), seg(2, 400, 600)));
        assertEquals("CONSTRUCTION_SEGMENT_OVERLAP", failed.getCode());
        ConstructionOrderResponse afterFailure = createOrder("idem-c2", "RW-C7", seg(1, 0, 500));
        assertEquals("PENDING", afterFailure.status());

        // 验收幂等：同键同参重放
        activate(afterFailure.id(), 1);
        AcceptanceRequest acceptReq = new AcceptanceRequest("idem-a1", "inspector-A", "CLR-A",
                T0.plus(Duration.ofMinutes(30)));
        AcceptanceResponse a1 = constructionService.submitAcceptance(afterFailure.id(), 1, acceptReq);
        AcceptanceResponse a2 = constructionService.submitAcceptance(afterFailure.id(), 1, acceptReq);
        assertEquals(a1.credentialId(), a2.credentialId());
        Integer credCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM acceptance_credential WHERE request_id = 'idem-a1'", Integer.class);
        assertEquals(1, credCount);
    }

    /** 生效原子抢占：已开始计划中止、未开始取消并保留证据，不波及其他跑道。 */
    @Test
    void activationPreemptsRoutinePlansAtomically() {
        register("RW-C8", 1000);
        PlanResponse startedPlan = routineService.publishPlan(new PlanPublishRequest("p-s", "RW-C8",
                T0, T0.plus(Duration.ofHours(2)), 60, 1));
        routineService.startPlan(startedPlan.planId(),
                new PlanStartRequest("s-s", 1, T0.plus(Duration.ofMinutes(10))));
        PlanResponse approvedPlan = routineService.publishPlan(new PlanPublishRequest("p-a", "RW-C8",
                T0.plus(Duration.ofHours(3)), T0.plus(Duration.ofHours(5)), 60, 1));
        PlanResponse otherRunwayPlan = routineService.publishPlan(new PlanPublishRequest("p-o", "RW-C8B",
                T0, T0.plus(Duration.ofHours(2)), 60, 1));

        ConstructionOrderResponse order = createOrder("co-p", "RW-C8", seg(1, 0, 500));
        activate(order.id(), 1);

        PlanResponse aborted = routineService.getPlan(startedPlan.planId());
        assertEquals("ABORTED", aborted.status());
        assertNotNull(aborted.preemptedAt());
        assertTrue(aborted.evidence().contains("\"conflict\":false"), "中止计划保留发布冻结证据");

        PlanResponse cancelled = routineService.getPlan(approvedPlan.planId());
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.preemptedAt());

        PlanResponse untouched = routineService.getPlan(otherRunwayPlan.planId());
        assertEquals("APPROVED", untouched.status(), "其他跑道计划不受影响");
        assertNull(untouched.preemptedAt());

        // 闭锁期间整跑道新使用被阻断：施工跑道上的新例行计划不得开始
        PlanResponse newPlanOnLockedRunway = routineService.publishPlan(new PlanPublishRequest(
                "p-n", "RW-C8", T0.plus(Duration.ofHours(6)), T0.plus(Duration.ofHours(8)), 60, 1));
        ApiException startBlocked = assertThrows(ApiException.class,
                () -> routineService.startPlan(newPlanOnLockedRunway.planId(),
                        new PlanStartRequest("s-b", 1, T0.plus(Duration.ofHours(6)))));
        assertEquals("CONSTRUCTION_LOCKDOWN_ACTIVE", startBlocked.getCode());

        // 未施工跑道不受影响：其他跑道计划可正常开始
        PlanResponse startedOther = routineService.startPlan(otherRunwayPlan.planId(),
                new PlanStartRequest("s-o", 1, T0.plus(Duration.ofMinutes(10))));
        assertEquals("STARTED", startedOther.status());
    }

    /** 整跑道恢复：所有施工段完成前恢复入口拒绝；分段完成本身不直接开放。 */
    @Test
    void reopenBlockedUntilAllConstructionSegmentsAccepted() {
        register("RW-C9", 1000);
        ReportResponse event = runwayService.report(
                new ReportRequest("ev-1", "RW-C9", EventType.FOD, T0));
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("as-1", new BigDecimal("0.50"),
                AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6))));

        ConstructionOrderResponse order = createOrder("co-r", "RW-C9", seg(1, 0, 400), seg(2, 600, 900));
        activate(order.id(), 1);

        ApiException blocked = assertThrows(ApiException.class, () -> runwayService.reopen(event.eventId(),
                new ReopenRequest("re-1", T0.plus(Duration.ofMinutes(40)))));
        assertEquals("CONSTRUCTION_LOCKDOWN_ACTIVE", blocked.getCode());
        assertEquals(422, blocked.getStatus().value());

        // 分段完成本身不直接开放：段1完成后仍有段2闭锁，恢复仍拒绝
        accept(order.id(), 1, "ac-r1a", "inspector-A");
        accept(order.id(), 1, "ac-r1b", "inspector-B");
        ApiException stillBlocked = assertThrows(ApiException.class, () -> runwayService.reopen(
                event.eventId(), new ReopenRequest("re-2", T0.plus(Duration.ofMinutes(45)))));
        assertEquals("CONSTRUCTION_LOCKDOWN_ACTIVE", stillBlocked.getCode());

        // 全部段验收完成且原紧急事件条件满足：恢复与通告同事务完成
        accept(order.id(), 2, "ac-r2a", "inspector-A");
        accept(order.id(), 2, "ac-r2b", "inspector-B");
        ReopenResponse reopened = runwayService.reopen(event.eventId(),
                new ReopenRequest("re-3", T0.plus(Duration.ofMinutes(50))));
        assertEquals("REOPENED", reopened.status());
        assertTrue(reopened.notamNo().startsWith("NTM-R-"));
        Integer reopenNotams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notam_record WHERE event_id = ? AND action = 'REOPEN'",
                Integer.class, event.eventId());
        assertEquals(1, reopenNotams);
    }

    /** 已结束施工的迟到凭据只读留痕：不改变状态、不复活已中止巡检。 */
    @Test
    void lateCredentialRecordedOnlyAfterCompletion() {
        register("RW-C10", 1000);
        PlanResponse plan = routineService.publishPlan(new PlanPublishRequest("p-late", "RW-C10",
                T0, T0.plus(Duration.ofHours(2)), 60, 1));
        routineService.startPlan(plan.planId(),
                new PlanStartRequest("s-late", 1, T0.plus(Duration.ofMinutes(10))));

        ConstructionOrderResponse order = createOrder("co-late", "RW-C10", seg(1, 0, 500));
        activate(order.id(), 1);
        assertEquals("ABORTED", routineService.getPlan(plan.planId()).status());

        accept(order.id(), 1, "ac-l1", "inspector-A");
        AcceptanceResponse done = accept(order.id(), 1, "ac-l2", "inspector-B");
        assertEquals("COMPLETED", done.orderStatus());

        AcceptanceResponse late = constructionService.submitAcceptance(order.id(), 1,
                new AcceptanceRequest("ac-l3", "inspector-C", "CLR-C", T0.plus(Duration.ofHours(3))));
        assertTrue(late.late());
        assertEquals("COMPLETED", late.orderStatus());
        assertEquals(done.orderVersion(), late.orderVersion(), "迟到凭据不变更单据版本");

        Integer lateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM acceptance_credential WHERE order_id = ? AND late = 1",
                Integer.class, order.id());
        assertEquals(1, lateCount);
        assertEquals("ABORTED", routineService.getPlan(plan.planId()).status(), "迟到凭据不复活已中止巡检");
    }

    /** 同一验收人重复提交同段凭据拒绝（409）。 */
    @Test
    void duplicateInspectorRejected() {
        register("RW-C11", 1000);
        ConstructionOrderResponse order = createOrder("co-dup-i", "RW-C11", seg(1, 0, 100));
        activate(order.id(), 1);
        accept(order.id(), 1, "ac-d1", "inspector-A");
        ApiException dup = assertThrows(ApiException.class,
                () -> accept(order.id(), 1, "ac-d2", "inspector-A"));
        assertEquals("ACCEPTANCE_INSPECTOR_DUPLICATE", dup.getCode());
        assertEquals(409, dup.getStatus().value());
        assertEquals("LOCKED", constructionService.getOrder(order.id()).segments().get(0).status());
    }

    /** 未受控跑道：不受理施工单；原紧急关闭流程行为不变。 */
    @Test
    void uncontrolledRunwayKeepsOriginalBehavior() {
        ApiException notControlled = assertThrows(ApiException.class,
                () -> createOrder("co-nc", "RW-UNCTRL", seg(1, 0, 100)));
        assertEquals("RUNWAY_NOT_CONTROLLED", notControlled.getCode());

        ApiException areasNotFound = assertThrows(ApiException.class,
                () -> constructionService.getAvailableAreas("RW-UNCTRL"));
        assertEquals("CONTROLLED_RUNWAY_NOT_FOUND", areasNotFound.getCode());

        // 原紧急关闭→巡检→评估→恢复流程在未受控跑道不受影响
        ReportResponse event = runwayService.report(
                new ReportRequest("ev-nc", "RW-UNCTRL", EventType.FOD, T0));
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("as-nc", new BigDecimal("0.60"),
                AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6))));
        ReopenResponse reopened = runwayService.reopen(event.eventId(),
                new ReopenRequest("re-nc", T0.plus(Duration.ofMinutes(9))));
        assertEquals("REOPENED", reopened.status());
    }

    /** 存在活动施工后不许缩改跑道长度；扩建允许；无活动施工可缩改。 */
    @Test
    void shrinkBlockedWhileActiveConstruction() {
        ControlledRunwayResponse runway = register("RW-C12", 1000);
        ConstructionOrderResponse order = createOrder("co-rs", "RW-C12", seg(1, 0, 900));
        activate(order.id(), 1);

        ApiException shrink = assertThrows(ApiException.class, () -> constructionService.resizeRunway(
                "RW-C12", new RunwayResizeRequest("rs-1", runway.version(), 500)));
        assertEquals("RUNWAY_SHRINK_BLOCKED", shrink.getCode());
        assertEquals(422, shrink.getStatus().value());

        ControlledRunwayResponse expanded = constructionService.resizeRunway("RW-C12",
                new RunwayResizeRequest("rs-2", runway.version(), 2000));
        assertEquals(2000, expanded.lengthMeters());
        assertEquals(2, expanded.version());

        // 施工完成后无活动施工：允许缩改
        accept(order.id(), 1, "ac-rs1", "inspector-A");
        accept(order.id(), 1, "ac-rs2", "inspector-B");
        ControlledRunwayResponse shrunk = constructionService.resizeRunway("RW-C12",
                new RunwayResizeRequest("rs-3", 2, 1500));
        assertEquals(1500, shrunk.lengthMeters());

        // 未生效施工单不阻断缩改，但生效时区间越界拒绝
        register("RW-C12B", 1000);
        ConstructionOrderResponse pending = createOrder("co-rs2", "RW-C12B", seg(1, 0, 900));
        constructionService.resizeRunway("RW-C12B", new RunwayResizeRequest("rs-4", 1, 800));
        ApiException activateInvalid = assertThrows(ApiException.class,
                () -> activate(pending.id(), 1));
        assertEquals("CONSTRUCTION_SEGMENT_RANGE_INVALID", activateInvalid.getCode());
    }

    /** 全跑道闭锁时可用区间为空列表。 */
    @Test
    void availableAreasEmptyWhenFullyLocked() {
        register("RW-C13", 1000);
        ConstructionOrderResponse order = createOrder("co-full", "RW-C13", seg(1, 0, 1000));
        activate(order.id(), 1);
        AvailableAreasResponse areas = constructionService.getAvailableAreas("RW-C13");
        assertTrue(areas.available().isEmpty(), "空可用返回空列表");
        assertEquals(0, areas.availableTotalMeters());
        assertEquals(1000, areas.unavailableTotalMeters());
    }

    /** 并发：最后验收与整跑道恢复竞争，按事务次序判定，不留带活动闭锁的新开放。 */
    @Test
    void concurrentLastAcceptanceAndReopenAreSerialized() throws Exception {
        register("RW-C14", 1000);
        ReportResponse event = runwayService.report(
                new ReportRequest("ev-cc", "RW-C14", EventType.FOD, T0));
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("as-cc", new BigDecimal("0.50"),
                AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6))));
        ConstructionOrderResponse order = createOrder("co-cc", "RW-C14", seg(1, 0, 1000));
        activate(order.id(), 1);
        accept(order.id(), 1, "ac-cc1", "inspector-A");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> reopenOutcome = new AtomicReference<>();
        AtomicReference<Object> acceptOutcome = new AtomicReference<>();
        Future<?> reopenFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                reopenOutcome.set(runwayService.reopen(event.eventId(),
                        new ReopenRequest("re-cc", T0.plus(Duration.ofMinutes(40)))));
            } catch (ApiException e) {
                reopenOutcome.set(e);
            }
            return null;
        });
        Future<?> acceptFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                acceptOutcome.set(accept(order.id(), 1, "ac-cc2", "inspector-B"));
            } catch (ApiException e) {
                acceptOutcome.set(e);
            }
            return null;
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        reopenFuture.get(15, TimeUnit.SECONDS);
        acceptFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertInstanceOf(AcceptanceResponse.class, acceptOutcome.get(), "最后验收必须成功");
        Object reopen = reopenOutcome.get();
        if (reopen instanceof ReopenResponse) {
            // 验收先提交：恢复成功时不得残留活动闭锁
            Integer locked = jdbc.queryForObject("SELECT COUNT(*) FROM construction_segment s"
                            + " JOIN construction_order o ON o.id = s.order_id"
                            + " WHERE o.runway_id = 'RW-C14' AND o.status = 'ACTIVE' AND s.status = 'LOCKED'",
                    Integer.class);
            assertEquals(0, locked, "恢复开放后不得残留活动闭锁段");
            assertEquals("REOPENED", runwayService.getDetail(event.eventId()).status());
        } else {
            ApiException ex = assertInstanceOf(ApiException.class, reopen);
            assertEquals("CONSTRUCTION_LOCKDOWN_ACTIVE", ex.getCode(), "恢复先于验收：须被闭锁阻断");
            // 验收随后完成：施工单已结束，事件仍未恢复（可再次走恢复入口）
            assertEquals("COMPLETED", constructionService.getOrder(order.id()).status());
            ReopenResponse retry = runwayService.reopen(event.eventId(),
                    new ReopenRequest("re-cc-2", T0.plus(Duration.ofMinutes(50))));
            assertEquals("REOPENED", retry.status());
        }
    }

    /** 并发：施工生效与整跑道恢复竞争，按事务次序判定。 */
    @Test
    void concurrentActivateAndReopenAreSerialized() throws Exception {
        register("RW-C15", 1000);
        ReportResponse event = runwayService.report(
                new ReportRequest("ev-ca", "RW-C15", EventType.FOD, T0));
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("as-ca", new BigDecimal("0.50"),
                AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6))));
        ConstructionOrderResponse order = createOrder("co-ca", "RW-C15", seg(1, 0, 1000));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> reopenOutcome = new AtomicReference<>();
        AtomicReference<Object> activateOutcome = new AtomicReference<>();
        Future<?> reopenFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                reopenOutcome.set(runwayService.reopen(event.eventId(),
                        new ReopenRequest("re-ca", T0.plus(Duration.ofMinutes(40)))));
            } catch (ApiException e) {
                reopenOutcome.set(e);
            }
            return null;
        });
        Future<?> activateFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                activateOutcome.set(activate(order.id(), 1));
            } catch (ApiException e) {
                activateOutcome.set(e);
            }
            return null;
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        reopenFuture.get(15, TimeUnit.SECONDS);
        activateFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertInstanceOf(ConstructionOrderResponse.class, activateOutcome.get(), "生效必须成功");
        Object reopen = reopenOutcome.get();
        if (reopen instanceof ApiException ex) {
            // 生效先提交：恢复被阻断，事件保持已评估，施工闭锁生效
            assertEquals("CONSTRUCTION_LOCKDOWN_ACTIVE", ex.getCode());
            assertEquals("ASSESSED", runwayService.getDetail(event.eventId()).status());
            assertEquals("ACTIVE", constructionService.getOrder(order.id()).status());
        } else {
            // 恢复先提交：事件已开放，随后生效的施工仍闭锁（开放发生在闭锁之前，不违反不变量）
            assertInstanceOf(ReopenResponse.class, reopen);
            assertEquals("REOPENED", runwayService.getDetail(event.eventId()).status());
            assertEquals("ACTIVE", constructionService.getOrder(order.id()).status());
        }
    }
}
