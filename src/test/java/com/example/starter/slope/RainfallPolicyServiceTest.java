package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.PolicyPublishRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallHourRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceResponse;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雨量联动阈值版本与复工安全门禁业务测试：
 * 策略发布乐观版本、小时雨量写入/更正、同一快照有效阈值（恰达门槛折减、缺点阻断不按零）、
 * 速率恰等阈值仍判正常、变严重新累计安全计时、变宽不撤销红色处置链、批准冻结一致证据、
 * 新写操作幂等与失败不占键。
 */
@SpringBootTest
class RainfallPolicyServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0);

    @Autowired
    private SlopeService slopeService;
    @Autowired
    private SlopeRepository repository;
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
        jdbc.update("DELETE FROM resumption_evidence");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM threshold_policy_version");
        jdbc.update("DELETE FROM rainfall_hour");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM resumption_assessment");
        jdbc.update("DELETE FROM evacuation_confirmation");
        jdbc.update("DELETE FROM disposal_chain");
        jdbc.update("DELETE FROM radar_reading");
        jdbc.update("DELETE FROM slope_zone");
    }

    private void truncateAll() {
        jdbcTemplate.update("DELETE FROM resumption_evidence");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM threshold_policy_version");
        jdbcTemplate.update("DELETE FROM rainfall_hour");
        jdbcTemplate.update("DELETE FROM late_event");
        jdbcTemplate.update("DELETE FROM resumption_assessment");
        jdbcTemplate.update("DELETE FROM evacuation_confirmation");
        jdbcTemplate.update("DELETE FROM disposal_chain");
        jdbcTemplate.update("DELETE FROM radar_reading");
        jdbcTemplate.update("DELETE FROM slope_zone");
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private JsonNode readJson(IdempotencyService.Stored stored) {
        try {
            return objectMapper.readTree(stored.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long createZone(String code) {
        IdempotencyService.Stored stored = slopeService.createZone(new CreateZoneRequest(
                "zone-" + code, code, "雨量联动测区" + code, 5.0, 50.0, 2.0, 10));
        assertEquals(201, stored.status());
        return readJson(stored).get("zoneId").asLong();
    }

    private JsonNode publish(long zoneId, String reqId, int expected, double yellow, double red,
                             double gate, double factor, LocalDateTime submitted,
                             LocalDateTime effective) {
        IdempotencyService.Stored stored = slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest(reqId, expected, yellow, red, gate, factor,
                        submitted, effective));
        assertEquals(201, stored.status(), "策略发布应成功");
        return readJson(stored);
    }

    private JsonNode rain(long zoneId, String reqId, LocalDateTime hourUtc, double rainMm,
                          int expectedVersion) {
        IdempotencyService.Stored stored = slopeService.upsertRainfallHour(zoneId,
                new RainfallHourRequest(reqId, hourUtc, rainMm, expectedVersion));
        return readJson(stored);
    }

    /** 向 [startUtc, startUtc+hours) 的每个UTC整点写入相同雨量（合成数据）。 */
    private void rainBlock(long zoneId, String tag, LocalDateTime startUtc, int hours,
                           double rainMm) {
        for (int i = 0; i < hours; i++) {
            LocalDateTime hour = startUtc.plusHours(i);
            rain(zoneId, tag + "-" + i, hour, rainMm, 0);
        }
    }

    private JsonNode ingest(long zoneId, String reqId, LocalDateTime measuredAt, double disp) {
        return readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(reqId, measuredAt, disp)));
    }

    private long triggerRed(long zoneId, String tag) {
        ingest(zoneId, tag + "-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, tag + "-r1", T0.plusHours(24), 120.0);
        assertEquals("RED_CHAIN_CREATED", red.get("evaluation").asText());
        return red.get("chainId").asLong();
    }

    private void evacuate(long chainId, String tag) {
        IdempotencyService.Stored stored = slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(tag + "-ev", T0.plusHours(24).plusMinutes(10), 10));
        assertEquals(201, stored.status());
    }

    /**
     * 构造零雨量宽块（覆盖本批测试全部24小时窗口）：T0=2026-09-01T08:00上海=09-01T00Z，
     * 从08-31T00Z起120小时，覆盖首条读数的前24个完整UTC桶直至 T0+60h 评价窗口。
     */
    private void zeroRainBlock(long zoneId, String tag) {
        rainBlock(zoneId, tag, LocalDateTime.of(2026, 8, 31, 0, 0), 120, 0.0);
    }

    private ApiException expectApi(Runnable action, int status, String code) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(status, ex.getStatus());
        assertEquals(code, ex.getCode());
        return ex;
    }

    // ---------------------------------------------------------------
    // 策略发布：版本乐观锁与参数校验
    // ---------------------------------------------------------------

    @Test
    void policyPublishStartsAtVersionOneAndIncrements() {
        long zoneId = createZone("P-VER");
        JsonNode v1 = publish(zoneId, "p-v1", 0, 5.0, 50.0, 10.0, 0.5, T0, T0.plusHours(1));
        assertEquals(1, v1.get("version").asInt());
        assertFalse(v1.get("tightenedFromPrevious").asBoolean(), "首版不存在变严概念");

        JsonNode v2 = publish(zoneId, "p-v2", 1, 4.0, 40.0, 10.0, 0.5,
                T0.plusHours(2), T0.plusHours(3));
        assertEquals(2, v2.get("version").asInt());
        assertTrue(v2.get("tightenedFromPrevious").asBoolean(), "黄色/红色基础阈值降低判变严");

        // 期望版本落后 → 409，不产生新版本
        expectApi(409, "POLICY_VERSION_CONFLICT", () -> publish(zoneId, "p-v3-bad", 1,
                3.0, 30.0, 10.0, 0.5, T0.plusHours(4), T0.plusHours(5)));
        assertEquals(2, repository.findLatestPolicyVersionByZone(zoneId).orElseThrow().version());

        List<JsonNode> listed = slopeService.listPolicies(zoneId).stream()
                .map(v -> objectMapper.convertValue(v, JsonNode.class)).toList();
        assertEquals(2, listed.size());
    }

    private void expectApi(int status, String code, Runnable action) {
        expectApi(action, status, code);
    }

    @Test
    void policyPublishValidatesThresholdsFactorAndTiming() {
        long zoneId = createZone("P-BAD");
        // 0<黄<红
        expectApi(400, "INVALID_RATE_THRESHOLDS", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pb-1", 0, 5.0, 5.0, 10.0, 0.5, T0, T0.plusHours(1))));
        expectApi(400, "INVALID_RATE_THRESHOLDS", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pb-2", 0, 6.0, 5.0, 10.0, 0.5, T0, T0.plusHours(1))));
        // 折减系数 (0,1]
        expectApi(400, "INVALID_REDUCTION_FACTOR", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pb-3", 0, 5.0, 50.0, 10.0, 1.5, T0, T0.plusHours(1))));
        expectApi(400, "INVALID_REDUCTION_FACTOR", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pb-4", 0, 5.0, 50.0, 10.0, 0.0, T0, T0.plusHours(1))));
        // 生效早于提交 → 422
        expectApi(422, "EFFECTIVE_BEFORE_SUBMITTED", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pb-5", 0, 5.0, 50.0, 10.0, 0.5,
                        T0.plusHours(2), T0.plusHours(1))));
        // 非法expectedVersion → 400
        expectApi(400, "INVALID_EXPECTED_VERSION", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pb-6", -1, 5.0, 50.0, 10.0, 0.5, T0, T0.plusHours(1))));
        assertTrue(repository.findLatestPolicyVersionByZone(zoneId).isEmpty(),
                "全部非法发布均不落库");
    }

    @Test
    void policyPublishIdempotentReplayAndFailureDoesNotOccupyKey() {
        long zoneId = createZone("P-IDM");
        PolicyPublishRequest req = new PolicyPublishRequest("pidm", 0, 5.0, 50.0, 10.0, 0.5,
                T0, T0.plusHours(1));
        IdempotencyService.Stored first = slopeService.publishPolicy(zoneId, req);
        IdempotencyService.Stored replay = slopeService.publishPolicy(zoneId, req);
        assertEquals(first.body(), replay.body(), "同键同参重放首次冻结结果");
        assertEquals(1, repository.findPolicyVersionsByZone(zoneId).size());

        // 同键异参 → 409
        expectApi(409, "IDEMPOTENCY_CONFLICT", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pidm", 0, 4.0, 50.0, 10.0, 0.5,
                        T0, T0.plusHours(1))));

        // 失败请求不占用requestId：同键先400后合法 → 成功（当前版本已为1，携带expectedVersion=1发布v2）
        expectApi(400, "INVALID_RATE_THRESHOLDS", () -> slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pidm-fail", 9, 5.0, 5.0, 10.0, 0.5,
                        T0.plusHours(2), T0.plusHours(3))));
        IdempotencyService.Stored after = slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest("pidm-fail", 1, 5.0, 50.0, 10.0, 0.5,
                        T0.plusHours(2), T0.plusHours(3)));
        assertEquals(201, after.status(), "业务失败不占用幂等键");
        assertEquals(2, readJson(after).get("version").asInt());
    }

    // ---------------------------------------------------------------
    // 小时雨量：唯一、自然重放、显式更正
    // ---------------------------------------------------------------

    @Test
    void rainfallHourCreateReplayAndVersionedCorrection() {
        long zoneId = createZone("R-CR");
        LocalDateTime h = LocalDateTime.of(2026, 9, 2, 0, 0);
        JsonNode created = rain(zoneId, "rc-1", h, 2.0, 0);
        assertEquals("CREATED", created.get("outcome").asText());
        assertEquals(1, created.get("version").asInt());

        // 同测区同小时同值自然重放：携带任意版本号都不升版
        JsonNode replay = rain(zoneId, "rc-2", h, 2.0, 0);
        assertEquals("REPLAYED", replay.get("outcome").asText());
        assertEquals(1, replay.get("version").asInt());

        // 异值首写版本号 → 409
        expectApi(409, "RAIN_HOUR_VERSION_CONFLICT", () -> rain(zoneId, "rc-3", h, 3.0, 0));
        // 异值显式更正 → 版本加1
        JsonNode corrected = rain(zoneId, "rc-4", h, 3.0, 1);
        assertEquals("CORRECTED", corrected.get("outcome").asText());
        assertEquals(2, corrected.get("version").asInt());
        assertEquals(3.0, repository.findRainfallHour(zoneId, h).orElseThrow().rainMm(), 1e-9);
        // 版本号过期 → 409
        expectApi(409, "RAIN_HOUR_VERSION_CONFLICT", () -> rain(zoneId, "rc-5", h, 4.0, 1));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hour WHERE zone_id = ?", Integer.class, zoneId),
                "同小时始终唯一一行");

        // 非整点 / 负值 → 400，且失败不占键
        expectApi(400, "INVALID_RAIN_HOUR",
                () -> rain(zoneId, "rc-bad1", LocalDateTime.of(2026, 9, 2, 0, 30), 1.0, 0));
        expectApi(400, "INVALID_RAIN_VALUE",
                () -> rain(zoneId, "rc-bad2", h, -0.1, 0));
        IdempotencyService.Stored reused = slopeService.upsertRainfallHour(zoneId,
                new RainfallHourRequest("rc-bad2", h.plusHours(5), 1.5, 0));
        assertEquals(201, reused.status(), "400失败不占用幂等键");
    }

    // ---------------------------------------------------------------
    // 同一快照下的有效阈值
    // ---------------------------------------------------------------

    @Test
    void effectiveThresholdUsesOneSnapshotAndExactGateReduction() {
        long zoneId = createZone("T-EF");
        LocalDateTime submitted = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime effective = LocalDateTime.of(2026, 9, 2, 8, 0);
        publish(zoneId, "t-v1", 0, 5.0, 50.0, 10.0, 0.5, submitted, effective);

        // 生效前：无生效策略，返回静态阈值
        EffectiveThresholdResponse before = slopeService.effectiveThreshold(zoneId, effective.minusHours(1));
        assertNull(before.policyVersion());
        assertEquals(5.0, before.effectiveYellowHourlyRate(), 1e-9);
        assertTrue(before.dataSufficient());

        // A = 2026-09-03T08:00 上海 → UTC整点桶 09-03T00Z，前24个完整桶为 09-02T00Z..09-02T23Z
        LocalDateTime at = LocalDateTime.of(2026, 9, 3, 8, 0);
        rainBlock(zoneId, "t-rain", LocalDateTime.of(2026, 9, 2, 0, 0), 24, 0.0);
        EffectiveThresholdResponse dry = slopeService.effectiveThreshold(zoneId, at);
        assertEquals(1, dry.policyVersion());
        assertTrue(dry.dataSufficient());
        assertFalse(dry.reductionApplied());
        assertEquals(0.0, dry.rainSum24h(), 1e-9);
        assertEquals(5.0, dry.effectiveYellowHourlyRate(), 1e-9, "雨量低于门槛不折减");
        assertEquals(2.0, dry.effectiveSafetyHourlyRate(), 1e-9);

        // 恰达门槛：把其中一个桶更正为10，累计恰为10 → 启用折减
        rain(zoneId, "t-rain-fix", LocalDateTime.of(2026, 9, 2, 5, 0), 10.0, 1);
        EffectiveThresholdResponse atGate = slopeService.effectiveThreshold(zoneId, at);
        assertTrue(atGate.reductionApplied(), "雨量恰达门槛即启用折减");
        assertEquals(10.0, atGate.rainSum24h(), 1e-9);
        assertEquals(2.5, atGate.effectiveYellowHourlyRate(), 1e-9);
        assertEquals(25.0, atGate.effectiveRedDailyRate(), 1e-9);
        assertEquals(1.0, atGate.effectiveSafetyHourlyRate(), 1e-9);
    }

    @Test
    void effectiveThresholdMissingHoursReportedAndNeverZero() {
        long zoneId = createZone("T-MS");
        LocalDateTime effective = LocalDateTime.of(2026, 9, 2, 8, 0);
        publish(zoneId, "m-v1", 0, 5.0, 50.0, 10.0, 0.5, effective.minusHours(1), effective);
        EffectiveThresholdResponse missing = slopeService.effectiveThreshold(
                zoneId, LocalDateTime.of(2026, 9, 3, 8, 0));
        assertFalse(missing.dataSufficient(), "任一缺点即数据不足");
        assertNull(missing.effectiveYellowHourlyRate(), "缺点时不产出折减阈值，绝不按零累计");
        assertNull(missing.rainSum24h());
        assertEquals(24, missing.missingHoursUtc().size());
    }

    // ---------------------------------------------------------------
    // 读数评价：折减阈值、恰等判正常、缺点422回滚
    // ---------------------------------------------------------------

    @Test
    void reducedThresholdTriggersYellowAndExactEqualStillNormal() {
        // 折减后黄线2.5：小时速率3.0触发黄色；另一测区2.5恰等判正常
        LocalDateTime r0 = LocalDateTime.of(2026, 9, 3, 7, 0);
        LocalDateTime r1 = LocalDateTime.of(2026, 9, 3, 8, 0);
        for (var scenario : List.of(new double[]{3.0, 1}, new double[]{2.5, 0})) {
            String code = scenario[1] == 1 ? "A-RD" : "A-EQ";
            long zoneId = createZone(code);
            publish(zoneId, "a-v1-" + code, 0, 5.0, 50.0, 10.0, 0.5,
                    LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 2, 8, 0));
            // 覆盖 r0 与 r1 评价窗口的宽块（零），再令两个窗口都含恰达门槛的桶
            rainBlock(zoneId, "a-rain-" + code, LocalDateTime.of(2026, 9, 1, 23, 0), 26, 0.0);
            rain(zoneId, "a-rain-fix-" + code, LocalDateTime.of(2026, 9, 2, 5, 0), 10.0, 1);
            ingest(zoneId, "a-r0-" + code, r0, 0.0);
            JsonNode body = ingest(zoneId, "a-r1-" + code, r1, scenario[0]);
            if (scenario[1] == 1) {
                assertEquals("YELLOW_CHAIN_CREATED", body.get("evaluation").asText(),
                        "折减后黄线2.5，严格超过（3.0）触发黄色");
            } else {
                assertEquals("NORMAL", body.get("evaluation").asText(),
                        "折减后速率恰等阈值2.5仍判正常");
            }
        }
    }

    @Test
    void readingUnderActivePolicyWithoutRainDataRejectedAndRolledBack() {
        long zoneId = createZone("A-MS");
        publish(zoneId, "ms-v1", 0, 5.0, 50.0, 10.0, 0.5, T0, T0);
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.ingestReading(zoneId,
                new IngestReadingRequest("ms-r1", T0.plusHours(25), 120.0)));
        assertEquals(422, ex.getStatus());
        assertEquals("RAIN_DATA_INSUFFICIENT", ex.getCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM radar_reading WHERE zone_id = ?", Integer.class, zoneId),
                "数据不足时读数与业务写入同事务回滚");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_chain WHERE zone_id = ?", Integer.class, zoneId));
    }

    // ---------------------------------------------------------------
    // 复工安全门禁：变严重计、变宽不撤销、证据冻结
    // ---------------------------------------------------------------

    @Test
    void tightenedPolicyRestartsSafetyClockAndApprovalFreezesEvidence() {
        long zoneId = createZone("G-TIGHT");
        zeroRainBlock(zoneId, "gt-rain");
        // v1 早已生效，门槛极大不折减
        publish(zoneId, "gt-v1", 0, 5.0, 50.0, 999.0, 1.0,
                LocalDateTime.of(2026, 8, 31, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0));
        long chainId = triggerRed(zoneId, "gt");
        evacuate(chainId, "gt");
        // 红色后逐小时低速率读数（0.1毫米/小时，始终低于安全线2.0）
        for (int h = 25; h <= 60; h++) {
            ingest(zoneId, "gt-s" + h, T0.plusHours(h), 120.0 + 0.1 * (h - 24));
        }
        // 变严：黄色阈值5→4，在 T0+36h 生效
        LocalDateTime e = T0.plusHours(36);
        JsonNode v2 = publish(zoneId, "gt-v2", 1, 4.0, 50.0, 999.0, 1.0,
                e.minusMinutes(5), e);
        assertTrue(v2.get("tightenedFromPrevious").asBoolean());

        // 自生效时刻重新累计：E+23h 不满足连续24小时
        expectApi(422, "RECOVERY_WINDOW_TOO_SHORT", () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("gt-a-bad", e.plusHours(23))));
        // E+24h 恰满允许
        IdempotencyService.Stored applied = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("gt-a1", e.plusHours(24)));
        assertEquals(201, applied.status());

        // 批准前证据404
        expectApi(404, "RESUMPTION_EVIDENCE_NOT_FOUND",
                () -> slopeService.resumptionEvidence(chainId));

        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("gt-c1", "SITE_CHECK", e.plusHours(24).plusMinutes(5)));
        IdempotencyService.Stored approve = slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("gt-c2", "MONITORING_REVIEW",
                        e.plusHours(24).plusMinutes(10)));
        assertEquals("APPROVED", readJson(approve).get("assessmentStatus").asText());

        ResumptionEvidenceResponse evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(e, evidence.windowStart(), "变严后安全计时自生效时刻重新累计");
        assertEquals(e, evidence.lastTightenedAt());
        assertEquals(e.plusHours(24).plusMinutes(10), evidence.frozenAt());
        var snapshot = evidence.snapshot();
        assertFalse(snapshot.moments().isEmpty());
        snapshot.moments().forEach(m -> {
            assertFalse(m.measuredAt().isBefore(e), "重计窗口不含生效时刻之前的旧安全计时");
            assertEquals(2, m.policyVersion(), "逐时刻按当时实际有效的v2阈值判定");
            assertTrue(m.safe());
        });
        assertEquals(2, snapshot.policyVersions().size());
        assertEquals(2, snapshot.confirmations().size(), "冻结原双确认证据");
        assertTrue(snapshot.confirmations().stream()
                .anyMatch(c -> "SITE_CHECK".equals(c.type()) && c.confirmedAt() != null));
        assertTrue(snapshot.confirmations().stream()
                .anyMatch(c -> "MONITORING_REVIEW".equals(c.type()) && c.confirmedAt() != null));
        // 证据落库且唯一
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_evidence WHERE chain_id = ?", Integer.class, chainId));
    }

    @Test
    void wideningPolicyDoesNotUndoExistingRedChain() {
        long zoneId = createZone("G-WIDE");
        zeroRainBlock(zoneId, "gw-rain");
        publish(zoneId, "gw-v1", 0, 5.0, 50.0, 999.0, 1.0,
                LocalDateTime.of(2026, 8, 31, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0));
        long chainId = triggerRed(zoneId, "gw");
        evacuate(chainId, "gw");
        // 变宽：黄色5→6、红色50→60
        JsonNode v2 = publish(zoneId, "gw-v2", 1, 6.0, 60.0, 999.0, 1.0,
                T0.plusHours(25), T0.plusHours(26));
        assertFalse(v2.get("tightenedFromPrevious").asBoolean(), "阈值升高判变宽");
        // 变宽不撤销既有红色处置链：链仍执行中、测区仍封锁
        var chain = slopeService.chainDetail(chainId);
        assertEquals("EXECUTING", chain.status());
        assertEquals("RED", chain.level());
        assertEquals("LOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    @Test
    void approvalBlockedByRainDataGapAndNoEvidencePersisted() {
        long zoneId = createZone("G-GAP");
        // 先无策略：红色链、撤人、安全读数（旧入口规则）
        long chainId = triggerRed(zoneId, "gap");
        evacuate(chainId, "gap");
        ingest(zoneId, "gap-s1", T0.plusHours(36), 121.0);
        ingest(zoneId, "gap-s2", T0.plusHours(48), 122.0);
        // 策略在安全窗口内才生效；生效后读数所需24个完整UTC雨量桶全部缺失
        publish(zoneId, "gap-v1", 0, 5.0, 50.0, 10.0, 1.0,
                T0.plusHours(30), T0.plusHours(30));
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("gap-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("RAIN_DATA_INSUFFICIENT", ex.getCode(), "缺点阻断新复工批准，不按零处理");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_assessment WHERE chain_id = ?", Integer.class, chainId),
                "阻断时申请同事务回滚");
        expectApi(404, "RESUMPTION_EVIDENCE_NOT_FOUND",
                () -> slopeService.resumptionEvidence(chainId));
    }

    // ---------------------------------------------------------------
    // 并发：雨量更正 / 复工确认
    // ---------------------------------------------------------------

    /** 准备红色撤人+已申请+单确认、等待第二确认的场景；窗口读数速率每小时 rateMm。 */
    private long preparePendingApproval(String code, double gate, double factor, double rateMm) {
        long zoneId = createZone(code);
        zeroRainBlock(zoneId, code + "-rain");
        publish(zoneId, code + "-v1", 0, 5.0, 50.0, gate, factor,
                LocalDateTime.of(2026, 8, 31, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0));
        long chainId = triggerRed(zoneId, code);
        evacuate(chainId, code);
        for (int h = 25; h <= 60; h++) {
            ingest(zoneId, code + "-s" + h, T0.plusHours(h), 120.0 + rateMm * (h - 24));
        }
        slopeService.applyResumption(chainId,
                new ResumptionApplyRequest(code + "-a1", T0.plusHours(60)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest(code + "-c1", "SITE_CHECK", T0.plusHours(60).plusMinutes(5)));
        return chainId;
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> w1 = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return first.call();
            };
            Callable<T> w2 = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return second.call();
            };
            Future<T> f1 = executor.submit(w1);
            Future<T> f2 = executor.submit(w2);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSameValueRainReplayAndApprovalFreezesOneEvidence() throws Exception {
        String code = "C-RACE1";
        long chainId = preparePendingApproval(code, 999.0, 1.0, 0.5);
        long zoneId = repository.findZoneByCode(code).map(SlopeRepository.ZoneRow::id).orElseThrow();
        // 窗口内某桶当前0.0，并发送同值自然重放
        LocalDateTime bucket = RainThresholdResolver.toUtcFloor(T0.plusHours(60)).minusHours(1);
        Callable<String> approve = () -> {
            try {
                IdempotencyService.Stored s = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest(code + "-c2", "MONITORING_REVIEW",
                                T0.plusHours(60).plusMinutes(10)));
                return readJson(s).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus();
            }
        };
        Callable<String> replay = () -> {
            JsonNode body = rain(zoneId, code + "-replay", bucket, 0.0, 1);
            return body.get("outcome").asText();
        };
        List<String> results = race(approve, replay);
        assertEquals(1, results.stream().filter("APPROVED"::equals).count(),
                "复工仅批准一次，实际: " + results);
        assertTrue(results.contains("REPLAYED"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_evidence WHERE chain_id = ?", Integer.class, chainId));
        assertEquals("RESUMED", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    @Test
    void concurrentCorrectionAndApprovalProducesConsistentOutcome() throws Exception {
        String code = "C-RACE2";
        long zoneId = createZone(code);
        // 门槛10、系数0.5：窗口读数1.5毫米/小时（低于安全线2.0）；若某桶更正为10使折减生效，
        // 安全线降为1.0，读数1.5即不安全。
        long chainId = preparePendingApproval(code, 10.0, 0.5, 1.5);
        LocalDateTime bucket = RainThresholdResolver.toUtcFloor(T0.plusHours(60)).minusHours(1);
        Callable<String> approve = () -> {
            try {
                IdempotencyService.Stored s = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest(code + "-c2", "MONITORING_REVIEW",
                                T0.plusHours(60).plusMinutes(10)));
                return readJson(s).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus() + ":" + e.getCode();
            }
        };
        Callable<String> correct = () -> {
            JsonNode body = rain(zoneId, code + "-fix", bucket, 10.0, 1);
            return body.get("outcome").asText();
        };
        List<String> results = race(approve, correct);
        assertTrue(results.contains("CORRECTED"));
        long evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_evidence WHERE chain_id = ?", Integer.class, chainId);
        String chainStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId);
        String zoneStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId);
        if (results.contains("APPROVED")) {
            // 批准先提交：证据冻结的是更正前快照（该桶0.0、未折减），且唯一
            assertEquals(1, evidenceCount, "批准与更正二选一顺序下证据最多一条");
            assertEquals("RESUMED", chainStatus);
            assertEquals("NORMAL", zoneStatus);
            ResumptionEvidenceResponse ev = slopeService.resumptionEvidence(chainId);
            assertTrue(ev.snapshot().moments().stream().allMatch(EvidenceMoment ->
                    !EvidenceMoment.reductionApplied()));
        } else {
            // 更正先提交：批准按同一快照重算门禁，折减后不安全 → 阻断，不得冻结证据
            assertTrue(results.stream().anyMatch(r -> r.startsWith("HTTP_422")),
                    "实际: " + results);
            assertEquals(0, evidenceCount, "阻断时不冻结证据");
            assertEquals("EXECUTING", chainStatus);
            assertEquals("LOCKED", zoneStatus, "未批准则维持封锁，不存在既批准又封锁");
        }
    }

    @Test
    void concurrentPolicyTighteningAndApprovalProducesConsistentOutcome() throws Exception {
        String code = "C-RACE3";
        long zoneId = createZone(code);
        zeroRainBlock(zoneId, code + "-rain");
        // v1 早已生效
        publish(zoneId, code + "-v1", 0, 5.0, 50.0, 999.0, 1.0,
                LocalDateTime.of(2026, 8, 31, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0));
        long chainId = triggerRed(zoneId, code);
        evacuate(chainId, code);
        for (int h = 25; h <= 60; h++) {
            ingest(zoneId, code + "-s" + h, T0.plusHours(h), 120.0 + 0.5 * (h - 24));
        }
        slopeService.applyResumption(chainId,
                new ResumptionApplyRequest(code + "-a1", T0.plusHours(60)));
        slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest(code + "-c1", "SITE_CHECK",
                        T0.plusHours(60).plusMinutes(5)));
        // 变严策略在批准时刻即时生效（黄线5→4）：若发布先提交，批准须按新策略重新累计安全计时
        LocalDateTime effective = T0.plusHours(60).plusMinutes(10);
        Callable<String> approve = () -> {
            try {
                IdempotencyService.Stored s = slopeService.confirmResumption(chainId,
                        new ResumptionConfirmRequest(code + "-c2", "MONITORING_REVIEW", effective));
                return readJson(s).get("assessmentStatus").asText();
            } catch (ApiException e) {
                return "HTTP_" + e.getStatus() + ":" + e.getCode();
            }
        };
        Callable<String> publishTight = () -> {
            JsonNode body = publish(zoneId, code + "-v2", 1, 4.0, 50.0, 999.0, 1.0,
                    effective.minusMinutes(1), effective);
            return "PUBLISHED_V" + body.get("version").asInt();
        };
        List<String> results = race(approve, publishTight);
        assertTrue(results.contains("PUBLISHED_V2"), "变严策略发布必定成功一次，实际: " + results);
        long evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_evidence WHERE chain_id = ?", Integer.class, chainId);
        String chainStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId);
        String zoneStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId);
        if (results.contains("APPROVED")) {
            // 批准先提交：冻结证据固化在v1快照，策略之后生效不重算证据
            assertEquals(1, evidenceCount);
            assertEquals("RESUMED", chainStatus);
            assertEquals("NORMAL", zoneStatus);
            ResumptionEvidenceResponse ev = slopeService.resumptionEvidence(chainId);
            assertEquals(1, ev.snapshot().policyVersions().size(),
                    "证据冻结于v1快照，后续发布v2不重算");
            assertEquals(T0.plusHours(24), ev.windowStart());
        } else {
            // 发布先提交：批准在同一锁顺序下读到v2，自生效时刻重计未满24小时 → 阻断
            assertTrue(results.stream().anyMatch(r -> r.startsWith("HTTP_422")),
                    "实际: " + results);
            assertEquals(0, evidenceCount, "阻断时不冻结证据");
            assertEquals("EXECUTING", chainStatus);
            assertEquals("LOCKED", zoneStatus, "未批准维持封锁，证据与状态一致");
            // 单确认保持不变（原双确认语义未被绕过）
            assertTrue(jdbcTemplate.queryForObject(
                    "SELECT site_check_confirmed FROM resumption_assessment WHERE chain_id = ?",
                    Boolean.class, chainId));
            assertFalse(jdbcTemplate.queryForObject(
                    "SELECT monitoring_review_confirmed FROM resumption_assessment WHERE chain_id = ?",
                    Boolean.class, chainId));
        }
    }

    // ---------------------------------------------------------------
    // 结构化指纹：含分隔符文本必须区分
    // ---------------------------------------------------------------

    @Test
    void fingerprintWithDelimiterTextIsUnambiguous() {
        long zoneId = createZone("F-FP");
        // 朴素拼接会把 ("A|A","B") 与 ("A","A|B") 拼成相同串；JSON 结构化指纹必须区分
        IdempotencyService.Stored first = slopeService.createZone(new CreateZoneRequest(
                "fp-key", "A|A", "B", 5.0, 50.0, 2.0, 10));
        assertEquals(201, first.status());
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.createZone(
                new CreateZoneRequest("fp-key", "A", "A|B", 5.0, 50.0, 2.0, 10)));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slope_zone WHERE id = ?", Integer.class, zoneId));
    }
}
