package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跑道施工段闭锁与可用区域查询的 H2 集成测试：
 * 覆盖登记/生效/验收/恢复主流程、段校验失败分支、重叠并集补集、单段验收、
 * 长度缩改限制、版本乐观锁、幂等指纹，以及生效与恢复、最后验收与新施工的事务次序竞争。
 */
@SpringBootTest
class ConstructionServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

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
        jdbc.update("DELETE FROM clearance_credential");
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
                + " VALUES (1, ?, NULL, ?)", java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
    }

    private RunwayResponse register(String reqId, String runwayId, int lengthMeters) {
        return constructionService.registerRunway(new RegisterRunwayRequest(reqId, runwayId, lengthMeters));
    }

    private ConstructionOrderResponse createOrder(String reqId, String runwayId, SegmentInput... segments) {
        return constructionService.createOrder(
                new ConstructionOrderCreateRequest(reqId, runwayId, List.of(segments)));
    }

    private ConstructionOrderResponse activate(long orderId, String reqId, int expectedVersion) {
        return constructionService.activateOrder(orderId, new OrderActionRequest(reqId, expectedVersion));
    }

    private ClearanceResponse clear(long orderId, int segmentNo, String reqId, String acceptor) {
        return constructionService.submitClearance(orderId, segmentNo,
                new ClearanceRequest(reqId, acceptor, "CR-" + reqId, T0.plus(Duration.ofHours(1))));
    }

    /** 主流程：登记→施工单（端点相接合法）→生效→可用区间→双验收人逐段完成→整段恢复可用。 */
    @Test
    void mainFlowRegisterActivateClearAndComplete() {
        RunwayResponse runway = register("reg-1", "RWA", 1000);
        assertEquals(1, runway.version());
        assertEquals(1000, runway.lengthMeters());

        // 端点相接 [100,200) 与 [200,300) 合法
        ConstructionOrderResponse order = createOrder("co-1", "RWA",
                new SegmentInput(1, 100, 200), new SegmentInput(2, 200, 300));
        assertEquals("DRAFT", order.status());
        assertEquals(1, order.version());
        assertEquals(2, order.segments().size());
        assertEquals("PENDING", order.segments().get(0).status());

        // 未生效不产生闭锁：全跑道可用
        UsableSegmentsResponse beforeActivate = constructionService.getUsableSegments("RWA");
        assertEquals(1, beforeActivate.usableSegments().size());
        assertEquals(1000, beforeActivate.totalUsableMeters());

        ConstructionOrderResponse effective = activate(order.orderId(), "act-1", 1);
        assertEquals("EFFECTIVE", effective.status());
        assertEquals(2, effective.version());
        assertNotNull(effective.activatedAt());
        assertEquals("LOCKED", effective.segments().get(0).status());

        // 生效后可用区间 = [0,100) + [300,1000)
        UsableSegmentsResponse locked = constructionService.getUsableSegments("RWA");
        assertEquals(List.of(new UsableSegment(0, 100), new UsableSegment(300, 1000)),
                locked.usableSegments());
        assertEquals(800, locked.totalUsableMeters());

        // 段1：两名不同验收人
        ClearanceResponse first = clear(order.orderId(), 1, "cl-1a", "acceptor-A");
        assertFalse(first.late());
        assertEquals("LOCKED", first.segmentStatus());
        assertEquals(1, first.distinctAcceptors());
        ClearanceResponse second = clear(order.orderId(), 1, "cl-1b", "acceptor-B");
        assertEquals("CLEARED", second.segmentStatus());
        assertEquals(2, second.distinctAcceptors());
        assertEquals("EFFECTIVE", second.orderStatus());

        // 段1闭锁消除后：可用 [0,200) + [300,1000)
        UsableSegmentsResponse partial = constructionService.getUsableSegments("RWA");
        assertEquals(List.of(new UsableSegment(0, 200), new UsableSegment(300, 1000)),
                partial.usableSegments());

        // 段2完成 → 单结束，版本+1
        clear(order.orderId(), 2, "cl-2a", "acceptor-C");
        ClearanceResponse done = clear(order.orderId(), 2, "cl-2b", "acceptor-D");
        assertEquals("COMPLETED", done.orderStatus());
        ConstructionOrderResponse completed = constructionService.getOrder(order.orderId());
        assertEquals("COMPLETED", completed.status());
        assertEquals(3, completed.version());
        assertNotNull(completed.completedAt());

        UsableSegmentsResponse all = constructionService.getUsableSegments("RWA");
        assertEquals(List.of(new UsableSegment(0, 1000)), all.usableSegments());
        assertEquals(1000, all.totalUsableMeters());
    }

    /** 题干场景：1000米跑道两施工区间重叠 [100,400) 与 [300,500)，只验收一段后的可用区间。 */
    @Test
    void overlappingOrdersUnionAndSingleSegmentAcceptance() {
        register("reg-ov", "RWO", 1000);
        ConstructionOrderResponse order1 = createOrder("ov-1", "RWO", new SegmentInput(1, 100, 400));
        ConstructionOrderResponse order2 = createOrder("ov-2", "RWO", new SegmentInput(1, 300, 500));
        activate(order1.orderId(), "ov-a1", 1);
        activate(order2.orderId(), "ov-a2", 1);

        // 并集 [100,500)，补集 [0,100) + [500,1000)，重叠不重复计长度
        UsableSegmentsResponse both = constructionService.getUsableSegments("RWO");
        assertEquals(List.of(new UsableSegment(0, 100), new UsableSegment(500, 1000)),
                both.usableSegments());
        assertEquals(600, both.totalUsableMeters());

        // 只验收单1的段：重叠区 [300,400) 仍被单2占用，可用 [0,300) + [500,1000)
        clear(order1.orderId(), 1, "ov-c1", "acc-A");
        ClearanceResponse cleared = clear(order1.orderId(), 1, "ov-c2", "acc-B");
        assertEquals("CLEARED", cleared.segmentStatus());
        assertEquals("COMPLETED", cleared.orderStatus());

        UsableSegmentsResponse after = constructionService.getUsableSegments("RWO");
        assertEquals(List.of(new UsableSegment(0, 300), new UsableSegment(500, 1000)),
                after.usableSegments());
        assertEquals(800, after.totalUsableMeters());
        assertEquals("EFFECTIVE", constructionService.getOrder(order2.orderId()).status());
    }

    /** 全覆盖施工：可用区间为空列表，总可用0。 */
    @Test
    void fullCoverageReturnsEmptyUsableList() {
        register("reg-full", "RWF", 1000);
        ConstructionOrderResponse order = createOrder("full-1", "RWF", new SegmentInput(1, 0, 1000));
        activate(order.orderId(), "full-a", 1);
        UsableSegmentsResponse usable = constructionService.getUsableSegments("RWF");
        assertTrue(usable.usableSegments().isEmpty());
        assertEquals(0, usable.totalUsableMeters());
    }

    /** 段校验失败分支：未受控跑道404、重叠422、段号重复422、超长422、非法区间400、段数400。 */
    @Test
    void segmentValidationFailures() {
        // 未受控跑道：404
        ApiException uncontrolled = assertThrows(ApiException.class,
                () -> createOrder("v-0", "RW-NONE", new SegmentInput(1, 0, 100)));
        assertEquals(404, uncontrolled.getStatus().value());
        assertEquals("RUNWAY_NOT_CONTROLLED", uncontrolled.getCode());

        register("reg-v", "RWV", 1000);
        // 单内重叠：422
        ApiException overlap = assertThrows(ApiException.class, () -> createOrder("v-1", "RWV",
                new SegmentInput(1, 100, 300), new SegmentInput(2, 200, 400)));
        assertEquals(422, overlap.getStatus().value());
        assertEquals("SEGMENT_OVERLAP", overlap.getCode());

        // 段号重复（区间不重叠也拒绝）：422
        ApiException dupNo = assertThrows(ApiException.class, () -> createOrder("v-2", "RWV",
                new SegmentInput(1, 100, 200), new SegmentInput(1, 300, 400)));
        assertEquals("DUPLICATE_SEGMENT_NO", dupNo.getCode());

        // 终点超跑道长度：422
        ApiException exceed = assertThrows(ApiException.class,
                () -> createOrder("v-3", "RWV", new SegmentInput(1, 900, 1001)));
        assertEquals("SEGMENT_EXCEEDS_RUNWAY_LENGTH", exceed.getCode());

        // start>=end：400
        ApiException badInterval = assertThrows(ApiException.class,
                () -> createOrder("v-4", "RWV", new SegmentInput(1, 300, 300)));
        assertEquals(400, badInterval.getStatus().value());
        assertEquals("INVALID_SEGMENT_INTERVAL", badInterval.getCode());

        // 段数：0个与11个均400
        assertEquals(400, assertThrows(ApiException.class,
                () -> constructionService.createOrder(new ConstructionOrderCreateRequest("v-5", "RWV",
                        List.of()))).getStatus().value());
        List<SegmentInput> eleven = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(new SegmentInput(i + 1, i * 10, i * 10 + 5));
        }
        assertEquals(400, assertThrows(ApiException.class,
                () -> constructionService.createOrder(new ConstructionOrderCreateRequest("v-6", "RWV",
                        eleven))).getStatus().value());
    }

    /** 单段验收：同一验收人重复提交409；段完成后迟到凭据只读留痕，不改变状态、版本不变。 */
    @Test
    void sameAcceptorRejectedAndLateCredentialReadOnly() {
        register("reg-late", "RWL", 1000);
        ConstructionOrderResponse order = createOrder("late-1", "RWL", new SegmentInput(1, 100, 200));
        activate(order.orderId(), "late-a", 1);

        clear(order.orderId(), 1, "late-c1", "acc-A");
        // 同一验收人再次提交（新requestId）：409，不计入第二人
        ApiException dup = assertThrows(ApiException.class,
                () -> clear(order.orderId(), 1, "late-c2", "acc-A"));
        assertEquals(409, dup.getStatus().value());
        assertEquals("DUPLICATE_ACCEPTOR", dup.getCode());

        ClearanceResponse done = clear(order.orderId(), 1, "late-c3", "acc-B");
        assertEquals("CLEARED", done.segmentStatus());
        assertEquals("COMPLETED", done.orderStatus());
        int versionAfterComplete = done.orderVersion();

        // 已结束施工后的迟到凭据：只读留痕，状态与版本不变
        ClearanceResponse late = clear(order.orderId(), 1, "late-c4", "acc-C");
        assertTrue(late.late());
        assertEquals("CLEARED", late.segmentStatus());
        assertEquals("COMPLETED", late.orderStatus());
        assertEquals(versionAfterComplete, late.orderVersion());
        Integer lateRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clearance_credential WHERE order_id = ? AND late = 1",
                Integer.class, order.orderId());
        assertEquals(1, lateRows);
        // 迟到留痕不计入验收人数
        assertEquals(2, constructionService.getOrder(order.orderId()).segments().get(0).clearanceCount());
    }

    /** 未生效/已撤回单不受理凭据：422。 */
    @Test
    void clearanceOnDraftOrWithdrawnRejected() {
        register("reg-df", "RWD", 1000);
        ConstructionOrderResponse order = createOrder("df-1", "RWD", new SegmentInput(1, 100, 200));
        ApiException draft = assertThrows(ApiException.class,
                () -> clear(order.orderId(), 1, "df-c1", "acc-A"));
        assertEquals("ORDER_NOT_EFFECTIVE", draft.getCode());

        constructionService.withdrawOrder(order.orderId(), new OrderActionRequest("df-w", 1));
        ApiException withdrawn = assertThrows(ApiException.class,
                () -> clear(order.orderId(), 1, "df-c2", "acc-A"));
        assertEquals("ORDER_NOT_EFFECTIVE", withdrawn.getCode());
    }

    /** 长度修改：存在活动施工不许缩改（422），加长允许；旧版本409。 */
    @Test
    void shrinkBlockedWhileActiveConstruction() {
        register("reg-sh", "RWS", 1000);
        ConstructionOrderResponse order = createOrder("sh-1", "RWS", new SegmentInput(1, 100, 200));
        activate(order.orderId(), "sh-a", 1);

        ApiException shrink = assertThrows(ApiException.class, () -> constructionService
                .updateRunwayLength("RWS", new UpdateRunwayLengthRequest("sh-u1", 1, 500)));
        assertEquals(422, shrink.getStatus().value());
        assertEquals("RUNWAY_SHRINK_BLOCKED", shrink.getCode());

        // 加长允许，版本+1
        RunwayResponse grown = constructionService.updateRunwayLength("RWS",
                new UpdateRunwayLengthRequest("sh-u2", 1, 1200));
        assertEquals(1200, grown.lengthMeters());
        assertEquals(2, grown.version());

        // 旧版本：409
        ApiException stale = assertThrows(ApiException.class, () -> constructionService
                .updateRunwayLength("RWS", new UpdateRunwayLengthRequest("sh-u3", 1, 1300)));
        assertEquals(409, stale.getStatus().value());
        assertEquals("RUNWAY_VERSION_CONFLICT", stale.getCode());
    }

    /** 修改与撤回规则：未生效可改可撤（版本+1）；已生效不得缩段/删除；旧版409。 */
    @Test
    void updateAndWithdrawRules() {
        register("reg-mw", "RWM", 1000);
        ConstructionOrderResponse order = createOrder("mw-1", "RWM", new SegmentInput(1, 100, 200));

        // 未生效修改：版本+1
        ConstructionOrderResponse updated = constructionService.updateOrder(order.orderId(),
                new ConstructionOrderUpdateRequest("mw-u1", 1, List.of(new SegmentInput(1, 100, 250))));
        assertEquals(2, updated.version());
        assertEquals(250, updated.segments().get(0).endMeter());

        // 旧版本修改：409
        ApiException stale = assertThrows(ApiException.class, () -> constructionService.updateOrder(
                order.orderId(),
                new ConstructionOrderUpdateRequest("mw-u2", 1, List.of(new SegmentInput(1, 100, 260)))));
        assertEquals(409, stale.getStatus().value());
        assertEquals("ORDER_VERSION_CONFLICT", stale.getCode());

        // 生效后：不得修改缩段、不得撤回删除；生效旧版409
        ApiException staleActivate = assertThrows(ApiException.class,
                () -> activate(order.orderId(), "mw-a0", 1));
        assertEquals("ORDER_VERSION_CONFLICT", staleActivate.getCode());
        activate(order.orderId(), "mw-a1", 2);
        ApiException modifyEffective = assertThrows(ApiException.class, () -> constructionService
                .updateOrder(order.orderId(), new ConstructionOrderUpdateRequest("mw-u3", 3,
                        List.of(new SegmentInput(1, 100, 200)))));
        assertEquals("ORDER_NOT_MODIFIABLE", modifyEffective.getCode());
        ApiException withdrawEffective = assertThrows(ApiException.class, () -> constructionService
                .withdrawOrder(order.orderId(), new OrderActionRequest("mw-w1", 3)));
        assertEquals("ORDER_NOT_WITHDRAWABLE", withdrawEffective.getCode());

        // 未生效单可撤回
        ConstructionOrderResponse draft = createOrder("mw-2", "RWM", new SegmentInput(1, 500, 600));
        ConstructionOrderResponse withdrawn = constructionService.withdrawOrder(draft.orderId(),
                new OrderActionRequest("mw-w2", 1));
        assertEquals("WITHDRAWN", withdrawn.status());
        assertEquals(2, withdrawn.version());
        assertNotNull(withdrawn.withdrawnAt());
        // 撤回单不产生闭锁：可用区间仅由生效单 [100,250) 决定
        UsableSegmentsResponse usable = constructionService.getUsableSegments("RWM");
        assertEquals(List.of(new UsableSegment(0, 100), new UsableSegment(250, 1000)),
                usable.usableSegments());
        assertEquals(850, usable.totalUsableMeters());
    }

    /** 生效原子抢占：本跑道已开始计划中止（保留检查项）、未开始取消并保留证据；其他跑道不受影响。 */
    @Test
    void activationPreemptsRoutinePlansAtomically() {
        register("reg-pp", "RWP", 2000);
        // 本跑道：未开始计划 + 已开始计划（含2个检查项）
        PlanResponse notStarted = routineService.publishPlan(
                new PlanPublishRequest("pp-1", "RWP", T0, T0.plus(Duration.ofHours(2)), 60, 1));
        PlanResponse started = routineService.publishPlan(new PlanPublishRequest("pp-2", "RWP",
                T0.plus(Duration.ofHours(3)), T0.plus(Duration.ofHours(5)), 60, 1));
        routineService.startPlan(started.planId(),
                new PlanStartRequest("pp-2s", 1, T0.plus(Duration.ofHours(3))));
        routineService.reportChecks(started.planId(), new PlanCheckReportRequest("pp-2c", 2,
                T0.plus(Duration.ofHours(3).plusMinutes(30)),
                List.of(new CheckItemInput("A", "项A", true, T0.plus(Duration.ofHours(3).plusMinutes(20))))));
        // 其他跑道：未开始计划
        PlanResponse other = routineService.publishPlan(
                new PlanPublishRequest("pp-3", "RWQ", T0, T0.plus(Duration.ofHours(2)), 60, 1));

        ConstructionOrderResponse order = createOrder("pp-o", "RWP", new SegmentInput(1, 0, 500));
        ConstructionOrderResponse effective = activate(order.orderId(), "pp-a", 1);

        assertEquals("CANCELLED", routineService.getPlan(notStarted.planId()).status());
        PlanResponse aborted = routineService.getPlan(started.planId());
        assertEquals("ABORTED", aborted.status());
        assertNotNull(aborted.preemptedAt());
        // 已完成检查项保留
        Integer kept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routine_check_item WHERE plan_id = ? AND late = 0",
                Integer.class, started.planId());
        assertEquals(1, kept);
        // 抢占证据冻结在施工单上
        assertNotNull(effective.evidence());
        assertTrue(effective.evidence().contains("\"abortedPlanIds\":[" + started.planId() + "]"));
        assertTrue(effective.evidence().contains("\"cancelledPlanIds\":[" + notStarted.planId() + "]"));
        // 其他跑道不受影响
        assertEquals("APPROVED", routineService.getPlan(other.planId()).status());

        // 被中止计划的迟到完成回报仅登记，不复活
        PlanCompletionResponse lateDone = routineService.completePlan(started.planId(),
                new PlanCompletionRequest("pp-late", aborted.version(), T0.plus(Duration.ofHours(4))));
        assertTrue(lateDone.late());
        assertEquals("ABORTED", routineService.getPlan(started.planId()).status());

        // 施工生效阻断整跑道新使用：新计划开始被拒绝
        PlanResponse fresh = routineService.publishPlan(new PlanPublishRequest("pp-4", "RWP",
                T0.plus(Duration.ofHours(6)), T0.plus(Duration.ofHours(8)), 60, 1));
        ApiException blocked = assertThrows(ApiException.class, () -> routineService.startPlan(fresh.planId(),
                new PlanStartRequest("pp-4s", 1, T0.plus(Duration.ofHours(6)))));
        assertEquals(422, blocked.getStatus().value());
        assertEquals("CONSTRUCTION_LOCK_ACTIVE", blocked.getCode());
    }

    /** 恢复入口：原评估条件满足但存在活动施工闭锁时拒绝；全部段验收完成后原流程恢复并发通告。 */
    @Test
    void reopenBlockedUntilAllSegmentsCleared() {
        register("reg-ro", "RWR", 1000);
        ReportResponse event = runwayService.report(
                new ReportRequest("ro-1", "RWR", EventType.FOD, T0));
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("ro-a",
                new BigDecimal("0.60"), AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(10))));

        ConstructionOrderResponse order = createOrder("ro-o", "RWR",
                new SegmentInput(1, 100, 200), new SegmentInput(2, 500, 600));
        activate(order.orderId(), "ro-act", 1);

        // 原紧急事件条件已满足，但施工闭锁存在：恢复被拒绝
        ApiException blocked = assertThrows(ApiException.class, () -> runwayService.reopen(event.eventId(),
                new ReopenRequest("ro-r1", T0.plus(Duration.ofMinutes(20)))));
        assertEquals(422, blocked.getStatus().value());
        assertEquals("CONSTRUCTION_LOCK_ACTIVE", blocked.getCode());
        assertEquals("ASSESSED", runwayService.getDetail(event.eventId()).status());

        // 分段完成本身不直接开放：段1验收后仍闭锁（段2占用）
        clear(order.orderId(), 1, "ro-c1a", "acc-A");
        clear(order.orderId(), 1, "ro-c1b", "acc-B");
        ApiException stillBlocked = assertThrows(ApiException.class, () -> runwayService.reopen(
                event.eventId(), new ReopenRequest("ro-r2", T0.plus(Duration.ofMinutes(25)))));
        assertEquals("CONSTRUCTION_LOCK_ACTIVE", stillBlocked.getCode());

        // 全部段完成 → 原恢复入口放行，恢复通告照发
        clear(order.orderId(), 2, "ro-c2a", "acc-C");
        clear(order.orderId(), 2, "ro-c2b", "acc-D");
        ReopenResponse reopened = runwayService.reopen(event.eventId(),
                new ReopenRequest("ro-r3", T0.plus(Duration.ofMinutes(30))));
        assertEquals("REOPENED", reopened.status());
        assertTrue(reopened.notamNo().startsWith("NTM-R-"));
        assertEquals(1, runwayService.getNotams(event.eventId()).stream()
                .filter(n -> n.action().equals("REOPEN")).count());
    }

    /** 未受控跑道原行为不变：无施工概念，紧急关闭→评估→恢复全流程不受影响。 */
    @Test
    void uncontrolledRunwayBehaviorUnchanged() {
        ReportResponse event = runwayService.report(
                new ReportRequest("uc-1", "RW-UNCONTROLLED", EventType.FOD, T0));
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("uc-a",
                new BigDecimal("0.60"), AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(10))));
        ReopenResponse reopened = runwayService.reopen(event.eventId(),
                new ReopenRequest("uc-r", T0.plus(Duration.ofMinutes(15))));
        assertEquals("REOPENED", reopened.status());
        // 可用区域查询不适用于未受控跑道：404
        ApiException ex = assertThrows(ApiException.class,
                () -> constructionService.getUsableSegments("RW-UNCONTROLLED"));
        assertEquals(404, ex.getStatus().value());
    }

    /** 幂等：同键同参重放首次结果；异参409；失败不占键。 */
    @Test
    void idempotentReplayConflictAndFailureDoesNotOccupyKey() {
        register("reg-idem", "RWI", 1000);
        ConstructionOrderResponse first = createOrder("idem-o", "RWI", new SegmentInput(1, 100, 200));
        ConstructionOrderResponse replay = createOrder("idem-o", "RWI", new SegmentInput(1, 100, 200));
        assertEquals(first.orderId(), replay.orderId());
        assertEquals(1, orderCount());

        // 同键异参：409
        ApiException conflict = assertThrows(ApiException.class,
                () -> createOrder("idem-o", "RWI", new SegmentInput(1, 100, 300)));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 失败不占键：重叠失败后同键合法参数成功
        assertThrows(ApiException.class, () -> createOrder("idem-f", "RWI",
                new SegmentInput(1, 100, 300), new SegmentInput(2, 200, 400)));
        ConstructionOrderResponse afterFailure = createOrder("idem-f", "RWI",
                new SegmentInput(1, 700, 800));
        assertEquals("DRAFT", afterFailure.status());
        Integer failRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idem-f'", Integer.class);
        assertEquals(1, failRows, "失败不占键，仅成功后有一条幂等记录");

        // 登记跑道幂等：同键同参重放
        RunwayResponse r1 = register("idem-r", "RWI2", 800);
        RunwayResponse r2 = register("idem-r", "RWI2", 800);
        assertEquals(r1.version(), r2.version());
        ApiException dupRegister = assertThrows(ApiException.class,
                () -> constructionService.registerRunway(new RegisterRunwayRequest("idem-r2", "RWI2", 900)));
        assertEquals("RUNWAY_ALREADY_REGISTERED", dupRegister.getCode());
    }

    /** 施工生效与恢复竞争：按事务次序判定，不留带活动闭锁的新开放。 */
    @Test
    void concurrentActivateAndReopenSerialized() throws Exception {
        int pairs = 6;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        List<AtomicReference<String>> reopenOutcomes = new java.util.ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String runwayId = "RWC" + i;
            register("cc-reg-" + i, runwayId, 1000);
            ReportResponse event = runwayService.report(
                    new ReportRequest("cc-em-" + i, runwayId, EventType.FOD, T0));
            runwayService.startInspection(event.eventId(),
                    new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 2));
            runwayService.assess(event.eventId(), new AssessmentRequest("cc-as-" + i,
                    new BigDecimal("0.60"), AssessmentType.CLEAR_TO_REOPEN,
                    T0.plus(Duration.ofMinutes(10))));
            ConstructionOrderResponse order = createOrder("cc-o-" + i, runwayId,
                    new SegmentInput(1, 0, 500));
            AtomicReference<String> reopenOutcome = new AtomicReference<>("PENDING");
            reopenOutcomes.add(reopenOutcome);
            long eventId = event.eventId();
            long orderId = order.orderId();
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                constructionService.activateOrder(orderId, new OrderActionRequest("cc-act-" + orderId, 1));
                return null;
            }));
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    runwayService.reopen(eventId,
                            new ReopenRequest("cc-re-" + idx, T0.plus(Duration.ofMinutes(20))));
                    reopenOutcome.set("REOPENED");
                } catch (ApiException e) {
                    assertEquals("CONSTRUCTION_LOCK_ACTIVE", e.getCode(),
                            "恢复仅可因活动施工闭锁被拒绝");
                    reopenOutcome.set("BLOCKED");
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int i = 0; i < pairs; i++) {
            String runwayId = "RWC" + i;
            // 施工单必然生效成功
            String orderStatus = jdbc.queryForObject(
                    "SELECT status FROM construction_order WHERE runway_id = ?", String.class, runwayId);
            assertEquals("EFFECTIVE", orderStatus);
            String outcome = reopenOutcomes.get(i).get();
            String eventStatus = jdbc.queryForObject(
                    "SELECT status FROM runway_event WHERE runway_id = ?", String.class, runwayId);
            if ("REOPENED".equals(outcome)) {
                // 恢复先于生效提交：合法（恢复时不存在闭锁）
                assertEquals("REOPENED", eventStatus);
            } else {
                // 生效先提交：恢复被拒绝，事件不得处于已恢复（不留带活动闭锁的新开放）
                assertEquals("BLOCKED", outcome);
                assertNotEquals("REOPENED", eventStatus);
                Integer reopenNotams = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notam_record n JOIN runway_event e ON e.id = n.event_id"
                                + " WHERE e.runway_id = ? AND n.action = 'REOPEN'",
                        Integer.class, runwayId);
                assertEquals(0, reopenNotams, "被拒绝的恢复不得发布恢复通告");
            }
        }
    }

    /** 最后验收与新施工生效竞争：同一跑道行锁串行化，两者均完成且最终状态一致。 */
    @Test
    void concurrentLastAcceptanceAndNewActivationSerialized() throws Exception {
        register("reg-la", "RWLA", 1000);
        ConstructionOrderResponse orderA = createOrder("la-1", "RWLA", new SegmentInput(1, 0, 100));
        activate(orderA.orderId(), "la-a1", 1);
        clear(orderA.orderId(), 1, "la-c1", "acc-A");
        ConstructionOrderResponse orderB = createOrder("la-2", "RWLA", new SegmentInput(1, 200, 300));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> acceptance = pool.submit(() -> {
            ready.countDown();
            start.await();
            clear(orderA.orderId(), 1, "la-c2", "acc-B");
            return null;
        });
        Future<?> activation = pool.submit(() -> {
            ready.countDown();
            start.await();
            activate(orderB.orderId(), "la-a2", 1);
            return null;
        });
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        acceptance.get(20, TimeUnit.SECONDS);
        activation.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        // 单A完成、单B生效；可用区间仅为单B闭锁的补集
        assertEquals("COMPLETED", constructionService.getOrder(orderA.orderId()).status());
        assertEquals("EFFECTIVE", constructionService.getOrder(orderB.orderId()).status());
        UsableSegmentsResponse usable = constructionService.getUsableSegments("RWLA");
        assertEquals(List.of(new UsableSegment(0, 200), new UsableSegment(300, 1000)),
                usable.usableSegments());
        assertEquals(900, usable.totalUsableMeters());
    }

    /** 并发同 requestId 提交凭据：仅一个生效，另一个重放同参结果，验收人数不重复计。 */
    @Test
    void concurrentSameClearanceRequestIdempotent() throws Exception {
        register("reg-ci", "RWCI", 1000);
        ConstructionOrderResponse order = createOrder("ci-1", "RWCI", new SegmentInput(1, 0, 100));
        activate(order.orderId(), "ci-a", 1);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClearanceResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return clear(order.orderId(), 1, "ci-same", "acc-A");
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        long firstCredentialId = -1;
        for (Future<ClearanceResponse> f : futures) {
            ClearanceResponse response = f.get(20, TimeUnit.SECONDS);
            assertEquals(1, response.distinctAcceptors());
            if (firstCredentialId < 0) {
                firstCredentialId = response.credentialId();
            } else {
                assertEquals(firstCredentialId, response.credentialId(), "同键同参重放首次结果");
            }
        }
        pool.shutdown();
        Integer credentials = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clearance_credential WHERE order_id = ?", Integer.class,
                order.orderId());
        assertEquals(1, credentials, "并发同键仅登记一份凭据");
    }

    /** 并发不同验收人：行锁串行化后恰前两人正常验收，其余为迟到留痕，段完成、单结束。 */
    @Test
    void concurrentDifferentAcceptorsExactlyTwoEffective() throws Exception {
        register("reg-da", "RWDA", 1000);
        ConstructionOrderResponse order = createOrder("da-1", "RWDA", new SegmentInput(1, 0, 100));
        activate(order.orderId(), "da-a", 1);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClearanceResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return clear(order.orderId(), 1, "da-c-" + idx, "acc-" + idx);
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        int lateCount = 0;
        int normalCount = 0;
        for (Future<ClearanceResponse> f : futures) {
            ClearanceResponse response = f.get(20, TimeUnit.SECONDS);
            if (response.late()) {
                lateCount++;
            } else {
                normalCount++;
            }
        }
        pool.shutdown();
        assertEquals(2, normalCount, "恰两个不同验收人正常验收");
        assertEquals(2, lateCount, "段完成后其余为迟到留痕");
        assertEquals("CLEARED", jdbc.queryForObject(
                "SELECT status FROM construction_segment WHERE order_id = ?", String.class,
                order.orderId()));
        assertEquals("COMPLETED", constructionService.getOrder(order.orderId()).status());
        Integer normalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clearance_credential WHERE order_id = ? AND late = 0",
                Integer.class, order.orderId());
        assertEquals(2, normalRows);
    }

    private int orderCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM construction_order", Integer.class);
        return count == null ? 0 : count;
    }
}
