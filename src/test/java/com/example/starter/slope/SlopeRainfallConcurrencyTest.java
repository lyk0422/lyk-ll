package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.PublishStrategyRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallHourRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceItem;
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
 * 雨量更正/策略发布与复工确认并发的边界测试：批准必须冻结一致证据且仍满足双确认。
 * 通过 CountDownLatch 协调真实并发，并对 Future 设置超时，不以休眠充当验证。
 */
@SpringBootTest
class SlopeRainfallConcurrencyTest {

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
                "zone-" + code, code, "并发雨量测区" + code, 5.0, 50.0, 2.0, 10));
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

    private long triggerRedChain(long zoneId, String tag) {
        slopeService.ingestReading(zoneId, new IngestReadingRequest(tag + "-r0", T0, 0.0));
        JsonNode red = readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(tag + "-r1", T0.plusHours(24), 120.0)));
        return red.get("chainId").asLong();
    }

    /**
     * 雨量更正与复工批准并发：更正使24小时雨量越过门槛启用折减后，原安全窗口不再安全。
     * 批准要么先于更正提交（批准成立并冻结证据），要么复核到更正后数据（422阻断），
     * 不允许出现批准成立但证据与数据不一致的中间态。
     */
    @Test
    @Timeout(60)
    void concurrentRainfallCorrectionAndResumptionApproval() throws Exception {
        long zoneId = createZone("CC-RAIN");
        long chainId = triggerRedChain(zoneId, "cc");
        slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest("cc-ev", T0.plusHours(24).plusMinutes(10), 10));
        // 策略生效于触发前：不重置计时；雨量门槛24、系数0.5
        slopeService.publishStrategy(zoneId, new PublishStrategyRequest("cc-s1", 0,
                5.0, 50.0, 24.0, 0.5, T0.plusHours(10), T0.plusHours(10)));
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cc-r2", T0.plusHours(36), 121.0));
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cc-r3", T0.plusHours(48), 139.0));
        // 36个小时雨量：除T0+40h为0.0外均为1.0 → 评价窗口累计23<24，不折减
        for (int i = 0; i < 36; i++) {
            double value = i == 28 ? 0.0 : 1.0;
            slopeService.submitRainfallHour(zoneId,
                    new RainfallHourRequest("cc-rain-" + i, T0.plusHours(12 + i), value, 0));
        }
        slopeService.applyResumption(chainId, new ResumptionApplyRequest("cc-a1", T0.plusHours(48)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("cc-c1", "SITE_CHECK", T0.plusHours(49)));

        Callable<String> correctRainfall = () -> {
            // 更正T0+40h为2.0：评价窗口累计25≥24 → 折减生效，安全线降为1.0
            slopeService.submitRainfallHour(zoneId,
                    new RainfallHourRequest("cc-fix", T0.plusHours(40), 2.0, 1));
            return "CORRECTED";
        };
        Callable<String> approve = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("cc-c2", "MONITORING_REVIEW", T0.plusHours(50)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(correctRainfall, approve);
        assertEquals("CORRECTED", results.get(0), "雨量更正始终受理");
        String approval = results.get(1);
        List<ResumptionEvidenceItem> evidence = slopeService.resumptionEvidence(chainId);
        if ("APPROVED".equals(approval)) {
            // 批准先于更正提交：批准成立，证据与批准时数据一致冻结
            assertEquals("RESUMED", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
            assertEquals(2, evidence.size());
            assertEquals("APPROVE", evidence.get(1).phase());
        } else {
            // 复核到更正后数据：折减生效，速率1.5不低于安全线1.0，批准被阻断
            assertEquals("HTTP_422", approval, "实际结果: " + results);
            assertEquals("EXECUTING", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
            assertEquals("LOCKED", jdbcTemplate.queryForObject(
                    "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
            assertEquals(1, evidence.size(), "未批准不得冻结APPROVE证据");
            assertEquals("APPLY", evidence.get(0).phase());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'cc-c2'",
                    Integer.class), "失败的确认不占幂等键");
        }
        // 无论竞态结果如何，雨量更正本身已生效且版本加1
        assertEquals(2.0, jdbcTemplate.queryForObject(
                "SELECT rainfall_mm FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ?",
                Double.class, zoneId, java.sql.Timestamp.valueOf(T0.plusHours(40))), 1e-9);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT version FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ?",
                Integer.class, zoneId, java.sql.Timestamp.valueOf(T0.plusHours(40))));
    }

    /**
     * 策略发布与复工批准并发：变严策略生效后旧安全计时作废。
     * 批准要么先于发布提交（按旧计时批准），要么复核到新策略（422阻断），终态必须一致。
     */
    @Test
    @Timeout(60)
    void concurrentStrategyPublishAndResumptionApproval() throws Exception {
        long zoneId = createZone("CC-STRAT");
        long chainId = triggerRedChain(zoneId, "cs");
        slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest("cs-ev", T0.plusHours(24).plusMinutes(10), 10));
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cs-r2", T0.plusHours(36), 121.0));
        slopeService.ingestReading(zoneId, new IngestReadingRequest("cs-r3", T0.plusHours(48), 122.0));
        slopeService.applyResumption(chainId, new ResumptionApplyRequest("cs-a1", T0.plusHours(48)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("cs-c1", "SITE_CHECK", T0.plusHours(49)));

        Callable<String> publish = () -> {
            // 变严策略（黄4.0<5.0）生效于T0+40h，晚于触发时刻 → 安全计时须重新累计
            slopeService.publishStrategy(zoneId, new PublishStrategyRequest("cs-s1", 0,
                    4.0, 50.0, 100.0, 1.0, T0.plusHours(40), T0.plusHours(40)));
            return "PUBLISHED";
        };
        Callable<String> approve = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("cs-c2", "MONITORING_REVIEW", T0.plusHours(50)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };

        List<String> results = race(publish, approve);
        assertEquals("PUBLISHED", results.get(0), "策略发布始终受理");
        String approval = results.get(1);
        List<ResumptionEvidenceItem> evidence = slopeService.resumptionEvidence(chainId);
        if ("APPROVED".equals(approval)) {
            // 批准先于发布提交：按旧安全计时批准，新策略不撤销既有批准
            assertEquals("RESUMED", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
            assertEquals("NORMAL", jdbcTemplate.queryForObject(
                    "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
            assertEquals(2, evidence.size());
            assertEquals(T0.plusHours(24), evidence.get(1).windowStart(),
                    "批准证据冻结旧计时窗口");
        } else {
            // 复核到新策略：变严生效后旧计时作废，申请时刻距生效不足24小时 → 阻断
            assertEquals("HTTP_422", approval, "实际结果: " + results);
            assertEquals("EXECUTING", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
            assertEquals("LOCKED", jdbcTemplate.queryForObject(
                    "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
            assertEquals(1, evidence.size(), "未批准不得冻结APPROVE证据");
        }
        // 策略发布本身始终生效
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ? AND version = 1",
                Integer.class, zoneId));
    }
}
