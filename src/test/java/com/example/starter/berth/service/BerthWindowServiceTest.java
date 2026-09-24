package com.example.starter.berth.service;

import com.example.starter.berth.domain.WindowStatus;
import com.example.starter.berth.dto.CreateWindowRequest;
import com.example.starter.berth.dto.RescheduleRequest;
import com.example.starter.berth.dto.WindowResponse;
import com.example.starter.berth.testsupport.MutableClock;
import com.example.starter.berth.testsupport.TestClockConfiguration;
import com.example.starter.berth.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 靠泊窗口核心业务测试：基于真实 H2（MySQL 兼容模式）验证冲突判定、幂等、状态机与并发。
 * 测试基准时钟为 2026-10-15T04:00:00Z，当日合成允许时段为 06:00-12:00 与 13:00-20:00（UTC）。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class BerthWindowServiceTest {

    private static final String DAY = "2026-10-15";

    @Autowired
    private BerthWindowService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pilot_assignment");
        jdbc.update("DELETE FROM berth_window");
        jdbc.execute("ALTER TABLE berth_window ALTER COLUMN id RESTART WITH 1");
        clock.setInstant(TestClockConfiguration.BASE);
    }

    @Test
    void create_validRequest_returnsPendingWithPilotsAssigned() {
        WindowResponse response = service.create(req("REQ-001", "BERTH-A", "08:00", "10:00", "5.00", 2));

        assertEquals(WindowStatus.PENDING, response.status());
        assertEquals("REQ-001", response.requestId());
        assertEquals(2, response.assignedPilots().size());
        assertEquals(1, windowCount());
    }

    @Test
    void create_overlappingWindowSameBerth_rejected409() {
        service.create(req("REQ-010", "BERTH-A", "08:00", "10:00", "5.00", 1));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(req("REQ-011", "BERTH-A", "09:00", "11:00", "5.00", 1)));
        assertEquals("BERTH_WINDOW_CONFLICT", ex.code());
        assertEquals(1, windowCount());
    }

    @Test
    void create_endpointTouchingWindow_allowed() {
        WindowResponse first = service.create(req("REQ-020", "BERTH-A", "08:00", "10:00", "5.00", 1));
        WindowResponse second = service.create(req("REQ-021", "BERTH-A", "10:00", "12:00", "5.00", 1));

        assertEquals(WindowStatus.PENDING, first.status());
        assertEquals(WindowStatus.PENDING, second.status());
        assertEquals(2, windowCount());
    }

    @Test
    void create_terminalWindowDoesNotBlock_sameSlotReusable() {
        WindowResponse first = service.create(req("REQ-030", "BERTH-A", "08:00", "10:00", "5.00", 1));
        service.reject(first.id());

        WindowResponse second = service.create(req("REQ-031", "BERTH-A", "08:00", "10:00", "5.00", 1));
        assertEquals(WindowStatus.PENDING, second.status());
    }

    @Test
    void create_pilotReuseAcrossTouchingWindows_allowed() {
        // 5 名引航员全部分配给 08:00-10:00；端点相接的 10:00-12:00 可复用全部引航员
        service.create(req("REQ-040", "BERTH-A", "08:00", "10:00", "5.00", 5));

        WindowResponse second = service.create(req("REQ-041", "BERTH-B", "10:00", "12:00", "5.00", 5));
        assertEquals(5, second.assignedPilots().size());
    }

    @Test
    void create_pilotShortageOnOverlap_rejected409() {
        // 5 名引航员全部被 08:00-10:00 占用，重叠时段再申请 1 名即不足
        service.create(req("REQ-050", "BERTH-A", "08:00", "10:00", "5.00", 5));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(req("REQ-051", "BERTH-B", "09:00", "11:00", "5.00", 1)));
        assertEquals("PILOT_UNAVAILABLE", ex.code());
        assertEquals(1, windowCount());
    }

    @Test
    void create_draftExceedsLimit_rejected422WithoutRecord() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(req("REQ-060", "BERTH-B", "08:00", "10:00", "9.50", 1)));

        assertEquals("DRAFT_EXCEEDS_LIMIT", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals(0, windowCount());
    }

    @Test
    void create_unknownBerth_rejected422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(req("REQ-061", "BERTH-Z", "08:00", "10:00", "5.00", 1)));
        assertEquals("BERTH_NOT_FOUND", ex.code());
        assertEquals(0, windowCount());
    }

    @Test
    void create_outsideAllowedWindow_rejected422() {
        // 12:00-13:00 横跨两段允许时段之间的间隔
        ApiException gap = assertThrows(ApiException.class,
                () -> service.create(req("REQ-070", "BERTH-A", "12:00", "13:00", "5.00", 1)));
        assertEquals("OUTSIDE_ALLOWED_WINDOW", gap.code());

        // 05:00-07:00 早于当日首个允许时段
        ApiException early = assertThrows(ApiException.class,
                () -> service.create(req("REQ-071", "BERTH-A", "05:00", "07:00", "5.00", 1)));
        assertEquals("OUTSIDE_ALLOWED_WINDOW", early.code());
        assertEquals(0, windowCount());
    }

    @Test
    void create_exactlyOnAllowedBoundary_allowed() {
        WindowResponse morning = service.create(req("REQ-080", "BERTH-A", "06:00", "12:00", "5.00", 1));
        WindowResponse afternoon = service.create(req("REQ-081", "BERTH-B", "13:00", "20:00", "5.00", 1));

        assertEquals(WindowStatus.PENDING, morning.status());
        assertEquals(WindowStatus.PENDING, afternoon.status());
    }

    @Test
    void create_sameKeySameParams_replaysFirstResult() {
        WindowResponse first = service.create(req("REQ-090", "BERTH-A", "08:00", "10:00", "5.00", 2));
        WindowResponse replay = service.create(req("REQ-090", "BERTH-A", "08:00", "10:00", "5.00", 2));

        assertEquals(first.id(), replay.id());
        assertEquals(first.status(), replay.status());
        assertEquals(1, windowCount());
    }

    @Test
    void create_sameKeyDifferentParams_rejected409WithoutNewRecord() {
        service.create(req("REQ-091", "BERTH-A", "08:00", "10:00", "5.00", 2));

        CreateWindowRequest different = new CreateWindowRequest(
                "REQ-091", "另一艘船", "BERTH-A",
                Instant.parse(DAY + "T08:00:00Z"), Instant.parse(DAY + "T10:00:00Z"),
                new BigDecimal("5.00"), 2, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.create(different));

        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals(1, windowCount());
    }

    @Test
    void create_concurrentSameKey_onlyOneRecordAndConsistentResponse() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WindowResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.create(req("REQ-100", "BERTH-A", "08:00", "10:00", "5.00", 1));
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        List<WindowResponse> responses = new ArrayList<>();
        for (Future<WindowResponse> future : futures) {
            responses.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long distinctIds = responses.stream().map(WindowResponse::id).distinct().count();
        assertEquals(1, distinctIds, "并发同键必须返回同一窗口 id");
        assertEquals(1, windowCount(), "并发同键只允许一条记录");
    }

    @Test
    void terminalStates_idempotentAndNotResurrectable() {
        // 驳回幂等，且驳回后不得批准或取消
        WindowResponse rejected = service.create(req("REQ-110", "BERTH-A", "08:00", "09:00", "5.00", 1));
        service.reject(rejected.id());
        WindowResponse rejectedAgain = service.reject(rejected.id());
        assertEquals(WindowStatus.REJECTED, rejectedAgain.status());
        assertEquals("ILLEGAL_STATE", assertThrows(ApiException.class,
                () -> service.approve(rejected.id())).code());
        assertEquals("ILLEGAL_STATE", assertThrows(ApiException.class,
                () -> service.cancel(rejected.id())).code());

        // 取消幂等，且取消后不得驳回
        WindowResponse cancelled = service.create(req("REQ-111", "BERTH-A", "09:00", "10:00", "5.00", 1));
        service.cancel(cancelled.id());
        WindowResponse cancelledAgain = service.cancel(cancelled.id());
        assertEquals(WindowStatus.CANCELLED, cancelledAgain.status());
        assertEquals("ILLEGAL_STATE", assertThrows(ApiException.class,
                () -> service.reject(cancelled.id())).code());

        // 完成幂等，且完成后不得取消或批准
        WindowResponse completed = service.create(req("REQ-112", "BERTH-A", "10:00", "11:00", "5.00", 1));
        service.approve(completed.id());
        clock.setInstant(Instant.parse(DAY + "T10:00:00Z"));
        assertEquals(1, service.activateDueWindows());
        service.depart(completed.id());
        WindowResponse completedAgain = service.depart(completed.id());
        assertEquals(WindowStatus.COMPLETED, completedAgain.status());
        assertEquals("ILLEGAL_STATE", assertThrows(ApiException.class,
                () -> service.cancel(completed.id())).code());
        assertEquals("ILLEGAL_STATE", assertThrows(ApiException.class,
                () -> service.approve(completed.id())).code());
    }

    @Test
    void approveThenActivateThenDepart_fullLifecycle() {
        WindowResponse created = service.create(req("REQ-120", "BERTH-A", "06:00", "08:00", "5.00", 2));

        WindowResponse approved = service.approve(created.id());
        assertEquals(WindowStatus.APPROVED, approved.status());

        // 重复批准幂等
        assertEquals(WindowStatus.APPROVED, service.approve(created.id()).status());

        // 到达起始时刻自动生效
        clock.setInstant(Instant.parse(DAY + "T06:00:00Z"));
        assertEquals(1, service.activateDueWindows());
        assertEquals(WindowStatus.ACTIVE, service.get(created.id()).status());

        // 登记离泊后完成，引航员释放
        WindowResponse departed = service.depart(created.id());
        assertEquals(WindowStatus.COMPLETED, departed.status());
        assertTrue(departed.assignedPilots().isEmpty());
    }

    @Test
    void approve_afterWindowEnd_rejected409AndStaysPending() {
        WindowResponse created = service.create(req("REQ-130", "BERTH-A", "06:00", "07:00", "5.00", 1));

        clock.setInstant(Instant.parse(DAY + "T07:30:00Z"));
        ApiException ex = assertThrows(ApiException.class, () -> service.approve(created.id()));

        assertEquals("TOO_LATE", ex.code());
        assertEquals(WindowStatus.PENDING, service.get(created.id()).status());
    }

    @Test
    void reschedule_approvedWindow_createsNewAndSupersedesOriginal() {
        WindowResponse original = service.create(req("REQ-140", "BERTH-A", "06:00", "08:00", "5.00", 1));
        service.approve(original.id());

        WindowResponse rescheduled = service.reschedule(original.id(),
                new RescheduleRequest("REQ-140-R", Instant.parse(DAY + "T09:00:00Z"),
                        Instant.parse(DAY + "T11:00:00Z")));

        assertEquals(WindowStatus.PENDING, rescheduled.status());
        assertEquals(original.id(), rescheduled.supersedesId());
        assertNotEquals(original.id(), rescheduled.id());
        assertEquals(WindowStatus.SUPERSEDED, service.get(original.id()).status());
    }

    @Test
    void reschedule_sameSlotAsOriginal_doesNotConflictWithItself() {
        WindowResponse original = service.create(req("REQ-141", "BERTH-A", "08:00", "10:00", "5.00", 1));
        service.approve(original.id());

        WindowResponse rescheduled = service.reschedule(original.id(),
                new RescheduleRequest("REQ-141-R", Instant.parse(DAY + "T08:00:00Z"),
                        Instant.parse(DAY + "T10:00:00Z")));

        assertEquals(WindowStatus.PENDING, rescheduled.status());
        assertEquals(WindowStatus.SUPERSEDED, service.get(original.id()).status());
    }

    @Test
    void reschedule_conflictingSlot_rejectedAndOriginalUnchanged() {
        WindowResponse first = service.create(req("REQ-142", "BERTH-A", "06:00", "08:00", "5.00", 1));
        service.approve(first.id());
        WindowResponse blocker = service.create(req("REQ-143", "BERTH-A", "09:00", "11:00", "5.00", 1));
        service.approve(blocker.id());

        ApiException ex = assertThrows(ApiException.class, () -> service.reschedule(first.id(),
                new RescheduleRequest("REQ-142-R", Instant.parse(DAY + "T07:00:00Z"),
                        Instant.parse(DAY + "T10:00:00Z"))));

        assertEquals("BERTH_WINDOW_CONFLICT", ex.code());
        assertEquals(WindowStatus.APPROVED, service.get(first.id()).status());
        assertEquals(2, windowCount());
    }

    @Test
    void reschedule_terminalWindow_rejected409() {
        WindowResponse original = service.create(req("REQ-144", "BERTH-A", "08:00", "10:00", "5.00", 1));
        service.cancel(original.id());

        ApiException ex = assertThrows(ApiException.class, () -> service.reschedule(original.id(),
                new RescheduleRequest("REQ-144-R", Instant.parse(DAY + "T13:00:00Z"),
                        Instant.parse(DAY + "T15:00:00Z"))));

        assertEquals("SUPERSEDE_NOT_ALLOWED", ex.code());
        assertEquals(WindowStatus.CANCELLED, service.get(original.id()).status());
    }

    private CreateWindowRequest req(String requestId, String berth, String start, String end,
                                    String draft, int pilots) {
        return new CreateWindowRequest(
                requestId, "合成船-" + requestId, berth,
                Instant.parse(DAY + "T" + start + ":00Z"), Instant.parse(DAY + "T" + end + ":00Z"),
                new BigDecimal(draft), pilots, null);
    }

    private int windowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM berth_window", Integer.class);
        return count == null ? 0 : count;
    }
}
