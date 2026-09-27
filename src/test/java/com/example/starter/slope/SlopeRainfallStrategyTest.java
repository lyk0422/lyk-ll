package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成雨量小时数据、阈值策略版本与复工安全评价的 business 规则测试。
 * 覆盖：整点唯一与同值重放、异值显式更正版本、策略校验与版本递增、有效阈值查询、
 * 策略变严重置安全计时、雨量缺点阻断复工、恰达门槛启用折减、变宽不撤链、
 * 批准前复核冻结一致证据、幂等重放与同键异参409。业务时间全部由请求报文控制。
 */
@SpringBootTest
class SlopeRainfallStrategyTest {

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
                "zone-" + code, code, "雨量策略测区" + code, 5.0, 50.0, 2.0, 10));
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
        IdempotencyService.Stored stored = slopeService.ingestReading(zoneId,
                new IngestReadingRequest(requestId, measuredAt, displacement));
        assertEquals(201, stored.status());
        return readJson(stored);
    }

    /** 构造红色预警场景：t0位移0，t0+24h位移120（日速率120>50）。返回chainId。 */
    private long triggerRedChain(long zoneId, String tag) {
        ingest(zoneId, tag + "-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, tag + "-r1", T0.plusHours(24), 120.0);
        assertEquals("RED_CHAIN_CREATED", red.get("evaluation").asText());
        return red.get("chainId").asLong();
    }

    private void confirmEvacuation(long chainId, String requestId, LocalDateTime confirmedAt) {
        IdempotencyService.Stored stored = slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(requestId, confirmedAt, 10));
        assertEquals(201, stored.status());
    }

    private JsonNode submitRainfall(long zoneId, String requestId, LocalDateTime hourStart,
                                    double rainfallMm, int expectedVersion, int expectedStatus) {
        IdempotencyService.Stored stored = slopeService.submitRainfallHour(zoneId,
                new RainfallHourRequest(requestId, hourStart, rainfallMm, expectedVersion));
        assertEquals(expectedStatus, stored.status());
        return readJson(stored);
    }

    /** 连续插入hours个小时雨量（整点，version=1）。 */
    private void insertRainfall(long zoneId, String tag, LocalDateTime fromHour, int hours, double value) {
        for (int i = 0; i < hours; i++) {
            submitRainfall(zoneId, tag + "-" + i, fromHour.plusHours(i), value, 0, 201);
        }
    }

    private JsonNode publishStrategy(long zoneId, String requestId, int expectedVersion,
                                     double yellow, double red, double threshold, double factor,
                                     LocalDateTime submittedAt, LocalDateTime effectiveFrom,
                                     int expectedStatus) {
        IdempotencyService.Stored stored = slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest(requestId, expectedVersion, yellow, red, threshold,
                        factor, submittedAt, effectiveFrom));
        assertEquals(expectedStatus, stored.status());
        return readJson(stored);
    }

    // ---------------------------------------------------------------
    // 雨量小时数据：整点唯一、同值重放、异值显式更正
    // ---------------------------------------------------------------

    @Test
    void rainfallHourCreateReplayAndCorrectionVersions() {
        long zoneId = createZone("R-CRUD");
        JsonNode created = submitRainfall(zoneId, "c1", T0, 1.5, 0, 201);
        assertEquals(1, created.get("version").asInt(), "版本从1开始");
        assertFalse(created.get("duplicated").asBoolean());

        // 同测区同小时同值重放（新requestId）：返回已有记录，不变更版本
        JsonNode duplicated = submitRainfall(zoneId, "c2", T0, 1.5, 0, 200);
        assertTrue(duplicated.get("duplicated").asBoolean());
        assertEquals(1, duplicated.get("version").asInt());

        // 异值未携带更正版本 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> slopeService.submitRainfallHour(
                zoneId, new RainfallHourRequest("c3", T0, 2.0, 0)));
        assertEquals(409, mismatch.getStatus());
        assertEquals("RAINFALL_VALUE_MISMATCH", mismatch.getCode());

        // 更正版本不匹配 → 409
        ApiException versionConflict = assertThrows(ApiException.class, () -> slopeService
                .submitRainfallHour(zoneId, new RainfallHourRequest("c4", T0, 2.0, 2)));
        assertEquals(409, versionConflict.getStatus());
        assertEquals("VERSION_MISMATCH", versionConflict.getCode());

        // 显式更正：expectedVersion=1，成功后版本加1
        JsonNode corrected = submitRainfall(zoneId, "c5", T0, 2.0, 1, 200);
        assertEquals(2, corrected.get("version").asInt(), "成功变更加1");
        assertEquals(2.0, corrected.get("rainfallMm").asDouble(), 1e-9);

        // 更正不存在的小时 → 404
        ApiException notFound = assertThrows(ApiException.class, () -> slopeService.submitRainfallHour(
                zoneId, new RainfallHourRequest("c6", T0.plusHours(1), 1.0, 1)));
        assertEquals(404, notFound.getStatus());
        assertEquals("RAINFALL_HOUR_NOT_FOUND", notFound.getCode());

        // 测区不存在 → 404
        ApiException zoneMissing = assertThrows(ApiException.class, () -> slopeService
                .submitRainfallHour(999999, new RainfallHourRequest("c7", T0, 1.0, 0)));
        assertEquals(404, zoneMissing.getStatus());
        assertEquals("ZONE_NOT_FOUND", zoneMissing.getCode());
    }

    @Test
    void rainfallHourRejectsInvalidParams() {
        long zoneId = createZone("R-VALID");
        // 非整点 → 400
        ApiException notAligned = assertThrows(ApiException.class, () -> slopeService
                .submitRainfallHour(zoneId, new RainfallHourRequest("v1", T0.plusMinutes(30), 1.0, 0)));
        assertEquals(400, notAligned.getStatus());
        assertEquals("HOUR_NOT_ALIGNED", notAligned.getCode());
        // 负值 → 400
        ApiException negative = assertThrows(ApiException.class, () -> slopeService
                .submitRainfallHour(zoneId, new RainfallHourRequest("v2", T0, -0.5, 0)));
        assertEquals(400, negative.getStatus());
        assertEquals("RAINFALL_INVALID", negative.getCode());
        // NaN → 400（有限非负）
        ApiException nan = assertThrows(ApiException.class, () -> slopeService
                .submitRainfallHour(zoneId, new RainfallHourRequest("v3", T0, Double.NaN, 0)));
        assertEquals(400, nan.getStatus());
        // 无穷 → 400
        ApiException infinite = assertThrows(ApiException.class, () -> slopeService
                .submitRainfallHour(zoneId, new RainfallHourRequest("v4", T0, Double.POSITIVE_INFINITY, 0)));
        assertEquals(400, infinite.getStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId),
                "非法参数不落库");
    }

    @Test
    void rainfallIdempotencyReplayConflictAndFailureNotOccupyingKey() {
        long zoneId = createZone("R-IDM");
        IdempotencyService.Stored first = slopeService.submitRainfallHour(zoneId,
                new RainfallHourRequest("idm-1", T0, 1.5, 0));
        IdempotencyService.Stored replay = slopeService.submitRainfallHour(zoneId,
                new RainfallHourRequest("idm-1", T0, 1.5, 0));
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body(), "同键同参重放首次冻结结果");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId));

        // 同键异参 → 409，且业务状态不变
        ApiException conflict = assertThrows(ApiException.class, () -> slopeService.submitRainfallHour(
                zoneId, new RainfallHourRequest("idm-1", T0.plusHours(1), 1.5, 0)));
        assertEquals(409, conflict.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId));

        // 失败不占键：参数非法的请求失败后，同键可重新受理合法参数
        assertThrows(ApiException.class, () -> slopeService.submitRainfallHour(zoneId,
                new RainfallHourRequest("idm-2", T0.plusMinutes(30), 1.0, 0)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idm-2'", Integer.class),
                "失败请求不占幂等键");
        IdempotencyService.Stored ok = slopeService.submitRainfallHour(zoneId,
                new RainfallHourRequest("idm-2", T0.plusHours(1), 1.0, 0));
        assertEquals(201, ok.status());
    }

    // ---------------------------------------------------------------
    // 策略发布：校验、版本递增、幂等
    // ---------------------------------------------------------------

    @Test
    void strategyPublishValidationAndVersioning() {
        long zoneId = createZone("R-STRAT");
        // 须满足0<黄<红
        ApiException order = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b1", 0, 50.0, 5.0, 24.0, 0.5, T0, T0)));
        assertEquals(400, order.getStatus());
        assertEquals("THRESHOLD_ORDER_INVALID", order.getCode());
        ApiException equal = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b2", 0, 5.0, 5.0, 24.0, 0.5, T0, T0)));
        assertEquals(400, equal.getStatus());
        // 折减系数须满足0<系数<=1
        ApiException factorZero = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b3", 0, 4.0, 40.0, 24.0, 0.0, T0, T0)));
        assertEquals(400, factorZero.getStatus());
        assertEquals("REDUCTION_FACTOR_INVALID", factorZero.getCode());
        ApiException factorBig = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b4", 0, 4.0, 40.0, 24.0, 1.5, T0, T0)));
        assertEquals(400, factorBig.getStatus());
        // 雨量门槛有限非负
        ApiException threshold = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b5", 0, 4.0, 40.0, -1.0, 0.5, T0, T0)));
        assertEquals(400, threshold.getStatus());
        assertEquals("RAINFALL_THRESHOLD_INVALID", threshold.getCode());
        // 生效时刻不得早于提交时刻 → 422
        ApiException early = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b6", 0, 4.0, 40.0, 24.0, 0.5, T0.plusHours(2), T0.plusHours(1))));
        assertEquals(422, early.getStatus());
        assertEquals("EFFECTIVE_BEFORE_SUBMISSION", early.getCode());
        // 版本不匹配 → 409
        ApiException version = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-b7", 3, 4.0, 40.0, 24.0, 0.5, T0, T0)));
        assertEquals(409, version.getStatus());
        assertEquals("VERSION_MISMATCH", version.getCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId),
                "非法与冲突请求不落库");

        // 正常发布：版本从1开始，成功变更加1；系数恰为1、门槛恰为0均合法
        JsonNode v1 = publishStrategy(zoneId, "s-1", 0, 4.0, 40.0, 24.0, 0.5, T0, T0, 201);
        assertEquals(1, v1.get("version").asInt());
        JsonNode v2 = publishStrategy(zoneId, "s-2", 1, 6.0, 60.0, 0.0, 1.0,
                T0.plusHours(1), T0.plusHours(1), 201);
        assertEquals(2, v2.get("version").asInt());
        assertEquals(2, slopeService.listStrategies(zoneId).size());

        // 幂等：同键同参重放首次结果；同键异参409
        IdempotencyService.Stored replay = slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-1", 0, 4.0, 40.0, 24.0, 0.5, T0, T0));
        assertEquals(201, replay.status());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_strategy WHERE zone_id = ?", Integer.class, zoneId),
                "重放不产生新版本");
        ApiException conflict = assertThrows(ApiException.class, () -> slopeService.publishStrategy(zoneId,
                new PublishStrategyRequest("s-1", 0, 4.0, 41.0, 24.0, 0.5, T0, T0)));
        assertEquals(409, conflict.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }

    // ---------------------------------------------------------------
    // 有效阈值查询
    // ---------------------------------------------------------------

    @Test
    void effectiveThresholdsReflectStrategyAndRainfall() {
        long zoneId = createZone("R-TH");
        // 无策略：测区静态阈值，不要求雨量
        EffectiveThresholdResponse none = slopeService.effectiveThresholds(zoneId, T0);
        assertNull(none.strategyVersion());
        assertEquals(5.0, none.yellowHourlyRate(), 1e-9);
        assertEquals(50.0, none.redDailyRate(), 1e-9);
        assertEquals(2.0, none.safetyHourlyRate(), 1e-9);
        assertTrue(none.rainfallComplete());
        assertFalse(none.reductionApplied());

        publishStrategy(zoneId, "th-s1", 0, 4.0, 40.0, 24.0, 0.5, T0, T0.plusHours(10), 201);
        // 策略生效前时刻仍用静态阈值
        EffectiveThresholdResponse before = slopeService.effectiveThresholds(zoneId, T0.plusHours(9));
        assertNull(before.strategyVersion());
        // 雨量缺点：有效阈值不可定，不得当零
        EffectiveThresholdResponse incomplete = slopeService.effectiveThresholds(zoneId, T0.plusHours(40));
        assertEquals(1, incomplete.strategyVersion());
        assertFalse(incomplete.rainfallComplete());
        assertNull(incomplete.yellowHourlyRate());
        assertNull(incomplete.safetyHourlyRate());

        // 补齐前24个完整小时雨量且恰达门槛 → 启用折减
        insertRainfall(zoneId, "th-rain", T0.plusHours(16), 24, 1.0);
        EffectiveThresholdResponse reduced = slopeService.effectiveThresholds(zoneId, T0.plusHours(40));
        assertTrue(reduced.rainfallComplete());
        assertTrue(reduced.reductionApplied(), "雨量恰达门槛启用折减");
        assertEquals(24.0, reduced.rainfall24hMm(), 1e-9);
        assertEquals(2.0, reduced.yellowHourlyRate(), 1e-9);
        assertEquals(20.0, reduced.redDailyRate(), 1e-9);
        assertEquals(1.0, reduced.safetyHourlyRate(), 1e-9);

        // 更正一小时使累计略低于门槛 → 不折减
        submitRainfall(zoneId, "th-fix", T0.plusHours(16), 0.5, 1, 200);
        EffectiveThresholdResponse below = slopeService.effectiveThresholds(zoneId, T0.plusHours(40));
        assertEquals(23.5, below.rainfall24hMm(), 1e-9);
        assertFalse(below.reductionApplied());
        assertEquals(4.0, below.yellowHourlyRate(), 1e-9);
        assertEquals(2.0, below.safetyHourlyRate(), 1e-9);
    }

    // ---------------------------------------------------------------
    // 复工安全评价：策略变严重置、雨量缺点阻断、恰达门槛折减
    // ---------------------------------------------------------------

    @Test
    void stricterStrategyResetsSafetyTimer() {
        long zoneId = createZone("R-RESET");
        long chainId = triggerRedChain(zoneId, "reset");
        confirmEvacuation(chainId, "reset-ev", T0.plusHours(24).plusMinutes(10));
        ingest(zoneId, "reset-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "reset-r3", T0.plusHours(48), 122.0);

        // 变严策略（黄4.0<5.0）生效于T0+40h，晚于触发时刻T0+24h
        publishStrategy(zoneId, "reset-s1", 0, 4.0, 50.0, 100.0, 1.0,
                T0.plusHours(30), T0.plusHours(40), 201);

        // 不沿用旧安全计时：T0+48h申请被拒绝，须自生效时刻重新累计24小时
        ApiException tooShort = assertThrows(ApiException.class, () -> slopeService.applyResumption(
                chainId, new ResumptionApplyRequest("reset-a1", T0.plusHours(48))));
        assertEquals(422, tooShort.getStatus());
        assertEquals("RECOVERY_WINDOW_TOO_SHORT", tooShort.getCode());

        // 自生效时刻重新累计：T0+64h（生效后恰满24小时）申请，窗口内读数与雨量齐备
        ingest(zoneId, "reset-r4", T0.plusHours(64), 122.5);
        insertRainfall(zoneId, "reset-rain", T0.plusHours(24), 40, 1.0);
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("reset-a2", T0.plusHours(64)));
        assertEquals(201, applied.status());
        List<ResumptionEvidenceItem> evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(1, evidence.size());
        assertEquals(T0.plusHours(40), evidence.get(0).windowStart(),
                "安全计时自变严版本生效时刻重新累计");
        assertEquals(2, evidence.get(0).readingCount(), "生效前读数不计入新窗口");
    }

    @Test
    void missingRainfallBlocksResumption() {
        long zoneId = createZone("R-GAP");
        long chainId = triggerRedChain(zoneId, "gap");
        confirmEvacuation(chainId, "gap-ev", T0.plusHours(24).plusMinutes(10));
        ingest(zoneId, "gap-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "gap-r3", T0.plusHours(48), 122.0);
        // 策略生效于触发前，不重置计时，但评价需要雨量
        publishStrategy(zoneId, "gap-s1", 0, 5.0, 50.0, 100.0, 1.0,
                T0.plusHours(10), T0.plusHours(10), 201);
        // 雨量缺一个小时（T0+30h缺失），不得当零
        insertRainfall(zoneId, "gap-rain-a", T0.plusHours(12), 18, 1.0);
        insertRainfall(zoneId, "gap-rain-b", T0.plusHours(31), 17, 1.0);
        ApiException insufficient = assertThrows(ApiException.class, () -> slopeService.applyResumption(
                chainId, new ResumptionApplyRequest("gap-a1", T0.plusHours(48))));
        assertEquals(422, insufficient.getStatus());
        assertEquals("INSUFFICIENT_RAINFALL_DATA", insufficient.getCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_assessment WHERE chain_id = ?", Integer.class, chainId),
                "数据不足阻断新复工批准");

        // 补齐缺点后放行
        submitRainfall(zoneId, "gap-fill", T0.plusHours(30), 1.0, 0, 201);
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("gap-a2", T0.plusHours(48)));
        assertEquals(201, applied.status());
    }

    @Test
    void rainfallExactlyAtThresholdEnablesReduction() {
        // 场景A：24小时雨量恰达门槛 → 折减生效，安全线2.0×0.5=1.0，速率1.5判不安全
        long zoneA = createZone("R-RED-A");
        long chainA = triggerRedChain(zoneA, "ra");
        confirmEvacuation(chainA, "ra-ev", T0.plusHours(24).plusMinutes(10));
        publishStrategy(zoneA, "ra-s1", 0, 5.0, 50.0, 24.0, 0.5,
                T0.plusHours(10), T0.plusHours(10), 201);
        ingest(zoneA, "ra-r2", T0.plusHours(36), 121.0);
        ingest(zoneA, "ra-r3", T0.plusHours(48), 139.0);
        insertRainfall(zoneA, "ra-rain", T0.plusHours(12), 36, 1.0);
        ApiException above = assertThrows(ApiException.class, () -> slopeService.applyResumption(
                chainA, new ResumptionApplyRequest("ra-a1", T0.plusHours(48))));
        assertEquals(422, above.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", above.getCode(),
                "雨量恰达门槛启用折减后，速率1.5不低于折减安全线1.0");

        // 场景B：雨量略低于门槛 → 不折减，同一速率1.5低于原安全线2.0，放行
        long zoneB = createZone("R-RED-B");
        long chainB = triggerRedChain(zoneB, "rb");
        confirmEvacuation(chainB, "rb-ev", T0.plusHours(24).plusMinutes(10));
        publishStrategy(zoneB, "rb-s1", 0, 5.0, 50.0, 24.0, 0.5,
                T0.plusHours(10), T0.plusHours(10), 201);
        ingest(zoneB, "rb-r2", T0.plusHours(36), 121.0);
        ingest(zoneB, "rb-r3", T0.plusHours(48), 139.0);
        insertRainfall(zoneB, "rb-rain", T0.plusHours(12), 36, 0.9);
        IdempotencyService.Stored applied = slopeService.applyResumption(chainB,
                new ResumptionApplyRequest("rb-a1", T0.plusHours(48)));
        assertEquals(201, applied.status());
    }

    @Test
    void looserStrategyDoesNotRevokeRedChain() {
        long zoneId = createZone("R-LOOSE");
        long chainId = triggerRedChain(zoneId, "loose");
        publishStrategy(zoneId, "loose-s1", 0, 4.0, 40.0, 24.0, 0.5,
                T0.plusHours(25), T0.plusHours(26), 201);
        // 变宽：全部参数放宽
        publishStrategy(zoneId, "loose-s2", 1, 10.0, 100.0, 1000.0, 1.0,
                T0.plusHours(27), T0.plusHours(28), 201);
        assertEquals(2, slopeService.listStrategies(zoneId).size());
        assertEquals("EXECUTING", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId),
                "变宽不撤销既有红色处置链");
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    // ---------------------------------------------------------------
    // 复工证据：申请冻结、批准复核、并发更正一致性
    // ---------------------------------------------------------------

    @Test
    void resumptionEvidenceFrozenAndQueryable() {
        long zoneId = createZone("R-EV");
        long chainId = triggerRedChain(zoneId, "ev");
        confirmEvacuation(chainId, "ev-ev", T0.plusHours(24).plusMinutes(10));
        ingest(zoneId, "ev-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "ev-r3", T0.plusHours(48), 122.0);

        slopeService.applyResumption(chainId, new ResumptionApplyRequest("ev-a1", T0.plusHours(48)));
        List<ResumptionEvidenceItem> afterApply = slopeService.resumptionEvidence(chainId);
        assertEquals(1, afterApply.size());
        ResumptionEvidenceItem applyEvidence = afterApply.get(0);
        assertEquals("APPLY", applyEvidence.phase());
        assertEquals(T0.plusHours(24), applyEvidence.windowStart());
        assertEquals(T0.plusHours(48), applyEvidence.evaluatedAt());
        assertEquals(2, applyEvidence.readingCount());
        assertNull(applyEvidence.strategyVersion(), "无策略时证据不携带策略版本");
        assertNull(applyEvidence.rainfall24hMm());
        assertEquals("SAFE", applyEvidence.result());
        assertTrue(applyEvidence.detail().contains("measuredAt"), "证据冻结逐读数判定明细");

        // 单确认不产生批准证据
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("ev-c1", "SITE_CHECK", T0.plusHours(49)));
        assertEquals(1, slopeService.resumptionEvidence(chainId).size());

        // 双确认批准：复核并冻结APPROVE证据
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("ev-c2", "MONITORING_REVIEW", T0.plusHours(50)));
        List<ResumptionEvidenceItem> afterApprove = slopeService.resumptionEvidence(chainId);
        assertEquals(2, afterApprove.size());
        assertEquals("APPROVE", afterApprove.get(1).phase());
        assertEquals("SAFE", afterApprove.get(1).result());
    }

    @Test
    void approvalReevaluatesWithCorrectedRainfall() {
        long zoneId = createZone("R-REVAL");
        long chainId = triggerRedChain(zoneId, "rv");
        confirmEvacuation(chainId, "rv-ev", T0.plusHours(24).plusMinutes(10));
        // 策略生效于触发前：不重置计时；雨量门槛24、系数0.5
        publishStrategy(zoneId, "rv-s1", 0, 5.0, 50.0, 24.0, 0.5,
                T0.plusHours(10), T0.plusHours(10), 201);
        ingest(zoneId, "rv-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "rv-r3", T0.plusHours(48), 139.0);
        // 申请时雨量累计12<24：不折减，速率1.5低于安全线2.0，申请通过
        insertRainfall(zoneId, "rv-rain", T0.plusHours(12), 36, 0.5);
        slopeService.applyResumption(chainId, new ResumptionApplyRequest("rv-a1", T0.plusHours(48)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("rv-c1", "SITE_CHECK", T0.plusHours(49)));

        // 批准前雨量更正：累计48≥24 → 折减生效，安全线降为1.0，速率1.5不再安全
        for (int i = 0; i < 36; i++) {
            submitRainfall(zoneId, "rv-fix-" + i, T0.plusHours(12 + i), 2.0, 1, 200);
        }
        ApiException rejected = assertThrows(ApiException.class, () -> slopeService.confirmResumption(
                chainId, new ResumptionConfirmRequest("rv-c2", "MONITORING_REVIEW", T0.plusHours(50))));
        assertEquals(422, rejected.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", rejected.getCode());

        // 批准被阻断：链仍执行中、测区仍封锁、评估未批准、无APPROVE证据
        assertEquals("EXECUTING", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
        assertEquals("LOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
        assertEquals("APPLIED", jdbcTemplate.queryForObject(
                "SELECT status FROM resumption_assessment WHERE chain_id = ?", String.class, chainId));
        assertEquals(1, slopeService.resumptionEvidence(chainId).size());
        assertEquals("APPLY", slopeService.resumptionEvidence(chainId).get(0).phase());
        // 失败的确认请求不占幂等键
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'rv-c2'", Integer.class));
    }
}
