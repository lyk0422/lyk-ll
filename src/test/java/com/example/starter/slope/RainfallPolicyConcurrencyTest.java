package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.PolicyPublishRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallReportRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceInfo;
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
 * 雨量联动与复工门禁并发边界测试：雨量更正/策略发布与复工确认并发时批准冻结一致证据、
 * 同键并发发布唯一受理、同小时雨量并发上报唯一版本。
 * 通过 CountDownLatch 协调真实并发，并对 Future 设置超时，不以休眠充当验证。
 */
@SpringBootTest
class RainfallPolicyConcurrencyTest {

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
        jdbc.update("DELETE FROM threshold_policy");
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
        jdbcTemplate.update("DELETE FROM threshold_policy");
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
                "zone-" + code, code, "并发测区" + code, 5.0, 50.0, 2.0, 10));
        return readJson(stored).get("zoneId").asLong();
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

    private void reportRainfallRange(long zoneId, String tag, LocalDateTime from,
                                     LocalDateTime to, double value) {
        for (LocalDateTime h = from; h.isBefore(to); h = h.plusHours(1)) {
            slopeService.reportRainfall(zoneId,
                    new RainfallReportRequest(tag + "-" + h, h, value, null, h));
        }
    }

    private void publishPolicy(long zoneId, String requestId, long expectedVersion, double yellow,
                               double red, double rainThreshold, double factor,
                               LocalDateTime effectiveAt, LocalDateTime submittedAt) {
        IdempotencyService.Stored stored = slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest(requestId, expectedVersion, yellow, red, rainThreshold,
                        factor, effectiveAt, submittedAt));
        assertEquals(201, stored.status());
    }

    private JsonNode ingest(long zoneId, String requestId, LocalDateTime measuredAt,
                            double displacement) {
        return readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(requestId, measuredAt, displacement)));
    }

    /**
     * 构造待批准场景：策略v1生效、红色链、按时撤人、24小时回落读数、复工申请与首个确认。
     *
     * @param ratePerHour 回落读数小时速率
     * @return chainId
     */
    private long prepareApprovableAssessment(long zoneId, String tag, double ratePerHour) {
        ingest(zoneId, tag + "-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, tag + "-r1", T0.plusHours(1), 9.0);
        long chainId = red.get("chainId").asLong();
        slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(tag + "-ev", T0.plusHours(1).plusMinutes(10), 10));
        for (int h = 2; h <= 25; h++) {
            ingest(zoneId, tag + "-s" + h, T0.plusHours(h), 9.0 + ratePerHour * (h - 1));
        }
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest(tag + "-a1", T0.plusHours(25)));
        assertEquals(201, applied.status());
        IdempotencyService.Stored first = slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest(tag + "-c1", "SITE_CHECK", T0.plusHours(26)));
        assertEquals(201, first.status());
        return chainId;
    }

    /** 雨量更正与复工批准并发：批准必须基于一致快照，冻结证据与批准结果一致。 */
    @Test
    @Timeout(60)
    void concurrentRainfallCorrectionAndResumptionApproval() throws Exception {
        long zoneId = createZone("C-RC");
        // 黄4.0/红8.0，门槛100，系数0.5 → 折减后黄2.0；回落速率3.0仅在不折减时安全
        publishPolicy(zoneId, "crc-p1", 0, 4.0, 8.0, 100.0, 0.5, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "crc-r", T0.minusHours(24), T0.plusHours(26), 0.0);
        long chainId = prepareApprovableAssessment(zoneId, "crc", 3.0);

        Callable<String> correction = () -> {
            IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                    new RainfallReportRequest("crc-fix", T0.plusHours(10), 200.0, 1L,
                            T0.plusHours(26).plusMinutes(30)));
            return "CORRECTED_" + stored.status();
        };
        Callable<String> approval = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("crc-c2", "MONITORING_REVIEW",
                                T0.plusHours(27)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus() + "_" + e.getCode();
            }
        };

        List<String> results = race(correction, approval);
        assertEquals("CORRECTED_201", results.get(0), "雨量更正始终受理");
        String approvalResult = results.get(1);

        // 更正始终生效：当前值200.0、版本2
        assertEquals(200.0, jdbcTemplate.queryForObject(
                "SELECT rainfall_mm FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ? "
                        + "ORDER BY version DESC LIMIT 1", Double.class, zoneId,
                java.sql.Timestamp.valueOf(T0.plusHours(10))), 1e-9);

        List<ResumptionEvidenceInfo> evidence = slopeService.resumptionEvidence(chainId);
        if ("APPROVED".equals(approvalResult)) {
            // 批准先于更正提交：证据冻结的是更正前一致快照（雨量0.0、阈值4.0）
            assertEquals(2, evidence.size());
            assertEquals("APPROVE", evidence.get(1).phase());
            JsonNode detail = objectMapper.readTree(evidence.get(1).detail());
            assertEquals(0.0, detail.get("readings").get(0).get("rain24hMm").asDouble(), 1e-9);
            assertEquals(4.0, detail.get("readings").get(0).get("threshold").asDouble(), 1e-9);
            assertEquals("RESUMED", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
        } else {
            // 更正先提交：批准复评门禁失败（折减后阈值2.0，速率3.0超限），评估仍未批准
            assertEquals("HTTP_422_RATE_ABOVE_SAFETY_LINE", approvalResult,
                    "非批准即门禁拒绝，实际: " + approvalResult);
            assertEquals(1, evidence.size());
            assertEquals("APPLY", evidence.get(0).phase());
            assertEquals("APPLIED", jdbcTemplate.queryForObject(
                    "SELECT status FROM resumption_assessment WHERE chain_id = ?", String.class,
                    chainId));
            assertEquals("EXECUTING", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
        }
    }

    /** 更严策略发布与复工批准并发：变严先生效则旧安全计时作废，批准被拒绝。 */
    @Test
    @Timeout(60)
    void concurrentStricterPolicyPublishAndResumptionApproval() throws Exception {
        long zoneId = createZone("C-PP");
        publishPolicy(zoneId, "cpp-p1", 0, 4.0, 8.0, 1000.0, 1.0, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "cpp-r", T0.minusHours(24), T0.plusHours(26), 0.0);
        long chainId = prepareApprovableAssessment(zoneId, "cpp", 3.0);

        Callable<String> publish = () -> {
            IdempotencyService.Stored stored = slopeService.publishPolicy(zoneId,
                    new PolicyPublishRequest("cpp-p2", 1L, 3.5, 7.0, 1000.0, 1.0,
                            T0.plusHours(13), T0.plusHours(12)));
            return "PUBLISHED_" + readJson(stored).get("version").asLong();
        };
        Callable<String> approval = () -> {
            try {
                IdempotencyService.Stored stored = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest("cpp-c2", "MONITORING_REVIEW",
                                T0.plusHours(27)));
                return readJson(stored).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus() + "_" + e.getCode();
            }
        };

        List<String> results = race(publish, approval);
        assertEquals("PUBLISHED_2", results.get(0), "更严策略始终发布成功");
        String approvalResult = results.get(1);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_policy WHERE zone_id = ?", Integer.class, zoneId));

        if ("APPROVED".equals(approvalResult)) {
            // 批准先于发布提交：按v1快照批准并冻结证据
            assertEquals(2, slopeService.resumptionEvidence(chainId).size());
            assertEquals("RESUMED", jdbcTemplate.queryForObject(
                    "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
        } else {
            // 发布先提交：变严版本T0+13h生效，旧安全计时作废，批准被拒绝
            assertEquals("HTTP_422_RECOVERY_WINDOW_TOO_SHORT", approvalResult,
                    "非批准即窗口重计拒绝，实际: " + approvalResult);
            assertEquals("APPLIED", jdbcTemplate.queryForObject(
                    "SELECT status FROM resumption_assessment WHERE chain_id = ?", String.class,
                    chainId));
            assertEquals(1, slopeService.resumptionEvidence(chainId).size());
        }
    }

    /** 同键同参并发发布策略：唯一受理，重放方拿到首次冻结结果。 */
    @Test
    @Timeout(60)
    void concurrentIdenticalPolicyPublishAcceptedOnce() throws Exception {
        long zoneId = createZone("C-ID");
        PolicyPublishRequest request = new PolicyPublishRequest("cid-p1", 0L, 4.0, 8.0, 100.0,
                0.5, T0, T0);
        Callable<String> publishA = () -> {
            IdempotencyService.Stored stored = slopeService.publishPolicy(zoneId, request);
            return stored.status() + "|" + stored.body();
        };
        Callable<String> publishB = () -> {
            IdempotencyService.Stored stored = slopeService.publishPolicy(zoneId, request);
            return stored.status() + "|" + stored.body();
        };

        List<String> results = race(publishA, publishB);
        assertEquals(results.get(0), results.get(1), "并发同键同参返回同一冻结结果");
        assertTrue(results.get(0).startsWith("201|"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_policy WHERE zone_id = ?", Integer.class, zoneId),
                "并发同键仅受理一次");
    }

    /** 同小时雨量并发上报：同值并发唯一版本；异值并发仅一条受理，另一条要求显式更正。 */
    @Test
    @Timeout(60)
    void concurrentRainfallReportSameHour() throws Exception {
        long zoneId = createZone("C-RF");
        LocalDateTime hour = T0;

        Callable<String> sameA = () -> reportStatus(zoneId, "crf-a", hour, 5.0);
        Callable<String> sameB = () -> reportStatus(zoneId, "crf-b", hour, 5.0);
        List<String> sameResults = race(sameA, sameB);
        assertEquals(1, sameResults.stream().filter("201"::equals).count(),
                "同值并发仅一条首次受理，实际: " + sameResults);
        assertEquals(1, sameResults.stream().filter("200"::equals).count(),
                "另一条同值重放，实际: " + sameResults);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId));

        LocalDateTime hour2 = T0.plusHours(1);
        Callable<String> diffA = () -> reportStatus(zoneId, "crf-c", hour2, 7.0);
        Callable<String> diffB = () -> reportStatus(zoneId, "crf-d", hour2, 9.0);
        List<String> diffResults = race(diffA, diffB);
        assertEquals(1, diffResults.stream().filter("201"::equals).count(),
                "异值并发仅一条首次受理，实际: " + diffResults);
        assertEquals(1, diffResults.stream()
                        .filter(r -> r.equals("HTTP_409_RAINFALL_CORRECTION_REQUIRED")).count(),
                "另一条被要求显式更正，实际: " + diffResults);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ?",
                Integer.class, zoneId, java.sql.Timestamp.valueOf(hour2)),
                "异值并发不产生第二个版本");
    }

    private String reportStatus(long zoneId, String requestId, LocalDateTime hour, double value) {
        try {
            IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                    new RainfallReportRequest(requestId, hour, value, null, hour));
            return String.valueOf(stored.status());
        } catch (ApiException e) {
            return "HTTP_" + e.getStatus() + "_" + e.getCode();
        }
    }
}
