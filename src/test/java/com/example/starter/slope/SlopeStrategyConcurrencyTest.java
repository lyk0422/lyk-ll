package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.PublishStrategyRequest;
import com.example.starter.slope.dto.ApiDtos.ReportRainfallRequest;
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
 * 雨量/策略与复工流程的并发边界测试：并发更正唯一受理、并发发布唯一版本、
 * 雨量更正或策略发布与复工确认并发时批准冻结一致证据。
 * 通过 CountDownLatch 协调真实并发，并对 Future 设置超时，不以休眠充当验证。
 */
@SpringBootTest
class SlopeStrategyConcurrencyTest {

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
        jdbc.update("DELETE FROM resumption_evidence");
        jdbc.update("DELETE FROM threshold_strategy");
        jdbc.update("DELETE FROM rainfall_hourly");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM resumption_assessment");
        jdbc.update("DELETE FROM evacuation_confirmation");
        jdbc.update("DELETE FROM disposal_chain");
        jdbc.update("DELETE FROM radar_reading");
        jdbc.update("DELETE FROM slope_zone");
    }

    private void truncateAll() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM resumption_evidence");
        jdbcTemplate.update("DELETE FROM threshold_strategy");
        jdbcTemplate.update("DELETE FROM rainfall_hourly");
        jdbcTemplate.update("DELETE FROM late_event");
        jdbcTemplate.update("DELETE FROM resumption_assessment");
        jdbcTemplate.update("DELETE FROM evacuation_confirmation");
        jdbcTemplate.update("DELETE FROM disposal_chain");
        jdbcTemplate.update("DELETE FROM radar_reading");
        jdbcTemplate.update("DELETE FROM slope_zone");
    }

    private long createZone(String code) {
        IdempotencyService.Stored stored = slopeService.createZone(new CreateZoneRequest(
                "zone-" + code, code, "并发策略测区" + code, 5.0, 50.0, 2.0, 10));
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

    private void seedRainfall(long zoneId, String tag, LocalDateTime fromHour, int hours,
                              double rainfallMm) {
        for (int i = 0; i < hours; i++) {
            IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                    new ReportRainfallRequest(tag + "-rf" + i, fromHour.plusHours(i), rainfallMm, 0));
            assertEquals(201, stored.status());
        }
    }

    /** 并发雨量更正：同一记录同一期望版本，仅一个更正被受理。 */
    @Test
    @Timeout(60)
    void concurrentRainfallCorrectionsAcceptedOnlyOnce() throws Exception {
        long zoneId = createZone("CC-RF");
        LocalDateTime hour = LocalDateTime.of(2026, 9, 1, 10, 0);
        slopeService.reportRainfall(zoneId, new ReportRainfallRequest("ccrf-0", hour, 5.0, 0));

        Callable<String> correctA = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                        new ReportRainfallRequest("ccrf-a", hour, 10.0, 1));
                return "HTTP_" + stored.status();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> correctB = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                        new ReportRainfallRequest("ccrf-b", hour, 20.0, 1));
                return "HTTP_" + stored.status();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(correctA, correctB);
        assertEquals(1, results.stream().filter("HTTP_200"::equals).count(),
                "并发更正仅受理一次，实际: " + results);
        assertEquals(1, results.stream().filter("HTTP_409"::equals).count(),
                "另一并发更正版本冲突，实际: " + results);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT version FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId),
                "成功更正后版本加1");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId));
    }

    /** 并发策略发布：同一期望版本仅一个发布成功，版本从1开始。 */
    @Test
    @Timeout(60)
    void concurrentStrategyPublishesAcceptedOnlyOnce() throws Exception {
        long zoneId = createZone("CC-ST");
        Callable<String> publishA = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.publishStrategy(zoneId,
                        new PublishStrategyRequest("ccst-a", 0, 3.0, 10.0, 100.0, 0.5, T0, T0));
                return "HTTP_" + stored.status();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> publishB = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.publishStrategy(zoneId,
                        new PublishStrategyRequest("ccst-b", 0, 4.0, 12.0, 90.0, 0.6, T0, T0));
                return "HTTP_" + stored.status();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(publishA, publishB);
        assertEquals(1, results.stream().filter("HTTP_201"::equals).count(),
                "并发发布仅受理一次，实际: " + results);
        assertEquals(1, results.stream().filter("HTTP_409"::equals).count(),
                "另一并发发布版本冲突，实际: " + results);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId));
    }

    /** 准备可批准的复工场景：策略黄3.0/门槛100/系数0.5，雨量每小时1.0，窗口读数小时速率2.0。 */
    private long prepareApprovableAssessment(String code, String tag) {
        long zoneId = createZone(code);
        slopeService.publishStrategy(zoneId, new PublishStrategyRequest(
                tag + "-st", 0, 3.0, 10.0, 100.0, 0.5, T0, T0));
        seedRainfall(zoneId, tag, T0.minusHours(24), 96, 1.0);
        slopeService.ingestReading(zoneId, new IngestReadingRequest(tag + "-r0", T0, 0.0));
        JsonNode red = readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(tag + "-r1", T0.plusHours(24), 120.0)));
        long chainId = red.get("chainId").asLong();
        slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(tag + "-ev", T0.plusHours(24).plusMinutes(10), 10));
        slopeService.ingestReading(zoneId, new IngestReadingRequest(tag + "-r2", T0.plusHours(36), 144.0));
        slopeService.ingestReading(zoneId, new IngestReadingRequest(tag + "-r3", T0.plusHours(48), 168.0));
        slopeService.applyResumption(chainId, new ResumptionApplyRequest(tag + "-a1", T0.plusHours(48)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest(tag + "-c1", "SITE_CHECK", T0.plusHours(49)));
        return chainId;
    }

    private void assertConsistentFinalState(long chainId, List<String> results) {
        long approved = results.stream().filter("APPROVED"::equals).count();
        long rejected = results.stream().filter(r -> r.startsWith("HTTP_4")).count();
        assertEquals(1, approved + rejected, "每个并发确认有确定结果: " + results);
        String assessmentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM resumption_assessment WHERE chain_id = ?", String.class, chainId);
        String chainStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId);
        if ("APPROVED".equals(assessmentStatus)) {
            assertEquals("RESUMED", chainStatus, "批准后链进入复工终态");
            assertTrue(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM resumption_evidence WHERE chain_id = ?",
                            Integer.class, chainId) >= 2,
                    "批准时冻结证据");
        } else {
            assertEquals("APPLIED", assessmentStatus);
            assertEquals("EXECUTING", chainStatus, "复评不通过则维持执行中，无双终态");
        }
    }

    /** 策略发布与复工确认并发：批准必须基于一致快照，结果确定且状态一致。 */
    @Test
    @Timeout(60)
    void concurrentStrategyPublishAndResumptionConfirm() throws Exception {
        long chainId = prepareApprovableAssessment("CC-SP", "ccsp");
        long zoneId = jdbcTemplate.queryForObject(
                "SELECT zone_id FROM disposal_chain WHERE id = ?", Long.class, chainId);

        // 变严策略：黄色阈值0.5，确认时刻前生效；若先发布则复评必不通过
        Callable<String> publish = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.publishStrategy(zoneId,
                        new PublishStrategyRequest("ccsp-st2", 1, 0.5, 9.0, 100.0, 0.5,
                                T0.plusHours(49), T0.plusHours(49)));
                return "HTTP_" + stored.status();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> confirm = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("ccsp-c2", "MONITORING_REVIEW", T0.plusHours(50)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(publish, confirm);
        assertTrue(results.contains("HTTP_201"), "策略发布成功: " + results);
        assertConsistentFinalState(chainId,
                results.stream().filter(r -> !"HTTP_201".equals(r)).toList());
    }

    /** 雨量更正与复工确认并发：批准必须冻结一致证据，结果确定且状态一致。 */
    @Test
    @Timeout(60)
    void concurrentRainfallCorrectionAndResumptionConfirm() throws Exception {
        long chainId = prepareApprovableAssessment("CC-RC", "ccrc");
        long zoneId = jdbcTemplate.queryForObject(
                "SELECT zone_id FROM disposal_chain WHERE id = ?", Long.class, chainId);

        // 雨量更正：某小时改为100毫米 → 合计123≥100 → 启用折减 → 阈值1.5 < 读数速率2.0
        LocalDateTime correctedHour = T0.plusHours(26);
        Callable<String> correct = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                        new ReportRainfallRequest("ccrc-fix", correctedHour, 100.0, 1));
                return "HTTP_" + stored.status();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> confirm = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("ccrc-c2", "MONITORING_REVIEW", T0.plusHours(50)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(correct, confirm);
        assertTrue(results.contains("HTTP_200"), "雨量更正成功: " + results);
        assertConsistentFinalState(chainId,
                results.stream().filter(r -> !"HTTP_200".equals(r)).toList());
    }
}
