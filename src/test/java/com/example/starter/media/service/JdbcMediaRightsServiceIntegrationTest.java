package com.example.starter.media.service;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.LicenseMode;
import com.example.starter.media.domain.ScheduleItemStatus;
import com.example.starter.media.web.ApiException;
import com.example.starter.media.web.dto.CorrectGrantRequest;
import com.example.starter.media.web.dto.CreateGrantRequest;
import com.example.starter.media.web.dto.GrantView;
import com.example.starter.media.web.dto.PlayReceiptRequest;
import com.example.starter.media.web.dto.PlayabilityCheckRequest;
import com.example.starter.media.web.dto.RevokeGrantRequest;
import com.example.starter.media.web.dto.ScheduleItemView;
import com.example.starter.media.web.dto.ScheduleSlotDto;
import com.example.starter.media.web.dto.ScheduleView;
import com.example.starter.media.web.dto.SubmitScheduleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于嵌入式 H2（MODE=MySQL）的业务、事务与并发集成测试，全部使用真实数据库边界。
 */
@SpringBootTest
class JdbcMediaRightsServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T0 = BASE;
    private static final Instant T1 = Instant.parse("2026-10-01T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-01T03:00:00Z");
    private static final Instant T4 = Instant.parse("2026-10-01T04:00:00Z");

    @Autowired
    private MediaRightsService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationClock appClock;

    @BeforeEach
    void cleanUp() {
        appClock.reset();
        jdbc.update("DELETE FROM play_schedule_item");
        jdbc.update("DELETE FROM play_schedule");
        jdbc.update("DELETE FROM rights_grant_version");
        jdbc.update("DELETE FROM rights_grant");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM rights_key_lock");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private CreateGrantRequest grantReq(String requestId, String asset, String region, String platform,
                                        Instant start, Instant end, LicenseMode mode) {
        return new CreateGrantRequest(requestId, asset, region, platform, start, end, mode);
    }

    private static ScheduleSlotDto slot(String asset, String region, String platform,
                                        Instant start, Instant end) {
        return new ScheduleSlotDto(asset, region, platform, start, end);
    }

    private static ApiException expectApi(HttpStatus status, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(status, ex.getStatus());
        return ex;
    }

    @Test
    void createsExclusiveGrantAndChecksPlayabilityAtHalfOpenBoundaries() {
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-a", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE)).body();
        assertEquals(1, grant.versionNo());
        assertEquals(GrantStatus.ACTIVE, grant.status());

        assertTrue(service.playable(new PlayabilityCheckRequest("asset-a", "CN", "WEB", T0)));
        assertTrue(service.playable(new PlayabilityCheckRequest("asset-a", "CN", "WEB", T1)));
        // 半开：窗口结束端点不可播
        assertFalse(service.playable(new PlayabilityCheckRequest("asset-a", "CN", "WEB", T2)));
        assertFalse(service.playable(new PlayabilityCheckRequest("asset-a", "CN", "WEB", T0.minusSeconds(1))));
    }

    @Test
    void touchingWindowsAreLegalButPositiveOverlapWithExclusiveConflicts() {
        service.createGrant(grantReq(rid(), "asset-b", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE));

        // 端点相接的另一独占授权合法
        GrantView touching = service.createGrant(
                grantReq(rid(), "asset-b", "CN", "WEB", T2, T4, LicenseMode.EXCLUSIVE)).body();
        assertNotNull(touching.grantId());

        // 正长度交集（哪怕 1 秒）与独占授权冲突
        expectApi(HttpStatus.CONFLICT, () -> service.createGrant(
                grantReq(rid(), "asset-b", "CN", "WEB", T1.plusSeconds(1), T2.plusSeconds(1), LicenseMode.EXCLUSIVE)));
    }

    @Test
    void multipleNonExclusiveGrantsCoexistButExclusiveDoesNot() {
        service.createGrant(grantReq(rid(), "asset-c", "CN", "WEB", T0, T3, LicenseMode.NON_EXCLUSIVE));
        // 两条重叠的非独占授权可以共存
        service.createGrant(grantReq(rid(), "asset-c", "CN", "WEB", T1, T4, LicenseMode.NON_EXCLUSIVE));

        // 任一授权独占且有正长度交集即冲突
        expectApi(HttpStatus.CONFLICT, () -> service.createGrant(
                grantReq(rid(), "asset-c", "CN", "WEB", T2, T3, LicenseMode.EXCLUSIVE)));
    }

    @Test
    void invalidWindowRejected() {
        expectApi(HttpStatus.BAD_REQUEST, () -> service.createGrant(
                grantReq(rid(), "asset-x", "CN", "WEB", T2, T1, LicenseMode.EXCLUSIVE)));
    }

    @Test
    void correctionCreatesNewVersionAndRetainsHistory() {
        GrantView v1 = service.createGrant(
                grantReq(rid(), "asset-d", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE)).body();

        GrantView v2 = service.correctGrant(v1.grantId(),
                new CorrectGrantRequest(rid(), T0, T3, LicenseMode.EXCLUSIVE, 1)).body();
        assertEquals(2, v2.versionNo());
        assertEquals(T3, v2.windowEnd());

        List<GrantView> history = service.listGrantHistory(v1.grantId());
        assertEquals(2, history.size());
        assertEquals(GrantStatus.SUPERSEDED, history.get(0).status());
        assertEquals(GrantStatus.ACTIVE, history.get(1).status());
        // 旧版本窗口证据原样保留
        assertEquals(T2, history.get(0).windowEnd());
    }

    @Test
    void correctionWithWrongExpectedVersionConflicts() {
        GrantView v1 = service.createGrant(
                grantReq(rid(), "asset-e", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE)).body();
        expectApi(HttpStatus.CONFLICT, () -> service.correctGrant(v1.grantId(),
                new CorrectGrantRequest(rid(), T0, T3, LicenseMode.EXCLUSIVE, 99)));
        // 冲突不产生新版本
        assertEquals(1, service.listGrantHistory(v1.grantId()).size());
    }

    @Test
    void scheduleRequiresEverySlotFullyCoveredOrWholeSubmissionRollsBack() {
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-f", "CN", "WEB", T0, T3, LicenseMode.EXCLUSIVE)).body();

        SubmitScheduleRequest ok = new SubmitScheduleRequest(rid(), "sch-ok", List.of(
                slot("asset-f", "CN", "WEB", T0, T1),
                slot("asset-f", "CN", "WEB", T1, T2),
                slot("asset-f", "CN", "WEB", T2, T3)));
        ScheduleView view = service.submitSchedule(ok).body();
        assertEquals(3, view.items().size());
        view.items().forEach(i -> {
            assertEquals(ScheduleItemStatus.BOUND, i.status());
            assertEquals(1, i.boundVersionNo());
        });

        // 第二个时段超出授权窗口：整笔拒绝
        SubmitScheduleRequest bad = new SubmitScheduleRequest(rid(), "sch-bad", List.of(
                slot("asset-f", "CN", "WEB", T0, T1),
                slot("asset-f", "CN", "WEB", T2, T4)));
        expectApi(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.submitSchedule(bad));

        // 零写入：排期、时段、幂等占位均不存在
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(1) FROM play_schedule WHERE schedule_id = 'sch-bad'", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(1) FROM play_schedule_item WHERE schedule_id = 'sch-bad'", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?", Integer.class, bad.requestId()));
    }

    @Test
    void duplicateScheduleIdConflictsAndRollsBack() {
        service.createGrant(grantReq(rid(), "asset-g", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE));
        service.submitSchedule(new SubmitScheduleRequest(rid(), "sch-dup",
                List.of(slot("asset-g", "CN", "WEB", T0, T1))));
        String secondRequest = rid();
        expectApi(HttpStatus.CONFLICT, () -> service.submitSchedule(
                new SubmitScheduleRequest(secondRequest, "sch-dup",
                        List.of(slot("asset-g", "CN", "WEB", T1, T2)))));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?", Integer.class, secondRequest));
    }

    @Test
    void revokeBlocksUpcomingSlotsButPlayedEvidenceStaysFrozen() {
        // 授权窗口 00:00-04:00；当前时刻固定在 02:00。
        appClock.setClock(Clock.fixed(T2, java.time.ZoneOffset.UTC));
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-h", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();

        // 过去时段 00:00-01:00（已开始），未来时段 03:00-04:00（未开始）。
        service.submitSchedule(new SubmitScheduleRequest(rid(), "sch-rev", List.of(
                slot("asset-h", "CN", "WEB", T0, T1),
                slot("asset-h", "CN", "WEB", T3, T4))));

        // 在 01:00 时完成过去时段播出，拿到带播出时间与排期版本的回执
        appClock.setClock(Clock.fixed(T1, java.time.ZoneOffset.UTC));
        var receipt = service.markPlayed(new PlayReceiptRequest(rid(), "sch-rev", 1)).body();
        assertEquals(T1, receipt.playedAt());
        assertEquals(1, receipt.scheduleVersionNo());
        assertEquals(1, receipt.boundVersionNo());

        // 撤销发生在 02:00
        appClock.setClock(Clock.fixed(T2, java.time.ZoneOffset.UTC));
        GrantView revoked = service.revokeGrant(
                grant.grantId(), new RevokeGrantRequest(rid(), null)).body();
        assertEquals(GrantStatus.REVOKED, revoked.status());
        assertEquals(T2, revoked.revokedAt());

        ScheduleView after = service.getSchedule("sch-rev");
        ScheduleItemView played = after.items().get(0);
        ScheduleItemView upcoming = after.items().get(1);
        assertEquals(ScheduleItemStatus.PLAYED, played.status());
        assertEquals(ScheduleItemStatus.BLOCKED, upcoming.status());
        // 已开始但未回执的过去时段不被阻止
        // （第 0 项已 PLAYED，这里仅断言未来项被阻止）

        // BLOCKED 时段禁止补播出回执
        expectApi(HttpStatus.CONFLICT, () -> service.markPlayed(
                new PlayReceiptRequest(rid(), "sch-rev", 2)));

        // 证据冻结：授权版本已 REVOKED，但播出证据仍保留绑定时窗口/模式快照与回执时间
        var evidence = service.listScheduleEvidence("sch-rev");
        var playedEvidence = evidence.stream().filter(e -> e.itemNo() == 1).findFirst().orElseThrow();
        assertEquals(ScheduleItemStatus.PLAYED, playedEvidence.itemStatus());
        assertEquals(T0, playedEvidence.grantWindowStart());
        assertEquals(T4, playedEvidence.grantWindowEnd());
        assertEquals(LicenseMode.EXCLUSIVE, playedEvidence.grantMode());
        assertEquals(GrantStatus.REVOKED, playedEvidence.grantVersionStatus());
        assertEquals(T1, playedEvidence.playedAt());

        var upcomingEvidence = evidence.stream().filter(e -> e.itemNo() == 2).findFirst().orElseThrow();
        assertEquals(ScheduleItemStatus.BLOCKED, upcomingEvidence.itemStatus());
        assertEquals(T4, upcomingEvidence.grantWindowEnd());
    }

    @Test
    void revokePreventsNewSubmissions() {
        appClock.setClock(Clock.fixed(T1, java.time.ZoneOffset.UTC));
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-i", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();
        service.revokeGrant(grant.grantId(), new RevokeGrantRequest(rid(), null));
        expectApi(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.submitSchedule(
                new SubmitScheduleRequest(rid(), "sch-after-revoke",
                        List.of(slot("asset-i", "CN", "WEB", T2, T3)))));
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        String requestId = rid();
        var first = service.createGrant(
                grantReq(requestId, "asset-j", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE));
        var replay = service.createGrant(
                grantReq(requestId, "asset-j", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE));
        assertEquals(first.body().grantId(), replay.body().grantId());
        assertEquals(first.statusCode(), replay.statusCode());
        assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(1) FROM rights_grant", Integer.class));
        assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(1) FROM idempotent_request", Integer.class));
    }

    @Test
    void sameRequestIdDifferentParamsConflicts() {
        String requestId = rid();
        service.createGrant(grantReq(requestId, "asset-k", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE));
        expectApi(HttpStatus.CONFLICT, () -> service.createGrant(
                grantReq(requestId, "asset-k", "CN", "WEB", T0, T3, LicenseMode.EXCLUSIVE)));
    }

    @Test
    void playReceiptIsIdempotentAndRejectsUnknownSchedule() {
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-l", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();
        service.submitSchedule(new SubmitScheduleRequest(rid(), "sch-play",
                List.of(slot("asset-l", "CN", "WEB", T1, T2))));

        String receiptId = rid();
        var r1 = service.markPlayed(new PlayReceiptRequest(receiptId, "sch-play", 1)).body();
        var r2 = service.markPlayed(new PlayReceiptRequest(receiptId, "sch-play", 1)).body();
        assertEquals(r1.playedAt(), r2.playedAt());

        expectApi(HttpStatus.NOT_FOUND, () -> service.markPlayed(
                new PlayReceiptRequest(rid(), "missing", 1)));
    }

    @Test
    void revokeTwiceOrWithWrongVersionConflictsAndCorrectionAfterRevokeRejected() {
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-m", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();
        service.revokeGrant(grant.grantId(), new RevokeGrantRequest(rid(), 1));

        expectApi(HttpStatus.CONFLICT, () -> service.revokeGrant(
                grant.grantId(), new RevokeGrantRequest(rid(), null)));
        expectApi(HttpStatus.CONFLICT, () -> service.correctGrant(
                grant.grantId(), new CorrectGrantRequest(rid(), T0, T3, LicenseMode.EXCLUSIVE, 1)));

        // 撤销前版本号不匹配也拒绝
        GrantView grant2 = service.createGrant(
                grantReq(rid(), "asset-m2", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();
        expectApi(HttpStatus.CONFLICT, () -> service.revokeGrant(
                grant2.grantId(), new RevokeGrantRequest(rid(), 5)));
        assertEquals(GrantStatus.ACTIVE, service.getGrant(grant2.grantId()).status());
    }

    @Test
    void startedButUnplayedSlotStaysBoundAndCanStillReceiveReceiptAfterRevoke() {
        appClock.setClock(Clock.fixed(T2, java.time.ZoneOffset.UTC));
        GrantView grant = service.createGrant(
                grantReq(rid(), "asset-n", "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();
        // 时段1：00:00-01:00 已开始（撤销时刻 02:00 之前）；时段2：03:00-04:00 未开始
        service.submitSchedule(new SubmitScheduleRequest(rid(), "sch-mid", List.of(
                slot("asset-n", "CN", "WEB", T0, T1),
                slot("asset-n", "CN", "WEB", T3, T4))));
        service.revokeGrant(grant.grantId(), new RevokeGrantRequest(rid(), null));

        ScheduleView view = service.getSchedule("sch-mid");
        assertEquals(ScheduleItemStatus.BOUND, view.items().get(0).status());
        assertEquals(ScheduleItemStatus.BLOCKED, view.items().get(1).status());

        // 已开始时段仍可完成播出回执，证据冻结；未开始时段拒绝
        var receipt = service.markPlayed(new PlayReceiptRequest(rid(), "sch-mid", 1)).body();
        assertEquals(ScheduleItemStatus.PLAYED, receipt.status());
        expectApi(HttpStatus.CONFLICT, () -> service.markPlayed(
                new PlayReceiptRequest(rid(), "sch-mid", 2)));
    }

    @Test
    void correctionWindowOverlappingOtherExclusiveGrantConflictsWithoutNewVersion() {
        GrantView a = service.createGrant(
                grantReq(rid(), "asset-o", "CN", "WEB", T0, T2, LicenseMode.EXCLUSIVE)).body();
        // 同键下另一条端点相接的独占授权线，合法共存
        service.createGrant(grantReq(rid(), "asset-o", "CN", "WEB", T2, T4, LicenseMode.EXCLUSIVE));
        // 把 a 的窗口更正为 01:00-03:00，会与第二条独占窗口产生正长度交集
        expectApi(HttpStatus.CONFLICT, () -> service.correctGrant(a.grantId(),
                new CorrectGrantRequest(rid(), T1, T3, LicenseMode.EXCLUSIVE, 1)));
        assertEquals(1, service.listGrantHistory(a.grantId()).size());
    }

    @Test
    void concurrentIdenticalRequestsWithSameRequestIdCreateExactlyOneGrant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String requestId = rid();
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.Callable<ServiceResult<?>> task = () -> {
                ready.countDown();
                start.await();
                return service.createGrant(grantReq(requestId, "asset-q", "CN", "WEB",
                        T0, T2, LicenseMode.EXCLUSIVE));
            };
            var f1 = pool.submit(task);
            var f2 = pool.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var r1 = f1.get(10, TimeUnit.SECONDS);
            var r2 = f2.get(10, TimeUnit.SECONDS);
            assertEquals(201, r1.statusCode());
            assertEquals(201, r2.statusCode());
            assertEquals(((GrantView) r1.body()).grantId(), ((GrantView) r2.body()).grantId());
            assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(1) FROM rights_grant", Integer.class));
            assertEquals(1, (int) jdbc.queryForObject(
                    "SELECT COUNT(1) FROM idempotent_request", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 撤销与排期提交并发：两者必须形成一致顺序。
     * 交错起跑使两种线性顺序都被确定性覆盖：
     * 撤销先提交 → 排期整笔 422 零写入；排期先提交 → 绑定成功，未开始时段转为 BLOCKED。
     */
    @Test
    void revokeAndScheduleSubmitAreLinearizedUnderConcurrency() throws Exception {
        int iterationsPerOrder = 5;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int scheduleFirstSeen = 0;
        int revokeFirstSeen = 0;
        try {
            for (int i = 0; i < iterationsPerOrder * 2; i++) {
                final int iter = i;
                // 偶数轮让排期先起跑，奇数轮让撤销先起跑（错开 150ms 确保先到者拿到键空间锁）
                boolean scheduleStartsFirst = i % 2 == 0;
                String asset = "asset-race-" + iter;
                appClock.setClock(Clock.fixed(T1, java.time.ZoneOffset.UTC));
                GrantView grant = service.createGrant(
                        grantReq(rid(), asset, "CN", "WEB", T0, T4, LicenseMode.EXCLUSIVE)).body();

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch revokeStart = new CountDownLatch(1);
                CountDownLatch scheduleStart = new CountDownLatch(1);

                Future<ApiException> revokeFuture = pool.submit(() -> {
                    ready.countDown();
                    try {
                        revokeStart.await();
                        service.revokeGrant(grant.grantId(), new RevokeGrantRequest(rid(), null));
                        return null;
                    } catch (ApiException ex) {
                        return ex;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                });
                Future<ApiException> submitFuture = pool.submit(() -> {
                    ready.countDown();
                    try {
                        scheduleStart.await();
                        service.submitSchedule(new SubmitScheduleRequest(rid(), "sch-race-" + iter,
                                List.of(slot(asset, "CN", "WEB", T2, T3))));
                        return null;
                    } catch (ApiException ex) {
                        return ex;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                });

                assertTrue(ready.await(5, TimeUnit.SECONDS));
                if (scheduleStartsFirst) {
                    scheduleStart.countDown();
                    Thread.sleep(150);
                    revokeStart.countDown();
                } else {
                    revokeStart.countDown();
                    Thread.sleep(150);
                    scheduleStart.countDown();
                }

                ApiException revokeError = revokeFuture.get(15, TimeUnit.SECONDS);
                ApiException submitError = submitFuture.get(15, TimeUnit.SECONDS);
                assertNull(revokeError);

                ScheduleView schedule = null;
                try {
                    schedule = service.getSchedule("sch-race-" + iter);
                } catch (ApiException ignored) {
                    // 排期不存在
                }

                if (submitError != null) {
                    revokeFirstSeen++;
                    // 撤销先于排期：整笔拒绝且零写入
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, submitError.getStatus());
                    assertNull(schedule);
                    assertEquals(0, (int) jdbc.queryForObject(
                            "SELECT COUNT(1) FROM play_schedule_item WHERE schedule_id = ?",
                            Integer.class, "sch-race-" + iter));
                } else {
                    scheduleFirstSeen++;
                    // 排期先于撤销：绑定成功，但未来时段必须已转为 BLOCKED，绝不能停留在 BOUND
                    assertNotNull(schedule);
                    assertEquals(ScheduleItemStatus.BLOCKED, schedule.items().get(0).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 两种线性顺序都必须实际发生并验证
        assertTrue(scheduleFirstSeen >= iterationsPerOrder, "未观察到排期先提交的顺序");
        assertTrue(revokeFirstSeen >= iterationsPerOrder, "未观察到撤销先提交的顺序");
    }
}
