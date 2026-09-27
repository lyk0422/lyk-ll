package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.ArchiveRequest;
import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.PublishStrategyRequest;
import com.example.starter.slope.dto.ApiDtos.ReportRainfallRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雨量与有效阈值版本纳入复工判断的业务规则测试。
 * 覆盖：策略发布版本与校验、雨量上报/更正/重放、有效阈值查询、
 * 复工安全按每时刻有效阈值判定、缺点阻断、变严重新累计、变宽不撤链、
 * 批准前复评冻结证据、幂等重放与同键异参409。业务时间全部由请求报文控制。
 */
@SpringBootTest
class SlopeStrategyServiceTest {

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

    // ---------------------------------------------------------------
    // 测试辅助
    // ---------------------------------------------------------------

    private long createZone(String code) {
        IdempotencyService.Stored stored = slopeService.createZone(new CreateZoneRequest(
                "zone-" + code, code, "策略测区" + code, 5.0, 50.0, 2.0, 10));
        assertEquals(201, stored.status());
        return readJson(stored).get("zoneId").asLong();
    }

    private JsonNode readJson(IdempotencyService.Stored stored) {
        try {
            return objectMapper.readTree(stored.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode ingest(long zoneId, String requestId, LocalDateTime measuredAt, double displacement) {
        return readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(requestId, measuredAt, displacement)));
    }

    private JsonNode publishStrategy(long zoneId, String requestId, int expectedVersion,
                                     double yellow, double red, double rainfallThreshold,
                                     double factor, LocalDateTime effectiveFrom,
                                     LocalDateTime submittedAt, int expectedStatus) {
        IdempotencyService.Stored stored = slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest(requestId, expectedVersion, yellow, red,
                        rainfallThreshold, factor, effectiveFrom, submittedAt));
        assertEquals(expectedStatus, stored.status());
        return readJson(stored);
    }

    private JsonNode reportRainfall(long zoneId, String requestId, LocalDateTime hourStart,
                                    double rainfallMm, int expectedVersion, int expectedStatus) {
        IdempotencyService.Stored stored = slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest(requestId, hourStart, rainfallMm, expectedVersion));
        assertEquals(expectedStatus, stored.status());
        return readJson(stored);
    }

    /** 连续播种 [fromHour, hours) 的整点雨量，每小时 rainfallMm 毫米。 */
    private void seedRainfall(long zoneId, String tag, LocalDateTime fromHour, int hours,
                              double rainfallMm) {
        for (int i = 0; i < hours; i++) {
            reportRainfall(zoneId, tag + "-rf" + i, fromHour.plusHours(i), rainfallMm, 0, 201);
        }
    }

    /** 触发红色链并按时撤人：t0位移0，t0+24h位移120。 */
    private long redChainEvacuated(long zoneId, String tag) {
        ingest(zoneId, tag + "-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, tag + "-r1", T0.plusHours(24), 120.0);
        assertEquals("RED_CHAIN_CREATED", red.get("evaluation").asText());
        long chainId = red.get("chainId").asLong();
        IdempotencyService.Stored ev = slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(tag + "-ev", T0.plusHours(24).plusMinutes(10), 10));
        assertEquals(201, ev.status());
        return chainId;
    }

    // ---------------------------------------------------------------
    // 策略发布
    // ---------------------------------------------------------------

    @Test
    void strategyPublishVersionsFromOneAndIncrements() {
        long zoneId = createZone("S-V1");
        JsonNode v1 = publishStrategy(zoneId, "sv-1", 0, 3.0, 10.0, 100.0, 0.5,
                T0, T0, 201);
        assertEquals(1, v1.get("version").asInt());
        JsonNode v2 = publishStrategy(zoneId, "sv-2", 1, 2.5, 9.0, 80.0, 0.6,
                T0.plusHours(1), T0.plusHours(1), 201);
        assertEquals(2, v2.get("version").asInt());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId));
    }

    @Test
    void strategyPublishRejectsStaleExpectedVersion() {
        long zoneId = createZone("S-VC");
        publishStrategy(zoneId, "svc-1", 0, 3.0, 10.0, 100.0, 0.5, T0, T0, 201);
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("svc-2", 0, 2.0, 8.0, 90.0, 0.5,
                        T0.plusHours(1), T0.plusHours(1))));
        assertEquals(409, ex.getStatus());
        assertEquals("VERSION_CONFLICT", ex.getCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId),
                "版本冲突不落库");
    }

    @Test
    void strategyPublishRejectsInvalidThresholdOrderAndEffectiveTime() {
        long zoneId = createZone("S-ORD");
        ApiException order = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("so-1", 0, 10.0, 3.0, 100.0, 0.5, T0, T0)));
        assertEquals(422, order.getStatus());
        assertEquals("THRESHOLD_ORDER_INVALID", order.getCode(), "须满足0<黄<红");

        ApiException effective = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("so-2", 0, 3.0, 10.0, 100.0, 0.5,
                        T0.minusHours(1), T0)));
        assertEquals(422, effective.getStatus());
        assertEquals("EFFECTIVE_BEFORE_SUBMISSION", effective.getCode(), "生效时刻不得早于提交时刻");

        ApiException nonFinite = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("so-3", 0, 3.0, 10.0, Double.POSITIVE_INFINITY, 0.5,
                        T0, T0)));
        assertEquals(400, nonFinite.getStatus());
        assertEquals("NON_FINITE_VALUE", nonFinite.getCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId));
    }

    @Test
    void strategyPublishMissingZoneReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.publishStrategy(999999,
                new PublishStrategyRequest("s404", 0, 3.0, 10.0, 100.0, 0.5, T0, T0)));
        assertEquals(404, ex.getStatus());
        assertEquals("ZONE_NOT_FOUND", ex.getCode());
    }

    // ---------------------------------------------------------------
    // 雨量小时数据
    // ---------------------------------------------------------------

    @Test
    void rainfallReportReplayAndCorrectionVersions() {
        long zoneId = createZone("S-RF");
        LocalDateTime hour = LocalDateTime.of(2026, 9, 1, 10, 0);
        JsonNode created = reportRainfall(zoneId, "rf-1", hour, 12.5, 0, 201);
        assertEquals(1, created.get("version").asInt());
        assertFalse(created.get("replayed").asBoolean());

        // 同测区同小时同值重放：返回既有记录，不产生新版本
        JsonNode replay = reportRainfall(zoneId, "rf-2", hour, 12.5, 0, 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(1, replay.get("version").asInt());

        // 异值须显式更正版本
        ApiException stale = assertThrows(ApiException.class, () -> slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest("rf-3", hour, 20.0, 0)));
        assertEquals(409, stale.getStatus());
        assertEquals("VERSION_CONFLICT", stale.getCode());

        JsonNode corrected = reportRainfall(zoneId, "rf-4", hour, 20.0, 1, 200);
        assertTrue(corrected.get("corrected").asBoolean());
        assertEquals(2, corrected.get("version").asInt());
        assertEquals(20.0, jdbcTemplate.queryForObject(
                "SELECT rainfall_mm FROM rainfall_hourly WHERE zone_id = ?", Double.class, zoneId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId),
                "更正不产生新行");
    }

    @Test
    void rainfallRejectsNonAlignedHourAndNonFiniteValue() {
        long zoneId = createZone("S-RB");
        ApiException aligned = assertThrows(ApiException.class, () -> slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest("rb-1", LocalDateTime.of(2026, 9, 1, 10, 30), 1.0, 0)));
        assertEquals(400, aligned.getStatus());
        assertEquals("RAINFALL_HOUR_NOT_ALIGNED", aligned.getCode());

        ApiException infinite = assertThrows(ApiException.class, () -> slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest("rb-2", LocalDateTime.of(2026, 9, 1, 10, 0),
                        Double.POSITIVE_INFINITY, 0)));
        assertEquals(400, infinite.getStatus());
        assertEquals("NON_FINITE_VALUE", infinite.getCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId));
    }

    // ---------------------------------------------------------------
    // 有效阈值查询
    // ---------------------------------------------------------------

    @Test
    void effectiveThresholdFallsBackToLegacyWithoutStrategy() {
        long zoneId = createZone("S-EL");
        EffectiveThresholdResponse response = slopeService.effectiveThreshold(zoneId, T0);
        assertNull(response.strategyVersion());
        assertEquals(2.0, response.legacySafetyHourlyRate());
        assertNull(response.effectiveSafetyRate());
    }

    @Test
    void effectiveThresholdAppliesReductionExactlyAtRainfallThreshold() {
        long zoneId = createZone("S-ER");
        publishStrategy(zoneId, "ser-1", 0, 3.0, 10.0, 24.0, 0.5, T0, T0, 201);
        // 24个完整小时每小时1.0毫米，合计恰达门槛24.0 → 启用折减
        seedRainfall(zoneId, "ser", T0, 24, 1.0);
        EffectiveThresholdResponse response = slopeService.effectiveThreshold(zoneId, T0.plusHours(24));
        assertEquals(1, response.strategyVersion());
        assertTrue(response.rainfallDataComplete());
        assertEquals(24.0, response.rainfall24hSum(), 1e-9);
        assertTrue(response.reductionApplied(), "雨量恰达门槛启用折减");
        assertEquals(1.5, response.effectiveSafetyRate(), 1e-9);
        assertEquals(1.5, response.effectiveYellowRate(), 1e-9);
        assertEquals(5.0, response.effectiveRedRate(), 1e-9);
    }

    @Test
    void effectiveThresholdReportsMissingHoursWithoutTreatingAsZero() {
        long zoneId = createZone("S-EM");
        publishStrategy(zoneId, "sem-1", 0, 3.0, 10.0, 100.0, 0.5, T0, T0, 201);
        // 缺一个小时：只播种23小时
        seedRainfall(zoneId, "sem", T0, 23, 1.0);
        EffectiveThresholdResponse response = slopeService.effectiveThreshold(zoneId, T0.plusHours(24));
        assertFalse(response.rainfallDataComplete());
        assertEquals(1, response.missingHours().size());
        assertEquals(T0.plusHours(23), response.missingHours().get(0));
        assertNull(response.rainfall24hSum(), "缺点时合计置空，不得当零");
        assertFalse(response.reductionApplied());
    }

    // ---------------------------------------------------------------
    // 复工评估：策略+雨量
    // ---------------------------------------------------------------

    /** 标准策略场景：黄3.0/红10.0/门槛100/系数0.5，T0生效；雨量每小时1.0（合计24，不折减）。 */
    private long strategyZoneWithRainfall(String code, String tag) {
        long zoneId = createZone(code);
        publishStrategy(zoneId, tag + "-st", 0, 3.0, 10.0, 100.0, 0.5, T0, T0, 201);
        seedRainfall(zoneId, tag, T0.minusHours(24), 96, 1.0);
        return zoneId;
    }

    @Test
    void resumptionWithStrategyFreezesEvidence() {
        long zoneId = strategyZoneWithRainfall("S-RM", "srm");
        long chainId = redChainEvacuated(zoneId, "srm");
        ingest(zoneId, "srm-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "srm-r3", T0.plusHours(48), 122.0);

        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("srm-a1", T0.plusHours(48)));
        assertEquals(201, applied.status());

        List<ResumptionEvidenceItem> evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(1, evidence.size());
        ResumptionEvidenceItem item = evidence.get(0);
        assertEquals(T0.plusHours(48), item.evaluatedAt());
        assertEquals(1, item.strategyVersion());
        assertEquals(24.0, item.rainfall24hSum(), 1e-9);
        assertFalse(item.reductionApplied());
        assertEquals(3.0, item.safetyThreshold(), 1e-9, "未折减时取黄色基础阈值");
        assertEquals(2, item.readingCount());
        assertTrue(item.detail().contains("\"passed\":true"));
    }

    @Test
    void resumptionBlockedWhenRainfallHourMissing() {
        long zoneId = createZone("S-RX");
        publishStrategy(zoneId, "srx-st", 0, 3.0, 10.0, 100.0, 0.5, T0, T0, 201);
        // 24个完整小时中缺一个小时（只播种23个）
        seedRainfall(zoneId, "srx", T0.plusHours(24), 23, 1.0);
        long chainId = redChainEvacuated(zoneId, "srx");
        ingest(zoneId, "srx-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "srx-r3", T0.plusHours(48), 122.0);

        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("srx-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("RAINFALL_DATA_INSUFFICIENT", ex.getCode(), "任一缺点阻断新复工批准");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_assessment WHERE chain_id = ?", Integer.class, chainId));
    }

    @Test
    void resumptionWithoutStrategyKeepsLegacySafetyLine() {
        long zoneId = createZone("S-LG");
        long chainId = redChainEvacuated(zoneId, "slg");
        ingest(zoneId, "slg-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "slg-r3", T0.plusHours(48), 122.0);
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("slg-a1", T0.plusHours(48)));
        assertEquals(201, applied.status(), "无策略版本走旧线，不要求雨量数据");
        List<ResumptionEvidenceItem> evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(1, evidence.size());
        assertNull(evidence.get(0).strategyVersion());
        assertNull(evidence.get(0).rainfall24hSum());
        assertEquals(2.0, evidence.get(0).safetyThreshold(), 1e-9);
    }

    @Test
    void reductionAtThresholdAppliesAndRateBelowReducedLinePasses() {
        long zoneId = createZone("S-RD");
        // 门槛24.0，每小时1.0 → 合计恰达门槛 → 折减系数0.5 → 有效安全阈值1.5
        publishStrategy(zoneId, "srd-st", 0, 3.0, 10.0, 24.0, 0.5, T0, T0, 201);
        seedRainfall(zoneId, "srd", T0.minusHours(24), 96, 1.0);
        long chainId = redChainEvacuated(zoneId, "srd");
        // 窗口读数小时速率1.4，严格低于折减后阈值1.5
        ingest(zoneId, "srd-r2", T0.plusHours(36), 136.8);
        ingest(zoneId, "srd-r3", T0.plusHours(48), 153.6);
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("srd-a1", T0.plusHours(48)));
        assertEquals(201, applied.status());
        ResumptionEvidenceItem item = slopeService.resumptionEvidence(chainId).get(0);
        assertTrue(item.reductionApplied(), "雨量恰达门槛启用折减");
        assertEquals(1.5, item.safetyThreshold(), 1e-9);
    }

    @Test
    void rateExactlyEqualSafetyLineRejectedAsBefore() {
        long zoneId = createZone("S-EQ");
        publishStrategy(zoneId, "seq-st", 0, 3.0, 10.0, 24.0, 0.5, T0, T0, 201);
        seedRainfall(zoneId, "seq", T0.minusHours(24), 96, 1.0);
        long chainId = redChainEvacuated(zoneId, "seq");
        // 小时速率恰等折减后安全阈值1.5：安全线沿用原规则，须严格低于
        ingest(zoneId, "seq-r2", T0.plusHours(36), 138.0);
        ingest(zoneId, "seq-r3", T0.plusHours(48), 156.0);
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("seq-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", ex.getCode());
    }

    @Test
    void reductionRejectsRateAboveReducedThreshold() {
        long zoneId = createZone("S-RJ");
        publishStrategy(zoneId, "srj-st", 0, 3.0, 10.0, 24.0, 0.5, T0, T0, 201);
        seedRainfall(zoneId, "srj", T0.minusHours(24), 96, 1.0);
        long chainId = redChainEvacuated(zoneId, "srj");
        // 小时速率1.6 > 折减后阈值1.5（但低于基础阈值3.0）
        ingest(zoneId, "srj-r2", T0.plusHours(36), 139.2);
        ingest(zoneId, "srj-r3", T0.plusHours(48), 158.4);
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("srj-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", ex.getCode(), "折减后按更严阈值判定");
    }

    @Test
    void stricterStrategyRestartsSafetyAccumulation() {
        long zoneId = strategyZoneWithRainfall("S-ST", "sst");
        long chainId = redChainEvacuated(zoneId, "sst");
        ingest(zoneId, "sst-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "sst-r3", T0.plusHours(48), 122.0);
        // 变严版本 T0+40h 生效：黄色阈值3.0→2.5
        publishStrategy(zoneId, "sst-st2", 1, 2.5, 9.0, 100.0, 0.5,
                T0.plusHours(40), T0.plusHours(39), 201);

        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("sst-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("SAFETY_WINDOW_RESTARTED", ex.getCode(), "变严后不沿用旧安全计时");

        // 变严生效后重新累计满24小时即可申请
        ingest(zoneId, "sst-r4", T0.plusHours(60), 123.0);
        ingest(zoneId, "sst-r5", T0.plusHours(64), 124.0);
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("sst-a2", T0.plusHours(64)));
        assertEquals(201, applied.status());
        ResumptionEvidenceItem item = slopeService.resumptionEvidence(chainId).get(0);
        assertEquals(T0.plusHours(40), item.windowStart(), "窗口自变严生效时刻重新累计");
        assertEquals(2, item.strategyVersion());
    }

    @Test
    void looserStrategyDoesNotRevokeExecutingRedChain() {
        long zoneId = strategyZoneWithRainfall("S-LS", "sls");
        long chainId = redChainEvacuated(zoneId, "sls");
        // 变宽版本：阈值与系数均放宽
        publishStrategy(zoneId, "sls-st2", 1, 9.0, 20.0, 200.0, 1.0,
                T0.plusHours(30), T0.plusHours(30), 201);
        var chain = slopeService.chainDetail(chainId);
        assertEquals("RED", chain.level());
        assertEquals("EXECUTING", chain.status(), "变宽不撤销既有红色处置链");
        assertEquals("LOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
        assertNotNull(chain.evacuatedAt(), "撤人证据不受影响");
    }

    @Test
    void strategyPublishDoesNotRecomputeArchivedEvidence() {
        long zoneId = createZone("S-AR");
        ingest(zoneId, "sar-r0", T0, 0.0);
        JsonNode yellow = ingest(zoneId, "sar-r1", T0.plusHours(1), 6.0);
        long chainId = yellow.get("chainId").asLong();
        slopeService.archiveChain(chainId, new ArchiveRequest("sar-arc", T0.plusHours(2)));

        publishStrategy(zoneId, "sar-st", 0, 0.1, 0.2, 1.0, 0.1, T0, T0, 201);
        var chain = slopeService.chainDetail(chainId);
        assertEquals("ARCHIVED", chain.status(), "已归档告警不被重算");
        assertEquals("YELLOW", chain.level(), "归档级别保持原值");
        assertEquals(T0.plusHours(2), chain.archivedAt());
    }

    // ---------------------------------------------------------------
    // 批准前复评与证据冻结
    // ---------------------------------------------------------------

    @Test
    void approvalReevaluatesAndBlocksAfterRainfallCorrection() {
        long zoneId = createZone("S-AP");
        publishStrategy(zoneId, "sap-st", 0, 3.0, 10.0, 100.0, 0.5, T0, T0, 201);
        seedRainfall(zoneId, "sap", T0.minusHours(24), 96, 1.0);
        long chainId = redChainEvacuated(zoneId, "sap");
        // 小时速率2.0：低于未折减阈值3.0，但高于折减后阈值1.5
        ingest(zoneId, "sap-r2", T0.plusHours(36), 144.0);
        ingest(zoneId, "sap-r3", T0.plusHours(48), 168.0);
        slopeService.applyResumption(chainId, new ResumptionApplyRequest("sap-a1", T0.plusHours(48)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("sap-c1", "SITE_CHECK", T0.plusHours(49)));

        // 申请后雨量更正：某小时改为100毫米 → 合计123≥100 → 启用折减 → 阈值1.5
        LocalDateTime correctedHour = T0.plusHours(26);
        reportRainfall(zoneId, "sap-fix", correctedHour, 100.0, 1, 200);

        ApiException ex = assertThrows(ApiException.class, () -> slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("sap-c2", "MONITORING_REVIEW", T0.plusHours(50))));
        assertEquals(422, ex.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", ex.getCode(), "批准前复评：更正后折减生效，阻断批准");
        assertEquals("APPLIED", jdbcTemplate.queryForObject(
                "SELECT status FROM resumption_assessment WHERE chain_id = ?", String.class, chainId),
                "未批准，双确认进度保留");

        // 更正回低雨量后仍可按原双确认流程批准
        reportRainfall(zoneId, "sap-fix2", correctedHour, 1.0, 2, 200);
        IdempotencyService.Stored approved = slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("sap-c3", "MONITORING_REVIEW", T0.plusHours(51)));
        assertEquals(201, approved.status());
        assertEquals("APPROVED", readJson(approved).get("assessmentStatus").asText());

        List<ResumptionEvidenceItem> evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(2, evidence.size(), "申请与批准各冻结一条证据");
        assertEquals(T0.plusHours(48), evidence.get(0).evaluatedAt());
        assertEquals(T0.plusHours(51), evidence.get(1).evaluatedAt());
        assertFalse(evidence.get(1).reductionApplied());
    }

    // ---------------------------------------------------------------
    // 幂等
    // ---------------------------------------------------------------

    @Test
    void strategyPublishIdempotentReplayAndConflict() {
        long zoneId = createZone("S-IM");
        IdempotencyService.Stored first = slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("sim-1", 0, 3.0, 10.0, 100.0, 0.5, T0, T0));
        IdempotencyService.Stored replay = slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("sim-1", 0, 3.0, 10.0, 100.0, 0.5, T0, T0));
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body(), "同键同参重放首次冻结结果");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId));

        ApiException ex = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("sim-1", 0, 4.0, 10.0, 100.0, 0.5, T0, T0)));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode(), "同键异参409");
    }

    @Test
    void rainfallFailureDoesNotOccupyRequestId() {
        long zoneId = createZone("S-FK");
        // 失败请求（版本冲突）不占键：同一requestId可携带正确参数再次受理
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest("sfk-1", LocalDateTime.of(2026, 9, 1, 10, 0), 5.0, 3)));
        assertEquals(409, ex.getStatus());
        JsonNode ok = reportRainfall(zoneId, "sfk-1", LocalDateTime.of(2026, 9, 1, 10, 0), 5.0, 0, 201);
        assertEquals(1, ok.get("version").asInt());
    }

    @Test
    void fingerprintDistinguishesSeparatorCharactersInRequestId() {
        long zoneId = createZone("S-FP");
        LocalDateTime hour = LocalDateTime.of(2026, 9, 1, 10, 0);
        // requestId 含换行与引号等分隔符：结构化指纹须区分，不得误判为同参重放
        JsonNode first = reportRainfall(zoneId, "fp\n\"1", hour, 5.0, 0, 201);
        assertEquals(1, first.get("version").asInt());
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest("fp\n\"1", hour, 6.0, 0)));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode(), "同键异参仍须409");
        IdempotencyService.Stored replay = slopeService.reportRainfall(zoneId,
                new ReportRainfallRequest("fp\n\"1", hour, 5.0, 0));
        assertEquals(201, replay.status(), "同键同参正常重放");
    }
}
