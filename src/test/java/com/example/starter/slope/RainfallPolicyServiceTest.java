package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.PolicyPublishRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallCorrectionInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallHourInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallReportRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雨量联动阈值版本测试：雨量上报/更正与版本、策略发布校验与版本、有效阈值计算
 * （生效时刻、恰达门槛折减、恰等阈值判正常、缺点不当零）、幂等与结构化指纹。
 * 业务时间全部由请求报文控制，即可控时钟。
 */
@SpringBootTest
class RainfallPolicyServiceTest {

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
        // DB_CLOSE_DELAY=-1 的命名内存库在测试类结束后释放业务数据；内存随 JVM 退出回收。
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

    // ---------------------------------------------------------------
    // 测试辅助
    // ---------------------------------------------------------------

    private long createZone(String code) {
        IdempotencyService.Stored stored = slopeService.createZone(new CreateZoneRequest(
                "zone-" + code, code, "雨量测区" + code, 5.0, 50.0, 2.0, 10));
        return readJson(stored).get("zoneId").asLong();
    }

    private JsonNode readJson(IdempotencyService.Stored stored) {
        try {
            return objectMapper.readTree(stored.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private IdempotencyService.Stored reportRainfall(long zoneId, String requestId,
                                                     LocalDateTime hour, Double value,
                                                     Long expectedVersion) {
        return slopeService.reportRainfall(zoneId,
                new RainfallReportRequest(requestId, hour, value, expectedVersion, hour));
    }

    /** 上报 [from, to) 逐小时雨量（UTC整点），全部成功。 */
    private void reportRainfallRange(long zoneId, String tag, LocalDateTime from,
                                     LocalDateTime to, double value) {
        for (LocalDateTime h = from; h.isBefore(to); h = h.plusHours(1)) {
            IdempotencyService.Stored stored = reportRainfall(zoneId,
                    tag + "-" + h, h, value, null);
            assertEquals(201, stored.status(), "小时雨量上报失败: " + h);
        }
    }

    private IdempotencyService.Stored publishPolicy(long zoneId, String requestId,
                                                    long expectedVersion, double yellow, double red,
                                                    double rainThreshold, double factor,
                                                    LocalDateTime effectiveAt,
                                                    LocalDateTime submittedAt) {
        return slopeService.publishPolicy(zoneId, new PolicyPublishRequest(requestId,
                expectedVersion, yellow, red, rainThreshold, factor, effectiveAt, submittedAt));
    }

    private JsonNode ingest(long zoneId, String requestId, LocalDateTime measuredAt,
                            double displacement) {
        return readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(requestId, measuredAt, displacement)));
    }

    // ---------------------------------------------------------------
    // 雨量上报、重放与显式更正
    // ---------------------------------------------------------------

    @Test
    void rainfallReportReplayAndExplicitCorrection() {
        long zoneId = createZone("RP-RF1");
        LocalDateTime hour = T0;

        IdempotencyService.Stored first = reportRainfall(zoneId, "rf1-a", hour, 5.0, null);
        assertEquals(201, first.status());
        JsonNode firstBody = readJson(first);
        assertEquals(1, firstBody.get("version").asLong(), "版本从1开始");
        assertTrue(!firstBody.get("replayed").asBoolean());

        // 同测区同小时同值重放（不同requestId）：返回现状，不产生新版本
        IdempotencyService.Stored replay = reportRainfall(zoneId, "rf1-b", hour, 5.0, null);
        assertEquals(200, replay.status());
        assertTrue(readJson(replay).get("replayed").asBoolean());
        assertEquals(1, readJson(replay).get("version").asLong());

        // 异值但未携带更正版本：409
        ApiException ex = assertThrows(ApiException.class,
                () -> reportRainfall(zoneId, "rf1-c", hour, 6.0, null));
        assertEquals(409, ex.getStatus());
        assertEquals("RAINFALL_CORRECTION_REQUIRED", ex.getCode());

        // 更正版本不匹配：409
        ex = assertThrows(ApiException.class,
                () -> reportRainfall(zoneId, "rf1-d", hour, 6.0, 2L));
        assertEquals(409, ex.getStatus());
        assertEquals("VERSION_CONFLICT", ex.getCode());

        // 显式更正：版本+1
        IdempotencyService.Stored corrected = reportRainfall(zoneId, "rf1-e", hour, 6.0, 1L);
        assertEquals(201, corrected.status());
        assertEquals(2, readJson(corrected).get("version").asLong(), "成功变更加1");

        // 同值更正视为重放，不再产生新版本
        IdempotencyService.Stored sameValue = reportRainfall(zoneId, "rf1-f", hour, 6.0, 2L);
        assertEquals(200, sameValue.status());
        assertEquals(2, readJson(sameValue).get("version").asLong());

        // 更正历史与当前值查询
        List<RainfallCorrectionInfo> history = slopeService.rainfallCorrections(zoneId, hour);
        assertEquals(2, history.size());
        assertEquals(5.0, history.get(0).rainfallMm(), 1e-9);
        assertEquals(6.0, history.get(1).rainfallMm(), 1e-9);
        List<RainfallHourInfo> current = slopeService.rainfallHours(zoneId, null, null);
        assertEquals(1, current.size());
        assertEquals(6.0, current.get(0).rainfallMm(), 1e-9);
        assertEquals(2, current.get(0).version());

        // 更正不存在的小时：404
        ApiException notFound = assertThrows(ApiException.class,
                () -> reportRainfall(zoneId, "rf1-g", T0.plusHours(1), 3.0, 1L));
        assertEquals(404, notFound.getStatus());
        assertEquals("RAINFALL_HOUR_NOT_FOUND", notFound.getCode());
    }

    @Test
    void rainfallValidationRejectsIllegalParams() {
        long zoneId = createZone("RP-RV1");
        // 非UTC整点：400
        ApiException ex = assertThrows(ApiException.class, () -> reportRainfall(zoneId, "rv-a",
                T0.plusMinutes(30), 5.0, null));
        assertEquals(400, ex.getStatus());
        assertEquals("INVALID_HOUR_START", ex.getCode());
        // 负值：400
        ex = assertThrows(ApiException.class, () -> reportRainfall(zoneId, "rv-b", T0, -1.0, null));
        assertEquals(400, ex.getStatus());
        assertEquals("INVALID_RAINFALL_VALUE", ex.getCode());
        // 非有限值：400
        ex = assertThrows(ApiException.class,
                () -> reportRainfall(zoneId, "rv-c", T0, Double.NaN, null));
        assertEquals(400, ex.getStatus());
        // 测区不存在：404
        ex = assertThrows(ApiException.class,
                () -> reportRainfall(999999, "rv-d", T0, 1.0, null));
        assertEquals(404, ex.getStatus());
        assertEquals("ZONE_NOT_FOUND", ex.getCode());
        // 参数非法不占键：同一requestId修正参数后成功
        IdempotencyService.Stored ok = reportRainfall(zoneId, "rv-b", T0, 1.0, null);
        assertEquals(201, ok.status(), "失败不占键，同键修正参数后受理");
    }

    // ---------------------------------------------------------------
    // 策略发布：校验、版本与生效时刻
    // ---------------------------------------------------------------

    @Test
    void policyPublishValidationAndVersioning() {
        long zoneId = createZone("RP-PV1");
        // 黄阈值必须严格小于红阈值
        ApiException ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-a",
                0, 5.0, 5.0, 100.0, 0.5, T0, T0));
        assertEquals(400, ex.getStatus());
        assertEquals("INVALID_POLICY_THRESHOLDS", ex.getCode());
        // 黄阈值必须为正
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-b",
                0, 0.0, 5.0, 100.0, 0.5, T0, T0));
        assertEquals(400, ex.getStatus());
        // 折减系数须在(0,1]
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-c",
                0, 4.0, 8.0, 100.0, 0.0, T0, T0));
        assertEquals(400, ex.getStatus());
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-d",
                0, 4.0, 8.0, 100.0, 1.5, T0, T0));
        assertEquals(400, ex.getStatus());
        // 雨量门槛非负
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-e",
                0, 4.0, 8.0, -1.0, 0.5, T0, T0));
        assertEquals(400, ex.getStatus());
        // 生效时刻不得早于提交时刻
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-f",
                0, 4.0, 8.0, 100.0, 0.5, T0, T0.plusHours(1)));
        assertEquals(400, ex.getStatus());
        assertEquals("EFFECTIVE_BEFORE_SUBMISSION", ex.getCode());
        // expectedVersion须等于当前最新版本（无策略为0）
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-g",
                1, 4.0, 8.0, 100.0, 0.5, T0, T0));
        assertEquals(409, ex.getStatus());
        assertEquals("VERSION_CONFLICT", ex.getCode());
        // 测区不存在：404
        ex = assertThrows(ApiException.class, () -> publishPolicy(999999, "pv-h",
                0, 4.0, 8.0, 100.0, 0.5, T0, T0));
        assertEquals(404, ex.getStatus());

        // 生效时刻恰等提交时刻允许；版本从1开始
        IdempotencyService.Stored v1 = publishPolicy(zoneId, "pv-1", 0, 4.0, 8.0, 100.0, 0.5, T0, T0);
        assertEquals(201, v1.status());
        assertEquals(1, readJson(v1).get("version").asLong());
        // 重复发布须携带新版本号
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "pv-2",
                0, 3.0, 7.0, 100.0, 0.5, T0, T0));
        assertEquals(409, ex.getStatus());
        IdempotencyService.Stored v2 = publishPolicy(zoneId, "pv-3", 1, 3.0, 7.0, 100.0, 0.5,
                T0.plusHours(2), T0.plusHours(1));
        assertEquals(201, v2.status());
        assertEquals(2, readJson(v2).get("version").asLong(), "成功发布版本加1");

        var versions = slopeService.policyVersions(zoneId);
        assertEquals(2, versions.size());
        assertEquals(4.0, versions.get(0).yellowHourlyRate(), 1e-9);
        assertEquals(3.0, versions.get(1).yellowHourlyRate(), 1e-9);
        assertEquals(T0.plusHours(2), versions.get(1).effectiveAt());
    }

    @Test
    void policyOnlyAffectsReadingsAtOrAfterEffectiveAt() {
        long zoneId = createZone("RP-PT1");
        // 策略：黄1.0/红8.0，T0+10h生效；之前按测区静态阈值（黄5.0）
        IdempotencyService.Stored published = publishPolicy(zoneId, "pt-p1", 0, 1.0, 8.0, 1000.0,
                1.0, T0.plusHours(10), T0.plusHours(10));
        assertEquals(201, published.status());
        reportRainfallRange(zoneId, "pt-r", T0.minusHours(24), T0.plusHours(12), 0.0);

        JsonNode base = ingest(zoneId, "pt-r0", T0, 0.0);
        assertEquals("NORMAL", base.get("evaluation").asText());
        // 生效前：小时速率2.0低于静态黄阈值5.0 → 正常
        JsonNode before = ingest(zoneId, "pt-r1", T0.plusHours(1), 2.0);
        assertEquals("NORMAL", before.get("evaluation").asText(), "生效前按测区静态阈值判定");
        // 生效后：小时速率2.0超过策略黄阈值1.0 → 黄色预警
        JsonNode after = ingest(zoneId, "pt-r2", T0.plusHours(11), 22.0);
        assertEquals(2.0, after.get("hourlyRate").asDouble(), 1e-9);
        assertEquals("YELLOW_CHAIN_CREATED", after.get("evaluation").asText(),
                "生效后按策略阈值判定");

        EffectiveThresholdResponse beforeQuery = slopeService.effectiveThreshold(zoneId,
                T0.plusHours(1));
        assertEquals("NO_POLICY", beforeQuery.status());
        EffectiveThresholdResponse afterQuery = slopeService.effectiveThreshold(zoneId,
                T0.plusHours(11));
        assertEquals("OK", afterQuery.status());
        assertEquals(1, afterQuery.policyVersion());
    }

    // ---------------------------------------------------------------
    // 有效阈值：恰达门槛折减、恰等阈值判正常、缺点不当零
    // ---------------------------------------------------------------

    @Test
    void reductionAppliesExactlyAtThresholdAndRateEqualThresholdJudgedNormal() {
        long zoneId = createZone("RP-RB1");
        // 黄4.0/红8.0，门槛100，系数0.5 → 折减后黄2.0/红4.0
        publishPolicy(zoneId, "rb-p1", 0, 4.0, 8.0, 100.0, 0.5, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "rb-r", T0.minusHours(72), T0.plusHours(48), 4.0);
        // 更正T0-2h为8.0：包含该小时的24小时窗口雨量合计恰为100（96-4+8）
        IdempotencyService.Stored corrected = reportRainfall(zoneId, "rb-fix",
                T0.minusHours(2), 8.0, 1L);
        assertEquals(201, corrected.status());

        // 有效阈值查询：雨量恰达门槛启用折减
        EffectiveThresholdResponse eff = slopeService.effectiveThreshold(zoneId, T0.plusHours(1));
        assertEquals("OK", eff.status());
        assertEquals(100.0, eff.rain24hMm(), 1e-9, "24小时雨量合计恰达门槛");
        assertTrue(eff.reductionApplied(), "恰达门槛启用折减");
        assertEquals(2.0, eff.yellowHourlyRate(), 1e-9);
        assertEquals(4.0, eff.redHourlyRate(), 1e-9);

        JsonNode r0 = ingest(zoneId, "rb-r0", T0, 0.0);
        assertEquals("NORMAL", r0.get("evaluation").asText());
        // 小时速率恰等折减后黄阈值2.0 → 正常
        JsonNode r1 = ingest(zoneId, "rb-r1", T0.plusHours(1), 2.0);
        assertEquals(2.0, r1.get("hourlyRate").asDouble(), 1e-9);
        assertEquals("NORMAL", r1.get("evaluation").asText(), "速率恰等折减阈值仍判正常");
        // 速率2.5严格超过折减后黄阈值 → 黄色预警
        JsonNode r2 = ingest(zoneId, "rb-r2", T0.plusHours(2), 4.5);
        assertEquals("YELLOW_CHAIN_CREATED", r2.get("evaluation").asText());
    }

    @Test
    void redBoundaryUnderReducedThreshold() {
        long zoneId = createZone("RP-RB2");
        publishPolicy(zoneId, "rb2-p1", 0, 4.0, 8.0, 100.0, 0.5, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "rb2-r", T0.minusHours(72), T0.plusHours(48), 4.0);
        reportRainfall(zoneId, "rb2-fix", T0.minusHours(2), 8.0, 1L);

        ingest(zoneId, "rb2-r0", T0, 0.0);
        // 小时速率恰等折减后红阈值4.0 → 不触发红色；超过黄阈值2.0 → 黄色
        JsonNode r1 = ingest(zoneId, "rb2-r1", T0.plusHours(1), 4.0);
        assertEquals("YELLOW_CHAIN_CREATED", r1.get("evaluation").asText(), "恰等红阈值不触发红色");
        // 速率4.5严格超过折减后红阈值4.0 → 升级红色
        JsonNode r2 = ingest(zoneId, "rb2-r2", T0.plusHours(2), 8.5);
        assertEquals("CHAIN_UPGRADED_RED", r2.get("evaluation").asText());
        assertEquals("STOPPED", r2.get("zoneStatus").asText());
    }

    @Test
    void missingRainfallHourReturnsInsufficientData() {
        long zoneId = createZone("RP-IN1");
        publishPolicy(zoneId, "in-p1", 0, 4.0, 8.0, 1000.0, 1.0, T0.minusHours(48),
                T0.minusHours(48));
        // 缺T0+5h一个小时
        reportRainfallRange(zoneId, "in-a", T0.minusHours(24), T0.plusHours(5), 0.0);
        reportRainfallRange(zoneId, "in-b", T0.plusHours(6), T0.plusHours(26), 0.0);

        ingest(zoneId, "in-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, "in-r1", T0.plusHours(1), 9.0);
        assertEquals("RED_CHAIN_CREATED", red.get("evaluation").asText());
        // T0+6h的评价窗口包含缺点T0+5h → 数据不足，读数仍入库
        JsonNode insufficient = ingest(zoneId, "in-r6", T0.plusHours(6), 10.0);
        assertEquals("RAINFALL_DATA_INSUFFICIENT", insufficient.get("evaluation").asText(),
                "任一缺点返回数据不足，缺点不当零");
        assertTrue(insufficient.get("readingId").asLong() > 0, "读数正常入库");

        EffectiveThresholdResponse eff = slopeService.effectiveThreshold(zoneId, T0.plusHours(6));
        assertEquals("INSUFFICIENT_DATA", eff.status());
        assertEquals(1, eff.missingHours().size());
        assertEquals(T0.plusHours(5), eff.missingHours().get(0));
        // 补齐缺点后恢复OK
        reportRainfall(zoneId, "in-fix", T0.plusHours(5), 0.0, null);
        assertEquals("OK", slopeService.effectiveThreshold(zoneId, T0.plusHours(6)).status());
    }

    // ---------------------------------------------------------------
    // 幂等与结构化指纹
    // ---------------------------------------------------------------

    @Test
    void newEndpointsIdempotentReplayAndConflict() {
        long zoneId = createZone("RP-ID1");
        // 雨量：同键同参重放首次冻结结果
        IdempotencyService.Stored first = reportRainfall(zoneId, "id-r1", T0, 5.0, null);
        IdempotencyService.Stored replay = reportRainfall(zoneId, "id-r1", T0, 5.0, null);
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rainfall_hourly WHERE zone_id = ?", Integer.class, zoneId));
        // 同键异参：409
        ApiException ex = assertThrows(ApiException.class,
                () -> reportRainfall(zoneId, "id-r1", T0.plusHours(1), 5.0, null));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());

        // 策略：同键同参重放，同键异参409
        IdempotencyService.Stored p1 = publishPolicy(zoneId, "id-p1", 0, 4.0, 8.0, 100.0, 0.5,
                T0, T0);
        IdempotencyService.Stored p1Replay = publishPolicy(zoneId, "id-p1", 0, 4.0, 8.0, 100.0,
                0.5, T0, T0);
        assertEquals(p1.body(), p1Replay.body());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM threshold_policy WHERE zone_id = ?", Integer.class, zoneId));
        ex = assertThrows(ApiException.class, () -> publishPolicy(zoneId, "id-p1", 0, 3.0, 8.0,
                100.0, 0.5, T0, T0));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    }

    @Test
    void structuredFingerprintDistinguishesSeparatorText() {
        // 合法文本含分隔符（换行、竖线、引号）也须与结构边界区分
        String trickyCode = "SEP|A\nB\"C\"";
        String trickyRequestId = "req\n1|x\"y\"";
        IdempotencyService.Stored first = slopeService.createZone(new CreateZoneRequest(
                trickyRequestId, trickyCode, "分隔符测区", 5.0, 50.0, 2.0, 10));
        assertEquals(201, first.status());
        // 同键同参重放
        IdempotencyService.Stored replay = slopeService.createZone(new CreateZoneRequest(
                trickyRequestId, trickyCode, "分隔符测区", 5.0, 50.0, 2.0, 10));
        assertEquals(first.body(), replay.body());
        // 同键异参：409而非误判为重放
        ApiException ex = assertThrows(ApiException.class,
                () -> slopeService.createZone(new CreateZoneRequest(
                        trickyRequestId, trickyCode, "另一个名字", 5.0, 50.0, 2.0, 10)));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
        // 内容被拼接到requestId中的不同键不会误判为同键
        IdempotencyService.Stored other = slopeService.createZone(new CreateZoneRequest(
                "req", "SEP-CLEAN", "干净测区", 5.0, 50.0, 2.0, 10));
        assertEquals(201, other.status());
    }

    @Test
    void rainfallHoursRangeQueryAndInvalidRange() {
        long zoneId = createZone("RP-RQ1");
        reportRainfallRange(zoneId, "rq", T0, T0.plusHours(5), 1.5);
        List<RainfallHourInfo> ranged = slopeService.rainfallHours(zoneId, T0.plusHours(1),
                T0.plusHours(3));
        assertEquals(2, ranged.size(), "范围为左闭右开");
        assertEquals(T0.plusHours(1), ranged.get(0).hourStart());
        ApiException ex = assertThrows(ApiException.class,
                () -> slopeService.rainfallHours(zoneId, T0, null));
        assertEquals(400, ex.getStatus());
        assertEquals("INVALID_RANGE", ex.getCode());
        ex = assertThrows(ApiException.class,
                () -> slopeService.rainfallHours(999999, null, null));
        assertEquals(404, ex.getStatus());
    }
}
