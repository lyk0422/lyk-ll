package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulInspectionRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.RegisterCertificateRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 年度检验与大修后开放资格门禁测试（真实 H2，MySQL 兼容模式）。
 * 测试时钟固定为 2026-09-26T01:00:00Z，证书时刻均为 UTC 合成数据。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionServiceTest {

    /** 测试时钟当前时刻（UTC）。 */
    private static final Instant NOW = TestClockConfig.FIXED_NOW;
    /** 有效证书的合格时刻：默认截止为 +365×24h，晚于当前时刻。 */
    private static final Instant VALID_PASSED_AT = Instant.parse("2026-09-20T00:00:00Z");
    /** 过期证书的合格时刻：默认截止早于当前时刻。 */
    private static final Instant EXPIRED_PASSED_AT = Instant.parse("2025-09-01T00:00:00Z");

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private long rideId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM overhaul_inspection_item");
        jdbc.update("DELETE FROM overhaul_inspection");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        rideId = ((Number) service.createRide(
                new CreateRideRequest("受检过山车", 10, 80, true)).get("rideId")).longValue();
    }

    private long createInspectedRide() {
        return ((Number) service.createRide(
                new CreateRideRequest("受检摩天轮", 12, 40, true)).get("rideId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ApiResult result) {
        try {
            return objectMapper.readValue(result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(long id) {
        return (String) service.getRide(id).get("status");
    }

    private long versionOf(long id) {
        return ((Number) service.getRide(id).get("version")).longValue();
    }

    private void dailyCheckOk(long id, String requestId) {
        ApiResult result = service.dailyCheck(id, new DailyCheckRequest(requestId, List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        assertEquals(200, result.status());
    }

    private void makeReady(long id, String keyPrefix) {
        dailyCheckOk(id, keyPrefix + "-check");
        for (int i = 1; i <= 3; i++) {
            ApiResult result = service.trialRun(id, new TrialRunRequest(keyPrefix + "-t" + i, true));
            assertEquals(200, result.status());
        }
    }

    /** 登记有效证书并断言成功，返回证书ID。 */
    private long registerValidCertificate(long id, String requestId) {
        ApiResult result = service.registerCertificate(id,
                new RegisterCertificateRequest(requestId, versionOf(id), VALID_PASSED_AT, null));
        assertEquals(200, result.status());
        Map<String, Object> cert = (Map<String, Object>) bodyOf(result).get("certificate");
        return ((Number) cert.get("certificateId")).longValue();
    }

    private void makeOpenWithCertificate(long id, String keyPrefix) {
        makeReady(id, keyPrefix);
        registerValidCertificate(id, keyPrefix + "-cert");
        ApiResult result = service.open(id, new OpenRequest(keyPrefix + "-open"));
        assertEquals(200, result.status());
    }

    private OverhaulInspectionRequest passInspection(String requestId, long expectedVersion) {
        return new OverhaulInspectionRequest(requestId, expectedVersion, "检验员甲", "检验员乙",
                List.of(new CheckItem("主体结构", true), new CheckItem("制动系统", true)));
    }

    @Test
    @DisplayName("受检设备无当前年度证书禁止开放，资格证据给出原因")
    void inspectedRideWithoutCertificateCannotOpen() {
        makeReady(rideId, "k");
        ApiException e = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(422, e.status().value());
        assertTrue(e.getMessage().contains("无当前年度检验证书"));
        assertEquals("READY", statusOf(rideId));

        Map<String, Object> evidence = service.eligibilityEvidence(rideId);
        assertEquals(false, evidence.get("eligible"));
        assertEquals(true, evidence.get("inspectionRequired"));
        assertTrue(evidence.get("reasons").toString().contains("无当前年度检验证书"));
    }

    @Test
    @DisplayName("登记证书后受检设备可开放：默认截止为合格时刻加365×24小时，版本从1开始成功变更加1")
    void registerCertificateEnablesOpen() {
        assertEquals(1, versionOf(rideId), "设备版本从1开始");
        makeReady(rideId, "k");
        long beforeRegister = versionOf(rideId);
        ApiResult registered = service.registerCertificate(rideId,
                new RegisterCertificateRequest("k-cert", beforeRegister, VALID_PASSED_AT, null));
        assertEquals(200, registered.status());
        Map<String, Object> cert = (Map<String, Object>) bodyOf(registered).get("certificate");
        assertEquals(VALID_PASSED_AT.toString(), cert.get("passedAt"));
        assertEquals(VALID_PASSED_AT.plusSeconds(365L * 24 * 3600).toString(),
                cert.get("validUntil"), "默认截止为合格时刻加365×24小时");
        assertEquals(true, cert.get("current"));
        assertEquals(beforeRegister + 1, versionOf(rideId), "成功变更版本加1");

        ApiResult opened = service.open(rideId, new OpenRequest("k-open"));
        assertEquals(200, opened.status());
        assertEquals("OPEN", bodyOf(opened).get("status"));
        assertEquals(true, service.eligibilityEvidence(rideId).get("eligible"));
    }

    @Test
    @DisplayName("过期证书与恰截止证书均失效：开放被拒绝422")
    void expiredOrBoundaryCertificateCannotOpen() {
        // 过期证书：默认截止早于now
        makeReady(rideId, "k");
        ApiResult expired = service.registerCertificate(rideId,
                new RegisterCertificateRequest("k-cert", versionOf(rideId), EXPIRED_PASSED_AT, null));
        assertEquals(200, expired.status());
        ApiException e = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(422, e.status().value());
        assertTrue(e.getMessage().contains("已过期或恰截止失效"));

        // 恰截止失效：validUntil == now，换证后仍不可开放
        long other = createInspectedRide();
        makeReady(other, "o");
        service.registerCertificate(other, new RegisterCertificateRequest(
                "o-cert", versionOf(other), VALID_PASSED_AT, NOW));
        ApiException boundary = assertThrows(ApiException.class,
                () -> service.open(other, new OpenRequest("o-open")));
        assertEquals(422, boundary.status().value());
        assertTrue(boundary.getMessage().contains("恰截止失效"));
    }

    @Test
    @DisplayName("开放后撤销证书：载客读数只记录并告警，不恢复资格；换证后恢复")
    void revokedCertificateReadingOnlyAlarms() {
        makeOpenWithCertificate(rideId, "k");
        long certId = ((Number) service.getRide(rideId).get("currentCertificateId")).longValue();

        ApiResult revoked = service.revokeCertificate(rideId, certId,
                new RevokeCertificateRequest("k-revoke", versionOf(rideId), "合成撤销"));
        assertEquals(200, revoked.status());
        assertEquals(true, ((Map<String, Object>) bodyOf(revoked).get("certificate")).get("revoked"));
        assertEquals(false, service.eligibilityEvidence(rideId).get("eligible"));

        // 设备仍处 OPEN，但资格失效：读数只记录并告警，不缓停也不恢复资格
        Map<String, Object> reading = service.reading(rideId, new ReadingRequest(5, 50));
        assertEquals(true, reading.get("certificateAlarm"));
        assertEquals("OPEN", statusOf(rideId));
        assertTrue(service.listEvents(rideId).stream()
                .anyMatch(ev -> "CERT_ALARM".equals(ev.get("event_type"))));
        assertEquals(false, service.eligibilityEvidence(rideId).get("eligible"));

        // 正常读数不恢复资格；换证后资格恢复、读数回归正常
        service.registerCertificate(rideId,
                new RegisterCertificateRequest("k-cert2", versionOf(rideId), VALID_PASSED_AT, null));
        assertEquals(true, service.eligibilityEvidence(rideId).get("eligible"));
        Map<String, Object> normal = service.reading(rideId, new ReadingRequest(5, 50));
        assertEquals(false, normal.get("exceeded"));
        assertFalse(normal.containsKey("certificateAlarm"));
    }

    @Test
    @DisplayName("换证原子切换唯一当前指针，历史证书不可覆盖；撤销当前证书后旧证书不自动生效")
    void certificateSwitchKeepsHistory() {
        makeReady(rideId, "k");
        long first = registerValidCertificate(rideId, "k-cert1");
        long second = registerValidCertificate(rideId, "k-cert2");
        assertNotEquals(first, second);

        List<Map<String, Object>> certs = service.listCertificates(rideId);
        assertEquals(2, certs.size());
        assertEquals(false, certs.get(0).get("current"));
        assertEquals(true, certs.get(1).get("current"), "唯一当前指针指向新证书");
        assertEquals(false, certs.get(0).get("revoked"));

        // 撤销当前证书：历史证书可查但不自动生效，开放资格失效
        service.revokeCertificate(rideId, second,
                new RevokeCertificateRequest("k-revoke", versionOf(rideId), "合成撤销"));
        certs = service.listCertificates(rideId);
        assertEquals(2, certs.size(), "历史证书不可覆盖");
        assertEquals(true, certs.get(1).get("revoked"));
        ApiException e = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(422, e.status().value());
    }

    @Test
    @DisplayName("重复撤销幂等成功；撤销不存在证书404；撤销他设备证书404")
    void revokeIdempotentAndNotFound() {
        makeReady(rideId, "k");
        long certId = registerValidCertificate(rideId, "k-cert");
        service.revokeCertificate(rideId, certId,
                new RevokeCertificateRequest("k-revoke", versionOf(rideId), "合成撤销"));
        ApiResult again = service.revokeCertificate(rideId, certId,
                new RevokeCertificateRequest("k-revoke-2", versionOf(rideId), "再次撤销"));
        assertEquals(200, again.status());
        assertTrue(bodyOf(again).get("message").toString().contains("幂等成功"));

        ApiException notFound = assertThrows(ApiException.class,
                () -> service.revokeCertificate(rideId, 999999,
                        new RevokeCertificateRequest("k-revoke-3", versionOf(rideId), null)));
        assertEquals(404, notFound.status().value());

        long other = createInspectedRide();
        long otherCert = registerValidCertificate(other, "o-cert");
        ApiException crossRide = assertThrows(ApiException.class,
                () -> service.revokeCertificate(rideId, otherCert,
                        new RevokeCertificateRequest("k-revoke-4", versionOf(rideId), null)));
        assertEquals(404, crossRide.status().value());
    }

    @Test
    @DisplayName("大修登记：原子递增大修版本、暂停开放、旧监督检验失效，旧年度证书不足以复开")
    void overhaulSuspendsAndInvalidatesOldInspection() {
        makeOpenWithCertificate(rideId, "k");
        // 先复开一次，验证大修后不重置当日复开次数
        service.safetyFault(rideId, new com.example.starter.ride.dto.RideDtos
                .SafetyFaultRequest("k-f1", "压杠"));
        service.recheck(rideId, new RecheckRequest("k-r1", List.of(new CheckItem("压杠", true))));
        ApiResult reopen1 = service.reopen(rideId, new ReopenRequest("k-re1"));
        assertEquals(1, bodyOf(reopen1).get("reopenCount"));

        // 登记大修：版本递增、暂停开放
        ApiResult overhaul = service.registerOverhaul(rideId,
                new OverhaulRequest("k-oh1", versionOf(rideId)));
        assertEquals(200, overhaul.status());
        assertEquals(1, bodyOf(overhaul).get("overhaulVersion"));
        assertEquals("SUSPENDED", statusOf(rideId));

        // 旧年度证书仍有效但不足以复开：复检合格后复开被门禁拒绝
        service.recheck(rideId, new RecheckRequest("k-r2", List.of(new CheckItem("压杠", true))));
        ApiException e = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("k-re2")));
        assertEquals(422, e.status().value());
        assertTrue(e.getMessage().contains("监督检验未确认"));

        // 双人确认全清单合格：只恢复资格，不自动开放，不重置复开次数
        ApiResult confirmed = service.confirmOverhaulInspection(rideId,
                passInspection("k-insp1", versionOf(rideId)));
        assertEquals(200, confirmed.status());
        assertEquals(true, bodyOf(confirmed).get("allPassed"));
        assertEquals("RECHECKED", statusOf(rideId), "确认后不自动开放");
        assertEquals(true, service.eligibilityEvidence(rideId).get("eligible"));

        ApiResult reopen2 = service.reopen(rideId, new ReopenRequest("k-re2b"));
        assertEquals(200, reopen2.status());
        assertEquals(2, bodyOf(reopen2).get("reopenCount"), "复开次数延续不重置");
    }

    @Test
    @DisplayName("新大修使旧监督检验失效：须绑定当前大修版本重新确认")
    void newOverhaulInvalidatesPreviousInspection() {
        makeOpenWithCertificate(rideId, "k");
        service.registerOverhaul(rideId, new OverhaulRequest("k-oh1", versionOf(rideId)));
        service.confirmOverhaulInspection(rideId, passInspection("k-insp1", versionOf(rideId)));
        assertEquals(true, service.eligibilityEvidence(rideId).get("eligible"));

        // 再次大修：旧监督检验失效
        service.registerOverhaul(rideId, new OverhaulRequest("k-oh2", versionOf(rideId)));
        Map<String, Object> evidence = service.eligibilityEvidence(rideId);
        assertEquals(false, evidence.get("eligible"));
        assertEquals(2, evidence.get("overhaulVersion"));
        assertEquals(false, evidence.get("overhaulInspectionConfirmed"));

        // 绑定当前大修版本重新确认后恢复
        service.confirmOverhaulInspection(rideId, passInspection("k-insp2", versionOf(rideId)));
        assertEquals(true, service.eligibilityEvidence(rideId).get("eligible"));
        List<Map<String, Object>> inspections = service.listOverhaulInspections(rideId);
        assertEquals(2, inspections.size());
        assertEquals(1, inspections.get(0).get("overhaul_version"));
        assertEquals(2, inspections.get(1).get("overhaul_version"));
    }

    @Test
    @DisplayName("监督检验失败分支：同一检验人422、清单不全合格不确认、未登记大修422")
    void overhaulInspectionFailures() {
        makeOpenWithCertificate(rideId, "k");
        // 未登记大修 → 422
        ApiException noOverhaul = assertThrows(ApiException.class,
                () -> service.confirmOverhaulInspection(rideId,
                        passInspection("k-insp0", versionOf(rideId))));
        assertEquals(422, noOverhaul.status().value());

        service.registerOverhaul(rideId, new OverhaulRequest("k-oh1", versionOf(rideId)));
        // 同一检验人 → 422
        ApiException sameInspector = assertThrows(ApiException.class,
                () -> service.confirmOverhaulInspection(rideId,
                        new OverhaulInspectionRequest("k-insp1", versionOf(rideId),
                                "检验员甲", "检验员甲",
                                List.of(new CheckItem("主体结构", true)))));
        assertEquals(422, sameInspector.status().value());

        // 清单不全合格 → 200但不确认，资格未恢复
        ApiResult failed = service.confirmOverhaulInspection(rideId,
                new OverhaulInspectionRequest("k-insp2", versionOf(rideId),
                        "检验员甲", "检验员乙",
                        List.of(new CheckItem("主体结构", true), new CheckItem("制动系统", false))));
        assertEquals(200, failed.status());
        assertEquals(false, bodyOf(failed).get("allPassed"));
        assertEquals(false, service.eligibilityEvidence(rideId).get("eligible"));
    }

    @Test
    @DisplayName("expectedVersion 校验：版本不符409，成功变更加1")
    void expectedVersionGuard() {
        makeReady(rideId, "k");
        long current = versionOf(rideId);
        ApiException stale = assertThrows(ApiException.class,
                () -> service.registerCertificate(rideId,
                        new RegisterCertificateRequest("k-cert", current + 1, VALID_PASSED_AT, null)));
        assertEquals(409, stale.status().value());
        assertEquals(current, versionOf(rideId), "版本冲突不产生变更");
        assertTrue(service.listCertificates(rideId).isEmpty());

        ApiResult ok = service.registerCertificate(rideId,
                new RegisterCertificateRequest("k-cert", current, VALID_PASSED_AT, null));
        assertEquals(200, ok.status());
        assertEquals(current + 1, versionOf(rideId));
    }

    @Test
    @DisplayName("证书登记幂等：同键同参重放首次结果，同键异参409，失败不占键")
    void certificateIdempotency() {
        makeReady(rideId, "k");
        RegisterCertificateRequest request = new RegisterCertificateRequest(
                "k-cert", versionOf(rideId), VALID_PASSED_AT, null);
        ApiResult first = service.registerCertificate(rideId, request);
        ApiResult replay = service.registerCertificate(rideId, request);
        assertEquals(200, first.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, service.listCertificates(rideId).size(), "同键重放不重复登记");

        RegisterCertificateRequest different = new RegisterCertificateRequest(
                "k-cert", versionOf(rideId), EXPIRED_PASSED_AT, null);
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.registerCertificate(rideId, different));
        assertEquals(409, conflict.status().value());
        assertEquals(1, service.listCertificates(rideId).size());

        // 失败不占键：版本冲突后同键修正参数可成功
        RegisterCertificateRequest stale = new RegisterCertificateRequest(
                "k-cert2", 999L, VALID_PASSED_AT, null);
        assertThrows(ApiException.class, () -> service.registerCertificate(rideId, stale));
        ApiResult retried = service.registerCertificate(rideId, new RegisterCertificateRequest(
                "k-cert2", versionOf(rideId), VALID_PASSED_AT, null));
        assertEquals(200, retried.status());
    }

    @Test
    @DisplayName("未启用受检标志的旧设备：拒绝检验操作422，开放不受证书门禁影响")
    void nonInspectedRideKeepsOldBehavior() {
        long oldRide = ((Number) service.createRide(
                new CreateRideRequest("旧设备", 10, 80)).get("rideId")).longValue();
        ApiException e = assertThrows(ApiException.class,
                () -> service.registerCertificate(oldRide, new RegisterCertificateRequest(
                        "o-cert", versionOf(oldRide), VALID_PASSED_AT, null)));
        assertEquals(422, e.status().value());
        ApiException oh = assertThrows(ApiException.class,
                () -> service.registerOverhaul(oldRide,
                        new OverhaulRequest("o-oh", versionOf(oldRide))));
        assertEquals(422, oh.status().value());

        // 旧设备无证书也可开放（行为不变）
        makeReady(oldRide, "o");
        ApiResult opened = service.open(oldRide, new OpenRequest("o-open"));
        assertEquals(200, opened.status());
        assertEquals(true, service.eligibilityEvidence(oldRide).get("eligible"));
    }

    @Test
    @DisplayName("指纹结构化无歧义：合法文本含分隔符的异参仍区分，同键异参409")
    void fingerprintDistinguishesDelimiterInText() {
        makeReady(rideId, "k");
        long certId = registerValidCertificate(rideId, "k-cert");
        RevokeCertificateRequest first = new RevokeCertificateRequest(
                "k-revoke", versionOf(rideId), "甲|乙");
        ApiResult firstResult = service.revokeCertificate(rideId, certId, first);
        assertEquals(200, firstResult.status());
        // 同键但 reason 不同（去掉分隔符片段）→ 409，指纹不被分隔符混淆
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.revokeCertificate(rideId, certId,
                        new RevokeCertificateRequest("k-revoke", first.expectedVersion(), "甲")));
        assertEquals(409, conflict.status().value());
        // 同键同参（含分隔符，完整参数含版本一致）→ 重放首次冻结结果
        ApiResult replay = service.revokeCertificate(rideId, certId, first);
        assertEquals(firstResult.body(), replay.body());
    }

    @Test
    @DisplayName("归档设备拒绝检验变更422；设备不存在404")
    void archivedAndMissingRideRejected() {
        makeOpenWithCertificate(rideId, "k");
        service.archive(rideId, new com.example.starter.ride.dto.RideDtos
                .ArchiveRequest("k-arch"));
        ApiException e = assertThrows(ApiException.class,
                () -> service.registerOverhaul(rideId,
                        new OverhaulRequest("k-oh", versionOf(rideId))));
        assertEquals(422, e.status().value());

        ApiException notFound = assertThrows(ApiException.class,
                () -> service.registerCertificate(999999,
                        new RegisterCertificateRequest("x-cert", 1L, VALID_PASSED_AT, null)));
        assertEquals(404, notFound.status().value());
        ApiException evidenceNotFound = assertThrows(ApiException.class,
                () -> service.eligibilityEvidence(999999));
        assertEquals(404, evidenceNotFound.status().value());
    }
}
