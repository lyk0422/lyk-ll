package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界测试：并发预警唯一受理、停产与复工唯一终态。
 * 通过 CountDownLatch 协调真实并发，并对 Future 设置超时，不以休眠充当验证。
 */
@SpringBootTest
class SlopeConcurrencyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0);

    @Autowired
    private SlopeService slopeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        truncateAll();
    }

    @AfterAll
    static void releaseData(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM resumption_assessment");
        jdbc.update("DELETE FROM evacuation_confirmation");
        jdbc.update("DELETE FROM disposal_chain");
        jdbc.update("DELETE FROM radar_reading");
        jdbc.update("DELETE FROM slope_zone");
    }

    private void truncateAll() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM late_event");
        jdbcTemplate.update("DELETE FROM resumption_assessment");
        jdbcTemplate.update("DELETE FROM evacuation_confirmation");
        jdbcTemplate.update("DELETE FROM disposal_chain");
        jdbcTemplate.update("DELETE FROM radar_reading");
        jdbcTemplate.update("DELETE FROM slope_zone");
    }

    private long createZone(String code) {
        IdempotencyService.Stored stored = slopeService.createZone(new CreateZoneRequest(
                "zone-" + code, code, "并发测区" + code, 5.0, 50.0, 2.0, 10));
        try {
            return objectMapper.readTree(stored.body()).get("zoneId").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readJson(IdempotencyService.Stored stored) {
        try {
            return objectMapper.readTree(stored.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 并发执行两个任务并等待结果（带超时）。 */
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrappedFirst = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return first.call();
            };
            Callable<T> wrappedSecond = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return second.call();
            };
            Future<T> f1 = executor.submit(wrappedFirst);
            Future<T> f2 = executor.submit(wrappedSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 并发红色预警：仅一条处置链被受理创建。 */
    @Test
    @Timeout(60)
    void concurrentRedAlarmsAcceptedOnlyOnce() throws Exception {
        long zoneId = createZone("C-RED");
        slopeService.ingestReading(zoneId, new IngestReadingRequest("c-r0", T0, 0.0));

        Callable<String> alarmA = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.ingestReading(zoneId,
                        new IngestReadingRequest("c-ra", T0.plusHours(24), 120.0));
                return readJson(stored).get("evaluation").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> alarmB = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.ingestReading(zoneId,
                        new IngestReadingRequest("c-rb", T0.plusHours(25), 130.0));
                return readJson(stored).get("evaluation").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(alarmA, alarmB);
        assertEquals(1, results.stream().filter("RED_CHAIN_CREATED"::equals).count(),
                "并发预警唯一受理，实际: " + results);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_chain WHERE zone_id = ?", Integer.class, zoneId));
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    /** 停产与复工竞态：双确认并发到达时仅批准一次，形成唯一终态。 */
    @Test
    @Timeout(60)
    void concurrentResumptionConfirmationApprovesOnlyOnce() throws Exception {
        long zoneId = createZone("C-RES");
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cr-r0", T0, 0.0));
        JsonNode red = readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest("cr-r1", T0.plusHours(24), 120.0)));
        long chainId = red.get("chainId").asLong();
        slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest("cr-ev", T0.plusHours(24).plusMinutes(10), 10));
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cr-r2", T0.plusHours(36), 121.0));
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cr-r3", T0.plusHours(48), 122.0));
        slopeService.applyResumption(chainId, new ResumptionApplyRequest("cr-a1", T0.plusHours(48)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("cr-c1", "SITE_CHECK", T0.plusHours(49)));

        Callable<String> confirmA = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("cr-c2", "MONITORING_REVIEW", T0.plusHours(50)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> confirmB = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("cr-c3", "MONITORING_REVIEW", T0.plusHours(50)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(confirmA, confirmB);
        assertEquals(1, results.stream().filter("APPROVED"::equals).count(),
                "复工仅批准一次，实际: " + results);
        assertEquals(1, results.stream().filter(r -> r.startsWith("HTTP_4")).count(),
                "另一并发确认被拒绝，实际: " + results);
        // 唯一终态：链已复工、测区恢复正常，不存在既停产又批准复工
        assertEquals("RESUMED", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_assessment WHERE chain_id = ? AND status = 'APPROVED'",
                Integer.class, chainId));
    }
}
