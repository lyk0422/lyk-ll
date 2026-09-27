package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.ArchiveRequest;
import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边坡监测业务规则测试：阈值恰等判正常、红色停产撤人、恰满30分钟确认、
 * 恰满24小时复工申请、双确认、幂等重放与同键异参409、归档终态与迟到事件。
 * 业务时间全部由请求报文控制，即可控时钟。
 */
@SpringBootTest
class SlopeServiceTest {

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
                "zone-" + code, code, "采场边坡" + code, 5.0, 50.0, 2.0, 10));
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
        return readJson(stored);
    }

    private JsonNode ingestWithStatus(long zoneId, String requestId, LocalDateTime measuredAt,
                                      double displacement, int expectedStatus) {
        IdempotencyService.Stored stored = slopeService.ingestReading(zoneId,
                new IngestReadingRequest(requestId, measuredAt, displacement));
        assertEquals(expectedStatus, stored.status());
        return readJson(stored);
    }

    /** 构造红色预警场景：t0位移0，t0+24h位移120（日速率120>50，小时速率恰等5不触发黄色）。返回chainId。 */
    private long triggerRedChain(long zoneId, String tag) {
        ingest(zoneId, tag + "-r0", T0, 0.0);
        JsonNode red = ingest(zoneId, tag + "-r1", T0.plusHours(24), 120.0);
        assertEquals("RED_CHAIN_CREATED", red.get("evaluation").asText());
        long chainId = red.get("chainId").asLong();
        assertEquals("STOPPED", red.get("zoneStatus").asText());
        return chainId;
    }

    private void confirmEvacuation(long chainId, String requestId, LocalDateTime confirmedAt, int arrived) {
        IdempotencyService.Stored stored = slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest(requestId, confirmedAt, arrived));
        assertEquals(201, stored.status());
    }

    // ---------------------------------------------------------------
    // 预警分级
    // ---------------------------------------------------------------

    @Test
    void yellowAlarmWhenHourlyRateStrictlyExceedsThreshold() {
        long zoneId = createZone("Z-YEL");
        ingest(zoneId, "y0", T0, 0.0);
        JsonNode body = ingest(zoneId, "y1", T0.plusHours(1), 6.0);
        assertEquals("YELLOW_CHAIN_CREATED", body.get("evaluation").asText());
        assertEquals(6.0, body.get("hourlyRate").asDouble(), 1e-9);
        assertEquals("NORMAL", body.get("zoneStatus").asText(), "黄色预警不停产");
        assertEquals("YELLOW", jdbcTemplate.queryForObject(
                "SELECT level FROM disposal_chain WHERE id = ?", String.class,
                body.get("chainId").asLong()));
    }

    @Test
    void rateExactlyEqualThresholdJudgedNormal() {
        long zoneId = createZone("Z-EQ");
        // 小时速率恰等预警值5.0 → 正常
        JsonNode first = ingest(zoneId, "e0", T0, 0.0);
        assertEquals("NORMAL", first.get("evaluation").asText());
        JsonNode second = ingest(zoneId, "e1", T0.plusHours(1), 5.0);
        assertEquals(5.0, second.get("hourlyRate").asDouble(), 1e-9);
        assertEquals("NORMAL", second.get("evaluation").asText(), "小时速率恰等阈值判正常");
        // 日速率恰等报警值50.0 → 正常
        JsonNode third = ingest(zoneId, "e2", T0.plusHours(24), 50.0);
        assertEquals(50.0, third.get("dailyRate").asDouble(), 1e-9);
        assertEquals("NORMAL", third.get("evaluation").asText(), "日速率恰等阈值判正常");
        Integer chains = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_chain WHERE zone_id = ?", Integer.class, zoneId);
        assertEquals(0, chains);
    }

    @Test
    void redAlarmStopsProductionAndOpensEvacuationWindow() {
        long zoneId = createZone("Z-RED");
        long chainId = triggerRedChain(zoneId, "red");
        var chain = slopeService.chainDetail(chainId);
        assertEquals("RED", chain.level());
        assertEquals("EXECUTING", chain.status());
        assertEquals(T0.plusHours(24), chain.triggeredAt());
        assertEquals(T0.plusHours(24).plusMinutes(30), chain.evacuateDeadline(),
                "撤人截止时刻为触发后30分钟");
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    @Test
    void yellowChainUpgradedToRedWhenDailyRateExceedsAlarm() {
        long zoneId = createZone("Z-UPG");
        ingest(zoneId, "u0", T0, 0.0);
        JsonNode yellow = ingest(zoneId, "u1", T0.plusHours(1), 6.0);
        assertEquals("YELLOW_CHAIN_CREATED", yellow.get("evaluation").asText());
        JsonNode upgraded = ingest(zoneId, "u2", T0.plusHours(24), 130.0);
        assertEquals("CHAIN_UPGRADED_RED", upgraded.get("evaluation").asText());
        assertEquals("STOPPED", upgraded.get("zoneStatus").asText());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_chain WHERE zone_id = ?", Integer.class, zoneId));
        assertEquals("RED", jdbcTemplate.queryForObject(
                "SELECT level FROM disposal_chain WHERE zone_id = ?", String.class, zoneId));
    }

    // ---------------------------------------------------------------
    // 撤人确认
    // ---------------------------------------------------------------

    @Test
    void evacuationConfirmedExactlyAt30MinutesIsOnTime() {
        long zoneId = createZone("Z-E30");
        long chainId = triggerRedChain(zoneId, "e30");
        LocalDateTime exactDeadline = T0.plusHours(24).plusMinutes(30);
        IdempotencyService.Stored stored = slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest("e30-c1", exactDeadline, 10));
        assertEquals(201, stored.status());
        JsonNode body = readJson(stored);
        assertTrue(body.get("onTime").asBoolean(), "恰满30分钟判定为按时");
        assertEquals("LOCKED", body.get("zoneStatus").asText());
        var chain = slopeService.chainDetail(chainId);
        assertEquals(exactDeadline, chain.evacuatedAt());
        assertNotNull(chain.evacuation());
        assertEquals(10, chain.evacuation().arrivedCount());
        assertEquals("LOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    @Test
    void evacuationAfterDeadlineRejectedWithoutStateChange() {
        long zoneId = createZone("Z-E31");
        long chainId = triggerRedChain(zoneId, "e31");
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest("e31-c1", T0.plusHours(24).plusMinutes(31), 10)));
        assertEquals(422, ex.getStatus());
        assertEquals("EVACUATION_TIMEOUT", ex.getCode());
        assertNull(slopeService.chainDetail(chainId).evacuatedAt());
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    @Test
    void evacuationWithIncompletePersonnelRollsBackLockdown() {
        long zoneId = createZone("Z-E09");
        long chainId = triggerRedChain(zoneId, "e09");
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.confirmEvacuation(chainId,
                new EvacuationConfirmRequest("e09-c1", T0.plusHours(24).plusMinutes(10), 9)));
        assertEquals(422, ex.getStatus());
        assertEquals("PERSONNEL_INCOMPLETE", ex.getCode());
        // 同事务回滚：无确认记录、链未确认、测区未封锁
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evacuation_confirmation WHERE chain_id = ?", Integer.class, chainId));
        assertNull(slopeService.chainDetail(chainId).evacuatedAt());
        assertEquals("STOPPED", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
    }

    // ---------------------------------------------------------------
    // 复工评估
    // ---------------------------------------------------------------

    /** 红色场景 + 按时撤人 + 窗口内低速率读数（t+12h位移121、t+24h位移122）。 */
    private long redChainReadyForResumption(long zoneId, String tag) {
        long chainId = triggerRedChain(zoneId, tag);
        confirmEvacuation(chainId, tag + "-ev", T0.plusHours(24).plusMinutes(10), 10);
        ingest(zoneId, tag + "-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, tag + "-r3", T0.plusHours(48), 122.0);
        return chainId;
    }

    @Test
    void resumptionAllowedExactlyAt24Hours() {
        long zoneId = createZone("Z-R24");
        long chainId = redChainReadyForResumption(zoneId, "r24");
        LocalDateTime exact24h = T0.plusHours(48);
        IdempotencyService.Stored stored = slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("r24-a1", exact24h));
        assertEquals(201, stored.status(), "恰满24小时允许申请");
        assertEquals("APPLIED", readJson(stored).get("status").asText());
    }

    @Test
    void resumptionRejectedBefore24Hours() {
        long zoneId = createZone("Z-R23");
        long chainId = redChainReadyForResumption(zoneId, "r23");
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("r23-a1", T0.plusHours(47))));
        assertEquals(422, ex.getStatus());
        assertEquals("RECOVERY_WINDOW_TOO_SHORT", ex.getCode());
    }

    @Test
    void resumptionRejectedWhenRateNotBelowSafetyLine() {
        long zoneId = createZone("Z-RSF");
        long chainId = triggerRedChain(zoneId, "rsf");
        confirmEvacuation(chainId, "rsf-ev", T0.plusHours(24).plusMinutes(10), 10);
        // 窗口内出现小时速率2.5（≥安全线2.0）
        ingest(zoneId, "rsf-r2", T0.plusHours(36), 150.0);
        ingest(zoneId, "rsf-r3", T0.plusHours(48), 151.0);
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("rsf-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("RATE_ABOVE_SAFETY_LINE", ex.getCode());
    }

    @Test
    void resumptionRejectedWithoutEvacuationConfirmation() {
        long zoneId = createZone("Z-RNE");
        long chainId = triggerRedChain(zoneId, "rne");
        ingest(zoneId, "rne-r2", T0.plusHours(36), 121.0);
        ingest(zoneId, "rne-r3", T0.plusHours(48), 122.0);
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.applyResumption(chainId,
                new ResumptionApplyRequest("rne-a1", T0.plusHours(48))));
        assertEquals(422, ex.getStatus());
        assertEquals("EVACUATION_NOT_CONFIRMED", ex.getCode());
    }

    @Test
    void resumptionRequiresDualConfirmation() {
        long zoneId = createZone("Z-DUAL");
        long chainId = redChainReadyForResumption(zoneId, "dual");
        slopeService.applyResumption(chainId, new ResumptionApplyRequest("dual-a1", T0.plusHours(48)));

        IdempotencyService.Stored first = slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("dual-c1", "SITE_CHECK", T0.plusHours(49)));
        assertEquals(201, first.status());
        JsonNode firstBody = readJson(first);
        assertEquals("APPLIED", firstBody.get("assessmentStatus").asText(), "单确认不批准");
        assertEquals("EXECUTING", firstBody.get("chainStatus").asText());
        assertEquals("LOCKED", firstBody.get("zoneStatus").asText());

        IdempotencyService.Stored second = slopeService.confirmResumption(chainId,
                new ResumptionConfirmRequest("dual-c2", "MONITORING_REVIEW", T0.plusHours(50)));
        JsonNode secondBody = readJson(second);
        assertEquals("APPROVED", secondBody.get("assessmentStatus").asText(), "双确认后批准");
        assertEquals("RESUMED", secondBody.get("chainStatus").asText());
        assertEquals("NORMAL", secondBody.get("zoneStatus").asText());
        // 唯一终态：测区恢复正常，不存在既停产又批准复工
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT status FROM slope_zone WHERE id = ?", String.class, zoneId));
        assertEquals("RESUMED", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_chain WHERE id = ?", String.class, chainId));
    }

    // ---------------------------------------------------------------
    // 幂等
    // ---------------------------------------------------------------

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        long zoneId = createZone("Z-IDM");
        IdempotencyService.Stored first = slopeService.ingestReading(zoneId,
                new IngestReadingRequest("idem-1", T0, 0.0));
        IdempotencyService.Stored replay = slopeService.ingestReading(zoneId,
                new IngestReadingRequest("idem-1", T0, 0.0));
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body(), "同键同参返回首次结果");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM radar_reading WHERE zone_id = ?", Integer.class, zoneId));
    }

    @Test
    void sameKeyDifferentParamsReturns409WithoutStateChange() {
        long zoneId = createZone("Z-IDC");
        slopeService.ingestReading(zoneId, new IngestReadingRequest("idem-c", T0, 0.0));
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.ingestReading(zoneId,
                new IngestReadingRequest("idem-c", T0.plusHours(1), 6.0)));
        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM radar_reading WHERE zone_id = ?", Integer.class, zoneId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_chain WHERE zone_id = ?", Integer.class, zoneId),
                "同键异参不改变测区状态");
    }

    @Test
    void duplicateReadingByZoneAndTimestampDeduplicated() {
        long zoneId = createZone("Z-DUP");
        ingest(zoneId, "d0", T0, 0.0);
        JsonNode dup = ingestWithStatus(zoneId, "d1", T0, 3.0, 200);
        assertTrue(dup.get("duplicated").asBoolean(), "测区＋时刻去重");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM radar_reading WHERE zone_id = ?", Integer.class, zoneId));
    }

    @Test
    void outOfOrderReadingsRepositionedByTimestamp() {
        long zoneId = createZone("Z-ORD");
        ingest(zoneId, "o2", T0.plusHours(2), 4.0);
        ingest(zoneId, "o0", T0, 0.0);
        ingest(zoneId, "o1", T0.plusHours(1), 1.0);
        RateCurveResponse curve = slopeService.rateCurve(zoneId);
        assertEquals(3, curve.points().size());
        assertEquals(T0, curve.points().get(0).measuredAt());
        assertEquals(T0.plusHours(1), curve.points().get(1).measuredAt());
        assertEquals(T0.plusHours(2), curve.points().get(2).measuredAt());
        assertNull(curve.points().get(0).hourlyRate());
        assertEquals(1.0, curve.points().get(1).hourlyRate(), 1e-9);
        assertEquals(3.0, curve.points().get(2).hourlyRate(), 1e-9, "乱序归位后速率按时刻复算");
    }

    // ---------------------------------------------------------------
    // 归档终态与迟到事件
    // ---------------------------------------------------------------

    @Test
    void archiveIsTerminalAndRepeatArchiveIdempotent() {
        long zoneId = createZone("Z-ARC");
        ingest(zoneId, "a0", T0, 0.0);
        JsonNode yellow = ingest(zoneId, "a1", T0.plusHours(1), 6.0);
        long chainId = yellow.get("chainId").asLong();

        IdempotencyService.Stored archived = slopeService.archiveChain(chainId,
                new ArchiveRequest("arc-1", T0.plusHours(2)));
        assertEquals(200, archived.status());
        assertEquals("ARCHIVED", readJson(archived).get("status").asText());

        IdempotencyService.Stored repeat = slopeService.archiveChain(chainId,
                new ArchiveRequest("arc-2", T0.plusHours(3)));
        assertEquals(200, repeat.status(), "重复归档返回幂等成功");
        assertEquals(T0.plusHours(2), slopeService.chainDetail(chainId).archivedAt(),
                "归档时刻保持首次值");

        // 归档后迟到位移只记迟到事件
        JsonNode late = ingestWithStatus(zoneId, "a2", T0.plusMinutes(30), 2.0, 201);
        assertTrue(late.get("recordedLate").asBoolean());
        assertEquals(1, slopeService.lateEvents(zoneId).size());
        assertEquals("ARCHIVED_LATE_DATA", slopeService.lateEvents(zoneId).get(0).reason());
        assertEquals(2, slopeService.rateCurve(zoneId).points().size(), "迟到数据不进入速率曲线");
    }

    @Test
    void redChainCannotArchiveBeforeResumption() {
        long zoneId = createZone("Z-ANR");
        long chainId = triggerRedChain(zoneId, "anr");
        ApiException ex = assertThrows(ApiException.class, () -> slopeService.archiveChain(chainId,
                new ArchiveRequest("anr-1", T0.plusHours(30))));
        assertEquals(409, ex.getStatus());
        assertEquals("CHAIN_NOT_RESUMED", ex.getCode());
    }

    @Test
    void rateCurveRecomputableFromDisplacements() {
        long zoneId = createZone("Z-RCV");
        ingest(zoneId, "rc0", T0, 0.0);
        ingest(zoneId, "rc1", T0.plusHours(2), 4.0);
        ingest(zoneId, "rc2", T0.plusHours(26), 28.0);
        RateCurveResponse curve = slopeService.rateCurve(zoneId);
        assertEquals(3, curve.points().size());
        assertEquals(2.0, curve.points().get(1).hourlyRate(), 1e-9);
        assertEquals(1.0, curve.points().get(2).hourlyRate(), 1e-9);
        assertEquals(24.0, curve.points().get(2).dailyRate(), 1e-9,
                "日速率相对24小时前基准点(t+2h,位移4)复算: (28-4)/1天=24");
        assertFalse(curve.points().get(0).measuredAt().isAfter(curve.points().get(1).measuredAt()));
    }
}
