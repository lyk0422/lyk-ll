package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.starter.dto.GrainDtos.ApplyFumigationRequest;
import com.example.starter.dto.GrainDtos.ApproveFumigationRequest;
import com.example.starter.dto.GrainDtos.BeginAerationRequest;
import com.example.starter.dto.GrainDtos.FumigationView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.GasReadingView;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.dto.GrainDtos.StartFumigationRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸闭环主流程测试：申请-双审批-开始锁仓冻结版本-转散气占用风机-三点安全采样-双复核解除，
 * 以及审批人去重、版本乐观锁、窗口互斥、采样序号/时刻约束、越限打断、缺号不放行、迟到读数等失败分支。
 */
class FumigationFlowTest extends AbstractIntegrationTest {

    @Test
    void fullHappyFlowReleasesSiloAndFanAtomically() {
        var silo = newSilo("S-FUM-1");
        var fan = newFan("F-FUM-1");

        FumigationView applied = applyFumigation(silo.id(), "fum-apply-1");
        assertEquals(FumigationStatus.PENDING_APPROVAL, applied.status());
        assertEquals(1, applied.version());
        assertEquals(1, applied.planVersion());
        assertNotNull(applied.orderNo());
        assertTrue(applied.orderNo().startsWith("FUM-"));

        FumigationView approved = approveTwice(applied.id(), "fum-1");
        assertEquals(FumigationStatus.APPROVED, approved.status());
        assertEquals(3, approved.version());
        assertEquals(List.of("alice", "bob"), approved.approvers());

        FumigationView started = startFumigation(approved.id(), 3, "fum-start-1");
        assertEquals(FumigationStatus.FUMIGATING, started.status());
        assertEquals(4, started.version());
        assertEquals(1, started.frozenPlanVersion());
        assertEquals(U0, started.startedAt());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());

        FumigationView aerating = beginAeration(started.id(), 4, fan.id(), "fum-aer-1");
        assertEquals(FumigationStatus.AERATING, aerating.status());
        assertEquals(5, aerating.version());
        assertEquals(fan.id(), aerating.fanId());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());

        int releaseVersion = safeThreeReadings(aerating.id(), 5, "fum-1");
        assertEquals(8, releaseVersion);

        ReleaseView released = releaseFumigation(aerating.id(), 8, "fum-rel-1");
        assertEquals(1, released.firstSeqNo());
        assertEquals(3, released.lastSeqNo());
        assertEquals(8, released.checkedVersion());
        assertEquals(List.of("carol", "dave"), released.reviewers());

        FumigationView finalOrder = fumigationService.getOrder(aerating.id());
        assertEquals(FumigationStatus.RELEASED, finalOrder.status());
        assertEquals(9, finalOrder.version());
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
        assertEquals("IDLE", siloService.getFan(fan.id()).status());
    }

    @Test
    void sameApproverCannotApproveTwiceAndFailureDoesNotOccupyKey() {
        var silo = newSilo("S-FUM-2");
        var order = applyFumigation(silo.id(), "fum-apply-2");

        // 第一名审批人 alice 正常批准
        fumigationService.approve(order.id(), new ApproveFumigationRequest("fum-2-a1", 1, "alice"));

        // 同一审批人再次批准属于业务拒绝（事务回滚，requestId 不占键）
        ApiException again = assertThrows(ApiException.class, () -> fumigationService.approve(order.id(),
                new ApproveFumigationRequest("fum-2-dup", 2, "alice")));
        assertEquals("APPROVER_DUPLICATED", again.getCode());
        assertEquals(422, again.getStatus().value());
        assertEquals(1, countRows("fumigation_approval"));

        // 失败不占键：同一 requestId 改用于合法的第二名审批人 bob 应成功
        FumigationView approved = fumigationService.approve(order.id(),
                new ApproveFumigationRequest("fum-2-dup", 2, "bob"));
        assertEquals(FumigationStatus.APPROVED, approved.status());
        assertEquals(2, countRows("fumigation_approval"));
    }

    @Test
    void approveWithStaleVersionIsRejected() {
        var silo = newSilo("S-FUM-3");
        var order = applyFumigation(silo.id(), "fum-apply-3");
        fumigationService.approve(order.id(), new ApproveFumigationRequest("fum-3-a1", 1, "alice"));

        ApiException ex = assertThrows(ApiException.class, () -> fumigationService.approve(order.id(),
                new ApproveFumigationRequest("fum-3-stale", 1, "bob")));
        assertEquals(409, ex.getStatus().value());
        assertEquals("VERSION_CONFLICT", ex.getCode());
        assertEquals(1, countRows("fumigation_approval"));
    }

    @Test
    void startRejectedWhenVentilationExecuting() {
        var silo = newSilo("S-FUM-4");
        var fan = newFan("F-FUM-4");
        raiseAlarm(silo.id(), "fum-4-alarm");
        var ventJob = ventilationService.createJob(silo.id(), newJobRequest("fum-4-job", fan.id()));
        assertEquals(SiloStatus.VENTILATING, siloService.getSilo(silo.id()).status());

        var order = applyFumigation(silo.id(), "fum-apply-4");
        var approved = approveTwice(order.id(), "fum-4");

        ApiException ex = assertThrows(ApiException.class,
                () -> startFumigation(approved.id(), 3, "fum-start-4"));
        assertEquals(422, ex.getStatus().value());
        assertEquals("VENTILATION_EXECUTING", ex.getCode());
        assertEquals(FumigationStatus.APPROVED, fumigationService.getOrder(order.id()).status());
        assertEquals(SiloStatus.VENTILATING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void windowsAreHalfOpenAndAdjacentEndpointsCoexist() {
        var silo = newSilo("S-FUM-5");
        var fan = newFan("F-FUM-5");
        raiseAlarm(silo.id(), "fum-5-alarm");

        // 普通通风窗 02:00Z-03:00Z（本地墙钟），熏蒸窗同样覆盖该段
        ventilationService.createJob(silo.id(), createJobLike("fum-5-job", fan.id(),
                U0, U0.plusSeconds(3600)));

        // 重叠的熏蒸窗 02:30Z-03:30Z 拒绝
        ApiException overlap = assertThrows(ApiException.class, () -> fumigationService.apply(silo.id(),
                new ApplyFumigationRequest("fum-apply-5-over", U0.plusSeconds(1800),
                        U0.plusSeconds(5400), "A", new BigDecimal("5.000"))));
        assertEquals(422, overlap.getStatus().value());
        assertEquals("WINDOW_CONFLICT", overlap.getCode());

        // 端点相接 03:00Z-04:00Z 可共存
        FumigationView adjacent = fumigationService.apply(silo.id(),
                new ApplyFumigationRequest("fum-apply-5-adj", U0.plusSeconds(3600),
                        U0.plusSeconds(7200), "A", new BigDecimal("5.000")));
        assertEquals(1, adjacent.version());
    }

    @Test
    void beginAerationRejectsBusyFanAtomically() {
        var siloA = newSilo("S-FUM-6A");
        var siloB = newSilo("S-FUM-6B");
        var fan = newFan("F-FUM-6");

        var orderA = applyFumigation(siloA.id(), "fum-apply-6a");
        approveTwice(orderA.id(), "fum-6a");
        var startedA = startFumigation(orderA.id(), 3, "fum-start-6a");
        beginAeration(startedA.id(), 4, fan.id(), "fum-aer-6a");

        var orderB = applyFumigation(siloB.id(), "fum-apply-6b");
        approveTwice(orderB.id(), "fum-6b");
        var startedB = startFumigation(orderB.id(), 3, "fum-start-6b");

        ApiException ex = assertThrows(ApiException.class,
                () -> beginAeration(startedB.id(), 4, fan.id(), "fum-aer-6b"));
        assertEquals(409, ex.getStatus().value());
        assertEquals("FAN_BUSY", ex.getCode());
        assertEquals(FumigationStatus.FUMIGATING, fumigationService.getOrder(orderB.id()).status());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(siloB.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
    }

    @Test
    void readingSeqNoMustBeUniqueAndTimeStrictlyIncreasing() {
        var context = aeratingContext("S-FUM-7", "F-FUM-7", "fum-7");

        fumigationService.ingestGasReading(context.orderId(), new GasReadingRequest(
                "fum-7-g1", context.version(), 1, new BigDecimal("4.000"), U0.plusSeconds(3600)));

        ApiException dupSeq = assertThrows(ApiException.class, () -> fumigationService.ingestGasReading(
                context.orderId(), new GasReadingRequest("fum-7-gdup", context.version() + 1, 1,
                        new BigDecimal("4.000"), U0.plusSeconds(3700))));
        assertEquals(422, dupSeq.getStatus().value());
        assertEquals("SEQ_NO_DUPLICATED", dupSeq.getCode());

        ApiException notIncreasing = assertThrows(ApiException.class,
                () -> fumigationService.ingestGasReading(context.orderId(), new GasReadingRequest(
                        "fum-7-gback", context.version() + 1, 2, new BigDecimal("4.000"),
                        U0.plusSeconds(3500))));
        assertEquals(422, notIncreasing.getStatus().value());
        assertEquals("READING_TIME_NOT_INCREASING", notIncreasing.getCode());
        assertEquals(1, countRows("gas_reading"));
    }

    @Test
    void concentrationMustBeFiniteNonNegative() {
        var context = aeratingContext("S-FUM-8", "F-FUM-8", "fum-8");

        ApiException negative = assertThrows(ApiException.class, () -> fumigationService.ingestGasReading(
                context.orderId(), new GasReadingRequest("fum-8-neg", context.version(), 1,
                        new BigDecimal("-0.001"), U0.plusSeconds(3600))));
        assertEquals(400, negative.getStatus().value());
        assertEquals("INVALID_CONCENTRATION", negative.getCode());
        assertEquals(0, countRows("gas_reading"));
    }

    @Test
    void exceedanceBreaksConsecutiveSafeRun() {
        var context = aeratingContext("S-FUM-9", "F-FUM-9", "fum-9");
        // 1,2 安全；3 越限；4,5 安全但最新三点 3,4,5 中含越限点 → 不可解除
        ingest(context.orderId(), "fum-9-g1", context.version(), 1, "4.000", 3600);
        ingest(context.orderId(), "fum-9-g2", context.version() + 1, 2, "5.000", 4500);
        ingest(context.orderId(), "fum-9-g3", context.version() + 2, 3, "5.001", 5400);
        ingest(context.orderId(), "fum-9-g4", context.version() + 3, 4, "4.000", 6300);
        ingest(context.orderId(), "fum-9-g5", context.version() + 4, 5, "4.000", 7200);

        ApiException ex = assertThrows(ApiException.class,
                () -> fumigationService.release(context.orderId(),
                        new ReleaseFumigationRequest("fum-9-rel", context.version() + 5,
                                List.of("carol", "dave"))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("GAS_NOT_SAFE", ex.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(context.orderId()).status());
        assertEquals("LOCKED", siloService.getFan(context.fanId()).status());
    }

    @Test
    void missingSeqNoIsNotSafe() {
        var context = aeratingContext("S-FUM-10", "F-FUM-10", "fum-10");
        // 序号 1,2,4：最新三点缺 3 号，缺号不视为安全
        ingest(context.orderId(), "fum-10-g1", context.version(), 1, "4.000", 3600);
        ingest(context.orderId(), "fum-10-g2", context.version() + 1, 2, "4.000", 4500);
        ingest(context.orderId(), "fum-10-g4", context.version() + 2, 4, "4.000", 5400);

        ApiException ex = assertThrows(ApiException.class,
                () -> fumigationService.release(context.orderId(),
                        new ReleaseFumigationRequest("fum-10-rel", context.version() + 3,
                                List.of("carol", "dave"))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("GAS_SEQUENCE_INCOMPLETE", ex.getCode());
    }

    @Test
    void firstLastGapExactlyThirtyMinutesIsLegal() {
        var context = aeratingContext("S-FUM-11", "F-FUM-11", "fum-11");
        // 恰等阈值 5.000、恰等 30 分钟间隔均合法
        ingest(context.orderId(), "fum-11-g1", context.version(), 1, "5.000", 3600);
        ingest(context.orderId(), "fum-11-g2", context.version() + 1, 2, "5.000", 4500);
        ingest(context.orderId(), "fum-11-g3", context.version() + 2, 3, "5.000", 5400);

        ReleaseView released = fumigationService.release(context.orderId(),
                new ReleaseFumigationRequest("fum-11-rel", context.version() + 3,
                        List.of("carol", "dave")));
        assertEquals(1, released.firstSeqNo());
        assertEquals(3, released.lastSeqNo());
    }

    @Test
    void gapShorterThanThirtyMinutesRejects() {
        var context = aeratingContext("S-FUM-12", "F-FUM-12", "fum-12");
        ingest(context.orderId(), "fum-12-g1", context.version(), 1, "4.000", 3600);
        ingest(context.orderId(), "fum-12-g2", context.version() + 1, 2, "4.000", 4499);
        ingest(context.orderId(), "fum-12-g3", context.version() + 2, 3, "4.000", 5399);

        ApiException ex = assertThrows(ApiException.class,
                () -> fumigationService.release(context.orderId(),
                        new ReleaseFumigationRequest("fum-12-rel", context.version() + 3,
                                List.of("carol", "dave"))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("AERATION_DURATION_SHORT", ex.getCode());
    }

    @Test
    void reviewersMustDifferAndFailureReleasesNothing() {
        var context = aeratingContext("S-FUM-13", "F-FUM-13", "fum-13");
        safeThreeReadings(context.orderId(), context.version(), "fum-13");

        ApiException ex = assertThrows(ApiException.class,
                () -> fumigationService.release(context.orderId(),
                        new ReleaseFumigationRequest("fum-13-rel", context.version() + 3,
                                List.of("carol", "carol"))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("REVIEWERS_MUST_DIFFER", ex.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(context.orderId()).status());
        assertEquals("LOCKED", siloService.getFan(context.fanId()).status());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(context.siloId()).status());
        assertEquals(0, countRows("fumigation_release"));
    }

    @Test
    void lateReadingsAfterReleaseOnlyRecordedAndDoNotChangeEvidence() {
        var context = aeratingContext("S-FUM-14", "F-FUM-14", "fum-14");
        safeThreeReadings(context.orderId(), context.version(), "fum-14");
        ReleaseView released = releaseFumigation(context.orderId(), context.version() + 3, "fum-14-rel");
        assertNotNull(released.releasedAt());

        // 解除后高浓度补录：只记迟到，不改冻结证据，版本不变
        GasReadingView late = fumigationService.ingestGasReading(context.orderId(),
                new GasReadingRequest("fum-14-late", context.version() + 3, 4,
                        new BigDecimal("99.000"), U0.plusSeconds(9000)));
        assertTrue(late.late());
        assertEquals(9, fumigationService.getOrder(context.orderId()).version());

        List<GasReadingView> readings = fumigationService.readings(context.orderId());
        assertEquals(4, readings.size());
        assertFalse(readings.get(0).late());
        assertTrue(readings.get(3).late());

        // 解除记录证据不变
        ReleaseView stored = fumigationService.getRelease(context.orderId());
        assertEquals(3, stored.lastSeqNo());
        assertEquals(1, stored.firstSeqNo());
    }

    @Test
    void highSupplementaryReadingBlocksReleaseEvenWithFreshVersion() {
        var context = aeratingContext("S-FUM-16", "F-FUM-16", "fum-16");
        // 先形成 1,2,3 安全三点
        safeThreeReadings(context.orderId(), context.version(), "fum-16");
        // 补录序号 4 高浓度；最新三点变为 2,3,4，含越限点
        ingest(context.orderId(), "fum-16-g4", 8, 4, "50.000", 9000);

        // 用最新版本 9 解除仍必须拒绝（越限打断，不得用旧三点放行）
        ApiException ex = assertThrows(ApiException.class,
                () -> fumigationService.release(context.orderId(),
                        new ReleaseFumigationRequest("fum-16-rel", 9, List.of("carol", "dave"))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("GAS_NOT_SAFE", ex.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(context.orderId()).status());
        assertEquals("LOCKED", siloService.getFan(context.fanId()).status());
    }

    @Test
    void normalVentilationAndFumigationAreMutuallyExclusive() {
        var silo = newSilo("S-FUM-17");
        var fan = newFan("F-FUM-17");
        raiseAlarm(silo.id(), "fum-17-alarm");

        // 先申请熏蒸窗 U0(00:00Z=本地08:00)-01:00Z，重叠的普通通风（本地08:00-09:00）拒绝
        var order = applyFumigation(silo.id(), "fum-apply-17");
        ApiException ventBlocked = assertThrows(ApiException.class,
                () -> ventilationService.createJob(silo.id(),
                        createJobLike("fum-17-job", fan.id(), U0, U0.plusSeconds(3600))));
        assertEquals("WINDOW_CONFLICT", ventBlocked.getCode());
        assertEquals(0, countRows("ventilation_job"));

        // 走到熏蒸锁仓态；另一下达窗口与熏蒸窗端点相接（本地09:00-10:00），不再撞窗口，
        // 但粮仓处于 FUMIGATING 仍必须拒绝（禁止人员进入与普通通风）
        approveTwice(order.id(), "fum-17");
        startFumigation(order.id(), 3, "fum-17-start");
        ApiException locked = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                createJobLike("fum-17-late", fan.id(),
                        U0.plusSeconds(3600), U0.plusSeconds(7200))));
        assertEquals(409, locked.getStatus().value());
        assertEquals("SILO_LOCKED_BY_FUMIGATION", locked.getCode());
        assertEquals(0, countRows("ventilation_job"));
    }

    @Test
    void notFoundAndIllegalWindow() {        ApiException missing = assertThrows(ApiException.class,
                () -> fumigationService.getOrder(999999));
        assertEquals(404, missing.getStatus().value());
        assertEquals("FUMIGATION_NOT_FOUND", missing.getCode());

        var silo = newSilo("S-FUM-15");
        ApiException badWindow = assertThrows(ApiException.class, () -> fumigationService.apply(silo.id(),
                new ApplyFumigationRequest("fum-15-bad", U0.plusSeconds(60), U0,
                        "A", new BigDecimal("5.000"))));
        assertEquals(400, badWindow.getStatus().value());
        assertEquals("INVALID_WINDOW", badWindow.getCode());
    }

    private void ingest(long orderId, String requestId, int version, int seq, String concentration,
                        long secondsFromU0) {
        fumigationService.ingestGasReading(orderId, new GasReadingRequest(requestId, version, seq,
                new BigDecimal(concentration), U0.plusSeconds(secondsFromU0)));
    }

    private AeratingContext aeratingContext(String siloCode, String fanCode, String prefix) {
        var silo = newSilo(siloCode);
        var fan = newFan(fanCode);
        var order = applyFumigation(silo.id(), prefix + "-apply");
        approveTwice(order.id(), prefix);
        var started = startFumigation(order.id(), 3, prefix + "-start");
        var aerating = beginAeration(started.id(), 4, fan.id(), prefix + "-aer");
        return new AeratingContext(aerating.id(), silo.id(), fan.id(), 5);
    }

    private record AeratingContext(long orderId, long siloId, long fanId, int version) {
    }

    /** 复用普通通风下达入口构造指定 UTC 窗口（折算到 Asia/Shanghai 墙钟）的作业。 */
    private com.example.starter.dto.GrainDtos.CreateJobRequest createJobLike(
            String requestId, long fanId, Instant windowStart, Instant windowEnd) {
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        return new com.example.starter.dto.GrainDtos.CreateJobRequest(requestId, fanId,
                com.example.starter.model.VentilationPurpose.COOLING,
                java.time.LocalDateTime.ofInstant(windowStart, zone),
                java.time.LocalDateTime.ofInstant(windowEnd, zone),
                new BigDecimal("65.00"));
    }
}
