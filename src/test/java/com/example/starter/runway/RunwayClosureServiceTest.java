package com.example.starter.runway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.runway.dto.ClosureDetailView;
import com.example.starter.runway.dto.ClosureEventView;
import com.example.starter.runway.dto.InspectionRequest;
import com.example.starter.runway.dto.ReopenRequest;
import com.example.starter.runway.dto.ReportClosureRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 跑道关闭事件业务测试：主流程、失败分支、15 分钟/0.35 边界、双人规则、
 * 开放通告原子性、并发唯一事件与同键重放、归档终态。
 */
@SpringBootTest
class RunwayClosureServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private RunwayClosureService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM closure_status_timeline");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_closure_event");
        clock.setInstant(T0);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM closure_status_timeline");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_closure_event");
    }

    private long report(String runway, String requestId, String eventType, Instant reportedAt) throws Exception {
        String json = service.reportClosure(runway, new ReportClosureRequest(requestId, eventType, reportedAt));
        return objectMapper.readTree(json).get("id").asLong();
    }

    private JsonNode inspect(long eventId, String requestId, int inspectors, int rvr,
            String friction, String assessment, Instant startedAt) throws Exception {
        String json = service.recordInspection(eventId, new InspectionRequest(
                requestId, inspectors, rvr, new BigDecimal(friction), assessment, startedAt));
        return objectMapper.readTree(json);
    }

    private JsonNode reopen(long eventId, String requestId) throws Exception {
        return objectMapper.readTree(service.reopen(eventId, new ReopenRequest(requestId)));
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void reportTriggersTempClosureAndPublishesClosureNotam() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);

        ClosureDetailView detail = service.detail(eventId);
        assertEquals("TEMP_CLOSED", detail.event().status());
        assertEquals(T0, detail.event().closedAt());
        assertEquals(1, detail.timeline().size());
        assertEquals("TEMP_CLOSED", detail.timeline().get(0).toStatus());
        assertEquals(1, detail.notams().size());
        assertEquals("CLOSURE", detail.notams().get(0).notamType());
    }

    @Test
    void reportSameKeySameParamsReplaysFirstResult() throws Exception {
        String first = service.reportClosure("01L", new ReportClosureRequest("rep-1", "FOD", T0));
        String second = service.reportClosure("01L", new ReportClosureRequest("rep-1", "FOD", T0));

        assertEquals(first, second);
        assertEquals(1, countRows("runway_closure_event"));
        assertEquals(1, countRows("notam_record"));
    }

    @Test
    void reportSameKeyDifferentParamsReturns409() throws Exception {
        report("01L", "rep-1", "FOD", T0);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.reportClosure("01L", new ReportClosureRequest("rep-1", "PAVEMENT_DAMAGE", T0)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, countRows("runway_closure_event"));
    }

    @Test
    void concurrentReportsAllowSingleActiveEventPerRunway() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            Runnable task = () -> {
                String requestId = Thread.currentThread().getName();
                ready.countDown();
                try {
                    start.await();
                    service.reportClosure("02R", new ReportClosureRequest(requestId, "FOD", T0));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> first = pool.submit(task, "rep-a");
            Future<?> second = pool.submit(task, "rep-b");
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, success.get());
        assertEquals(1, conflict.get());
        assertEquals(1, countRows("runway_closure_event"));
    }

    @Test
    void concurrentSameKeyReportsReplayFirstResult() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> task = () -> {
                ready.countDown();
                start.await();
                return service.reportClosure("03L", new ReportClosureRequest("rep-same", "FOD", T0));
            };
            Future<String> first = pool.submit(task);
            Future<String> second = pool.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            String firstJson = first.get(15, TimeUnit.SECONDS);
            String secondJson = second.get(15, TimeUnit.SECONDS);
            assertEquals(firstJson, secondJson);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, countRows("runway_closure_event"));
        assertEquals(1, countRows("notam_record"));
    }

    @Test
    void inspectionExactlyAt15MinutesIsDelayedAndEscalated() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);

        JsonNode response = inspect(eventId, "ins-1", 2, 800, "0.500",
                "CLEAR_TO_REOPEN", T0.plusSeconds(15 * 60));

        assertTrue(response.get("inspection").get("delayed").asBoolean());
        assertTrue(response.get("inspection").get("effective").asBoolean());
        assertTrue(response.get("event").get("escalated").asBoolean());
        ClosureDetailView detail = service.detail(eventId);
        assertTrue(detail.timeline().stream().anyMatch(entry -> entry.reason().contains("迟延")));
    }

    @Test
    void inspectionJustBefore15MinutesIsNotDelayed() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);

        JsonNode response = inspect(eventId, "ins-1", 2, 800, "0.500",
                "CLEAR_TO_REOPEN", T0.plusSeconds(15 * 60 - 1));

        assertFalse(response.get("inspection").get("delayed").asBoolean());
        assertFalse(response.get("event").get("escalated").asBoolean());
    }

    @Test
    void frictionExactly035AllowsReopen() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);
        inspect(eventId, "ins-1", 2, 800, "0.350", "CLEAR_TO_REOPEN", T0.plusSeconds(60));

        JsonNode response = reopen(eventId, "reo-1");

        assertEquals("REOPENED", response.get("event").get("status").asText());
        assertEquals("REOPEN", response.get("notam").get("notamType").asText());
        ClosureDetailView detail = service.detail(eventId);
        assertEquals(2, detail.notams().size());
        assertNotEquals(null, detail.event().reopenedAt());
    }

    @Test
    void frictionBelow035MustStayClosed() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);
        inspect(eventId, "ins-1", 2, 800, "0.349", "CLEAR_TO_REOPEN", T0.plusSeconds(60));

        ApiException ex = assertThrows(ApiException.class, () -> reopen(eventId, "reo-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        ClosureDetailView detail = service.detail(eventId);
        assertEquals("INSPECTING", detail.event().status());
        assertTrue(detail.notams().stream().noneMatch(notam -> notam.notamType().equals("REOPEN")));
    }

    @Test
    void repairRequiredNeedsPassingRetestBeforeReopen() throws Exception {
        long eventId = report("01L", "rep-1", "PAVEMENT_DAMAGE", T0);
        JsonNode first = inspect(eventId, "ins-1", 2, 800, "0.500", "REPAIR_REQUIRED", T0.plusSeconds(60));
        assertTrue(first.get("event").get("repairRequired").asBoolean());
        assertFalse(first.get("event").get("repairCleared").asBoolean());

        ApiException ex = assertThrows(ApiException.class, () -> reopen(eventId, "reo-1"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("复测"));

        JsonNode retest = inspect(eventId, "ins-2", 2, 800, "0.400", "CLEAR_TO_REOPEN", T0.plusSeconds(120));
        assertTrue(retest.get("event").get("repairCleared").asBoolean());

        JsonNode response = reopen(eventId, "reo-2");
        assertEquals("REOPENED", response.get("event").get("status").asText());
    }

    @Test
    void lowVisibilityRequiresTwoInspectors() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);

        JsonNode single = inspect(eventId, "ins-1", 1, 500, "0.500", "CLEAR_TO_REOPEN", T0.plusSeconds(60));
        assertFalse(single.get("inspection").get("effective").asBoolean());
        assertEquals("TEMP_CLOSED", single.get("event").get("status").asText());

        ApiException ex = assertThrows(ApiException.class, () -> reopen(eventId, "reo-1"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());

        JsonNode pair = inspect(eventId, "ins-2", 2, 500, "0.500", "CLEAR_TO_REOPEN", T0.plusSeconds(120));
        assertTrue(pair.get("inspection").get("effective").asBoolean());

        JsonNode response = reopen(eventId, "reo-2");
        assertEquals("REOPENED", response.get("event").get("status").asText());
    }

    @Test
    void reopenAndNotamAreAtomic() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);
        inspect(eventId, "ins-1", 2, 800, "0.349", "CLEAR_TO_REOPEN", T0.plusSeconds(60));

        // 失败分支：开放被拒绝时状态与通告均不产生
        assertThrows(ApiException.class, () -> reopen(eventId, "reo-1"));
        ClosureDetailView afterFailure = service.detail(eventId);
        assertEquals("INSPECTING", afterFailure.event().status());
        assertEquals(1, afterFailure.notams().size());

        // 成功分支：开放与通告同事务落库
        inspect(eventId, "ins-2", 2, 800, "0.400", "CLEAR_TO_REOPEN", T0.plusSeconds(120));
        reopen(eventId, "reo-2");
        ClosureDetailView afterSuccess = service.detail(eventId);
        assertEquals("REOPENED", afterSuccess.event().status());
        List<String> notamTypes = afterSuccess.notams().stream().map(n -> n.notamType()).toList();
        assertEquals(List.of("CLOSURE", "REOPEN"), notamTypes);
    }

    @Test
    void reopenSameKeyReplaysWithoutDuplicateNotam() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);
        inspect(eventId, "ins-1", 2, 800, "0.500", "CLEAR_TO_REOPEN", T0.plusSeconds(60));

        String first = service.reopen(eventId, new ReopenRequest("reo-1"));
        String second = service.reopen(eventId, new ReopenRequest("reo-1"));

        assertEquals(first, second);
        assertEquals(2, countRows("notam_record"));

        ApiException ex = assertThrows(ApiException.class, () -> reopen(eventId, "reo-other"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(2, countRows("notam_record"));
    }

    @Test
    void archiveIsTerminalAndLateInspectionIsOnlyRecorded() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);
        inspect(eventId, "ins-1", 2, 800, "0.500", "CLEAR_TO_REOPEN", T0.plusSeconds(60));
        reopen(eventId, "reo-1");

        ClosureEventView archived = service.archive(eventId);
        assertEquals("ARCHIVED", archived.status());

        ClosureEventView archivedAgain = service.archive(eventId);
        assertEquals("ARCHIVED", archivedAgain.status());
        assertEquals(archived.archivedAt(), archivedAgain.archivedAt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_status_timeline WHERE to_status = 'ARCHIVED'", Long.class).longValue());

        JsonNode late = inspect(eventId, "ins-late", 2, 800, "0.600", "CLEAR_TO_REOPEN", T0.plusSeconds(180));
        assertTrue(late.get("inspection").get("late").asBoolean());
        assertFalse(late.get("inspection").get("effective").asBoolean());
        assertEquals("ARCHIVED", late.get("event").get("status").asText());

        ClosureDetailView detail = service.detail(eventId);
        assertEquals(2, detail.inspections().size());
        assertEquals("ARCHIVED", detail.event().status());
    }

    @Test
    void archiveBeforeReopenReturns409() throws Exception {
        long eventId = report("01L", "rep-1", "FOD", T0);

        ApiException ex = assertThrows(ApiException.class, () -> service.archive(eventId));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("TEMP_CLOSED", service.detail(eventId).event().status());
    }

    @Test
    void inspectionOnUnknownEventReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> inspect(999L, "ins-1", 2, 800,
                "0.500", "CLEAR_TO_REOPEN", T0.plusSeconds(60)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
