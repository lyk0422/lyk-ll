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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复工安全门禁测试：连续24小时按每个时刻实际有效阈值判定、策略变严后重新累计、
 * 变宽不撤销既有红色处置链、雨量缺点阻断申请与批准、批准前复评并冻结一致证据。
 * 业务时间全部由请求报文控制，即可控时钟。
 */
@SpringBootTest
class ResumptionGateServiceTest {

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

    // ---------------------------------------------------------------
    // 测试辅助
    // ---------------------------------------------------------------

    private long createZone(String code) {
        IdempotencyService.Stored stored = slopeService.createZone(new CreateZoneRequest(
                "zone-" + code, code, "门禁测区" + code, 5.0, 50.0, 2.0, 10));
        return readJson(stored).get("zoneId").asLong();
    }

    private JsonNode readJson(IdempotencyService.Stored stored) {
        try {
            return objectMapper.readTree(stored.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void reportRainfall(long zoneId, String requestId, LocalDateTime hour, double value,
                                Long expectedVersion) {
        slopeService.reportRainfall(zoneId,
                new RainfallReportRequest(requestId, hour, value, expectedVersion, hour));
    }

    private void reportRainfallRange(long zoneId, String tag, LocalDateTime from,
                                     LocalDateTime to, double value) {
        for (LocalDateTime h = from; h.isBefore(to); h = h.plusHours(1)) {
            reportRainfall(zoneId, tag + "-" + h, h, value, null);
        }
    }

    private void publishPolicy(long zoneId, String requestId, long expectedVersion, double yellow,
                               double red, double rainThreshold, double factor,
                               LocalDateTime effectiveAt, LocalDateTime submittedAt) {
        IdempotencyService.Stored stored = slopeService.publishPolicy(zoneId,
                new PolicyPublishRequest(requestId, expectedVersion, yellow, red, rainThreshold,
                        factor, effectiveAt, submittedAt));
        assertEquals(201, stored.status(), "策略发布失败: " + requestId);
    }

    private JsonNode ingest(long zoneId, String requestId, LocalDateTime measuredAt,
                            double displacement) {
        return readJson(slopeService.ingestReading(zoneId,
                new IngestReadingRequest(requestId, measuredAt, displacement)));
    }

    /** 策略生效下构造红色链：(T0,0)、(T0+1h,9.0)，小时速率9>红阈值8。返回chainId。 */
    private long triggerRedChain(long zoneId, String tag) {
        ingest(zoneId, tag + "-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, tag + "-r1", T0.plusHours(1), 9.0);
        assertEquals("RED_CHAIN_CREATED", red.get("evaluation").asText());
        return red.get("chainId").asLong();
    }

    private void confirmEvacuation(long chainId, String requestId, LocalDateTime confirmedAt) {
        IdempotencyService.Stored stored = slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(requestId, confirmedAt, 10));
        assertEquals(201, stored.status());
    }

    /** 上报T0+fromHour至T0+toHour的回落读数，每小时速率ratePerHour（T0+1h位移为9.0）。 */
    private void ingestSafeReadings(long zoneId, String tag, int fromHour, int toHour,
                                    double ratePerHour) {
        for (int h = fromHour; h <= toHour; h++) {
            double displacement = 9.0 + ratePerHour * (h - 1);
            ingest(zoneId, tag + "-s" + h, T0.plusHours(h), displacement);
        }
    }

    private IdempotencyService.Stored applyResumption(long chainId, String requestId,
                                                      LocalDateTime appliedAt) {
        return slopeService.applyResumption(chainId, new ResumptionApplyRequest(requestId, appliedAt));
    }

    private IdempotencyService.Stored confirmResumption(long chainId, String requestId,
                                                        String type, LocalDateTime confirmedAt) {
        return slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest(requestId, type, confirmedAt));
    }

    // ---------------------------------------------------------------
    // 雨量缺点阻断复工申请与批准
    // ---------------------------------------------------------------

    @Test
    void missingRainfallBlocksResumptionApplyUntilFixed() {
        long zoneId = createZone("RG-IN1");
        publishPolicy(zoneId, "in1-p1", 0, 4.0, 8.0, 1000.0, 1.0, T0.minusHours(48),
                T0.minusHours(48));
        // 缺T0+5h一个小时
        reportRainfallRange(zoneId, "in1-a", T0.minusHours(24), T0.plusHours(5), 0.0);
        reportRainfallRange(zoneId, "in1-b", T0.plusHours(6), T0.plusHours(26), 0.0);

        long chainId = triggerRedChain(zoneId, "in1");
        confirmEvacuation(chainId, "in1-ev", T0.plusHours(1).plusMinutes(10));
        ingestSafeReadings(zoneId, "in1", 2, 25, 0.5);

        // 窗口内T0+6h读数的评价缺T0+5h雨量 → 数据不足，阻断申请
        ApiException ex = assertThrows(ApiException.class,
                () -> applyResumption(chainId, "in1-a1", T0.plusHours(25)));
        assertEquals(422, ex.getStatus());
        assertEquals("RAINFALL_DATA_INSUFFICIENT", ex.getCode(), "缺点不当零，阻断新复工批准");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumption_assessment WHERE chain_id = ?", Integer.class,
                chainId));

        // 补齐缺点后放行
        reportRainfall(zoneId, "in1-fix", T0.plusHours(5), 0.0, null);
        IdempotencyService.Stored applied = applyResumption(chainId, "in1-a2", T0.plusHours(25));
        assertEquals(201, applied.status());
        assertEquals("APPLIED", readJson(applied).get("status").asText());
    }

    // ---------------------------------------------------------------
    // 策略变严后重新累计安全时长
    // ---------------------------------------------------------------

    @Test
    void stricterPolicyRestartsSafetyAccumulation() {
        long zoneId = createZone("RG-ST1");
        publishPolicy(zoneId, "st1-p1", 0, 4.0, 8.0, 1000.0, 1.0, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "st1-r", T0.minusHours(24), T0.plusHours(38), 0.0);

        long chainId = triggerRedChain(zoneId, "st1");
        confirmEvacuation(chainId, "st1-ev", T0.plusHours(1).plusMinutes(10));
        ingestSafeReadings(zoneId, "st1", 2, 37, 0.5);

        // 发布更严策略v2（黄3.0<4.0），T0+13h生效
        publishPolicy(zoneId, "st1-p2", 1, 3.0, 6.0, 1000.0, 1.0, T0.plusHours(13),
                T0.plusHours(12));

        // 不沿用旧安全计时：按旧窗口T0+25h申请被拒（须自T0+13h重新累计24小时）
        ApiException ex = assertThrows(ApiException.class,
                () -> applyResumption(chainId, "st1-a1", T0.plusHours(25)));
        assertEquals(422, ex.getStatus());
        assertEquals("RECOVERY_WINDOW_TOO_SHORT", ex.getCode());

        // 自变严版本生效时刻重新累计满24小时（恰满允许）后申请成功
        IdempotencyService.Stored applied = applyResumption(chainId, "st1-a2", T0.plusHours(37));
        assertEquals(201, applied.status(), "变严版本生效后重新累计满24小时允许申请");

        // 证据冻结：窗口起点为最迟变严版本生效时刻
        List<ResumptionEvidenceInfo> evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(1, evidence.size());
        assertEquals("APPLY", evidence.get(0).phase());
        assertEquals(T0.plusHours(13), evidence.get(0).windowStart(), "变严后自生效时刻重新累计");
        assertEquals(T0.plusHours(37), evidence.get(0).windowEnd());

        // 双确认后批准，批准证据冻结
        assertEquals(201, confirmResumption(chainId, "st1-c1", "SITE_CHECK",
                T0.plusHours(38)).status());
        IdempotencyService.Stored approved = confirmResumption(chainId, "st1-c2",
                "MONITORING_REVIEW", T0.plusHours(39));
        assertEquals("APPROVED", readJson(approved).get("assessmentStatus").asText());
        evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(2, evidence.size());
        assertEquals("APPROVE", evidence.get(1).phase());
        assertEquals("PASS", evidence.get(1).result());
    }

    @Test
    void looserPolicyKeepsExistingRedChain() {
        long zoneId = createZone("RG-LS1");
        publishPolicy(zoneId, "ls1-p1", 0, 4.0, 8.0, 1000.0, 1.0, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "ls1-r", T0.minusHours(24), T0.plusHours(26), 0.0);

        long chainId = triggerRedChain(zoneId, "ls1");
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));

        // 发布更宽策略v2：不撤销既有红色处置链
        publishPolicy(zoneId, "ls1-p2", 1, 10.0, 20.0, 1000.0, 1.0, T0.plusHours(2),
                T0.plusHours(1).plusMinutes(30));
        var chain = slopeService.chainDetail(chainId);
        assertEquals("RED", chain.level());
        assertEquals("EXECUTING", chain.status(), "变宽不撤销既有红色处置链");
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));

        JsonNode follow = ingest(zoneId, "ls1-r2", T0.plusHours(2), 9.5);
        assertEquals("RED_CHAIN_ACTIVE", follow.get("evaluation").asText());
    }

    // ---------------------------------------------------------------
    // 雨量更正使批准前复评失败；修正后批准并冻结一致证据
    // ---------------------------------------------------------------

    @Test
    void rainfallCorrectionInvalidatesApprovalUntilConsistentAgain() {
        long zoneId = createZone("RG-RC1");
        // 黄4.0/红8.0，门槛100，系数0.5 → 折减后黄2.0
        publishPolicy(zoneId, "rc1-p1", 0, 4.0, 8.0, 100.0, 0.5, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "rc1-r", T0.minusHours(24), T0.plusHours(26), 0.0);

        long chainId = triggerRedChain(zoneId, "rc1");
        confirmEvacuation(chainId, "rc1-ev", T0.plusHours(1).plusMinutes(10));
        // 回落读数速率3.0/小时：低于未折减黄阈值4.0，但高于折减后2.0
        ingestSafeReadings(zoneId, "rc1", 2, 25, 3.0);

        IdempotencyService.Stored applied = applyResumption(chainId, "rc1-a1", T0.plusHours(25));
        assertEquals(201, applied.status());
        assertEquals(201, confirmResumption(chainId, "rc1-c1", "SITE_CHECK",
                T0.plusHours(26)).status());

        // 雨量更正：T0+10h更正为200 → 窗口雨量达门槛启用折减 → 速率3.0≥折减阈值2.0
        reportRainfall(zoneId, "rc1-fix1", T0.plusHours(10), 200.0, 1L);
        ApiException ex = assertThrows(ApiException.class, () -> confirmResumption(chainId,
                "rc1-c2", "MONITORING_REVIEW", T0.plusHours(27)));
        assertEquals(422, ex.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", ex.getCode(), "批准前复评门禁，更正后不再满足");
        // 本次确认回滚：评估仍未批准、测区仍封锁
        var assessment = slopeService.chainDetail(chainId).assessment();
        assertEquals("APPLIED", assessment.status());
        assertTrue(!assessment.monitoringReviewConfirmed());
        assertEquals("LOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));

        // 更正回0.0后重试确认 → 批准
        reportRainfall(zoneId, "rc1-fix2", T0.plusHours(10), 0.0, 2L);
        IdempotencyService.Stored approved = confirmResumption(chainId, "rc1-c3",
                "MONITORING_REVIEW", T0.plusHours(28));
        assertEquals(201, approved.status());
        assertEquals("APPROVED", readJson(approved).get("assessmentStatus").asText());
        assertEquals("RESUMED", readJson(approved).get("chainStatus").asText());
        assertEquals("NORMAL", readJson(approved).get("zoneStatus").asText());

        // 冻结证据：APPLY与APPROVE两阶段，雨量与阈值快照一致（均为更正前/后的0.0与4.0）
        List<ResumptionEvidenceInfo> evidence = slopeService.resumptionEvidence(chainId);
        assertEquals(2, evidence.size());
        assertEquals("APPLY", evidence.get(0).phase());
        assertEquals("APPROVE", evidence.get(1).phase());
        for (ResumptionEvidenceInfo item : evidence) {
            assertEquals(T0.plusHours(1), item.windowStart());
            assertEquals(T0.plusHours(25), item.windowEnd());
            JsonNode detail = readJsonString(item.detail());
            JsonNode firstReading = detail.get("readings").get(0);
            assertEquals(0.0, firstReading.get("rain24hMm").asDouble(), 1e-9);
            assertEquals(4.0, firstReading.get("threshold").asDouble(), 1e-9);
            assertEquals(1, firstReading.get("policyVersion").asLong());
            assertTrue(firstReading.get("pass").asBoolean());
        }
    }

    private JsonNode readJsonString(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------
    // 复工证据查询边界
    // ---------------------------------------------------------------

    @Test
    void resumptionEvidenceQueryBoundaries() {
        ApiException ex = assertThrows(ApiException.class,
                () -> slopeService.resumptionEvidence(999999));
        assertEquals(404, ex.getStatus());
        assertEquals("CHAIN_NOT_FOUND", ex.getCode());

        long zoneId = createZone("RG-EV1");
        publishPolicy(zoneId, "ev1-p1", 0, 4.0, 8.0, 1000.0, 1.0, T0.minusHours(48),
                T0.minusHours(48));
        reportRainfallRange(zoneId, "ev1-r", T0.minusHours(24), T0.plusHours(26), 0.0);
        long chainId = triggerRedChain(zoneId, "ev1");
        // 未申请复工的链：证据为空列表
        assertEquals(0, slopeService.resumptionEvidence(chainId).size());
    }
}
