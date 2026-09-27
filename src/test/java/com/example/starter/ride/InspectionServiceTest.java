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
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.SupervisionConfirmRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 年度检验门禁业务测试（真实 H2，MySQL 兼容模式，可控固定时钟）。
 * 固定时钟为 2026-09-26T01:00:00Z，证书有效期边界以此为基准。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionServiceTest {

    private static final Instant NOW = TestClockConfig.FIXED_NOW;

    @Autowired
    private RideService service;

    @Autowired
    private Idempotency.Executor idempotency;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private long rideId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM supervision_inspection_item");
        jdbc.update("DELETE FROM supervision_inspection");
        jdbc.update("DELETE FROM overhaul_record");
        jdbc.update("DELETE FROM current_certificate");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        rideId = createInspectionRide();
    }

    private long createInspectionRide() {
        return ((Number) service.createRide(
                new CreateRideRequest("受检过山车", 10, 80, true)).get("rideId")).longValue();
    }

    private long createPlainRide() {
        return ((Number) service.createRide(
                new CreateRideRequest("旧旋转木马", 12, 40)).get("rideId")).longValue();
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

    private void makeReady(long id, String keyPrefix) {
        ApiResult check = service.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check",
                List.of(new CheckItem("压杠", true), new CheckItem("安全带", true),
                        new CheckItem("联锁装置", true))));
        assertEquals(200, check.status());
        for (int i = 1; i <= 3; i++) {
            ApiResult trial = service.trialRun(id,
                    new TrialRunRequest(keyPrefix + "-t" + i, true));
            assertEquals(200, trial.status());
        }
    }

    /** 签发默认有效期证书（合格时刻=当前固定时刻，截止=+365×24小时）。 */
    private Map<String, Object> issueDefaultCert(long id, String requestId) {
        ApiResult result = service.issueCertificate(id, new IssueCertificateRequest(
                requestId, null, null));
        assertEquals(200, result.status());
        return bodyOf(result);
    }

    private void makeOpen(long id, String keyPrefix) {
        makeReady(id, keyPrefix);
        ApiResult result = service.open(id, new OpenRequest(keyPrefix + "-open"));
        assertEquals(200, result.status());
    }

    private SupervisionConfirmRequest supervision(String requestId, long overhaulVersion) {
        return new SupervisionConfirmRequest(requestId, overhaulVersion, "检验员甲", "检验员乙",
                List.of(new CheckItem("结构焊缝", true), new CheckItem("制动系统", true)));
    }

    @Test
    @DisplayName("受检设备无当前年度证书禁止开放，签发默认证书后可开放")
    void inspectionRideRequiresValidCertificateToOpen() {
        makeReady(rideId, "k");
        // 无证书 → 422 业务拒绝
        ApiException noCert = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(422, noCert.status().value());
        assertEquals("READY", statusOf(rideId));

        // 签发默认证书：截止=合格时刻+365×24小时
        Map<String, Object> cert = issueDefaultCert(rideId, "k-cert");
        assertEquals(NOW.toString(), cert.get("passedAt"));
        assertEquals(NOW.plus(Ride.CERT_DEFAULT_VALIDITY).toString(), cert.get("validUntil"));
        assertEquals(1, ((Number) cert.get("version")).longValue());
        assertFalse((Boolean) cert.get("revoked"));

        ApiResult opened = service.open(rideId, new OpenRequest("k-open-2"));
        assertEquals("OPEN", bodyOf(opened).get("status"));
    }

    @Test
    @DisplayName("恰截止失效：now<截止有效，now==截止失效")
    void certificateExpiresExactlyAtDeadline() {
        makeReady(rideId, "k");
        // 恰截止：validUntil == now → 失效
        ApiResult exact = service.issueCertificate(rideId, new IssueCertificateRequest(
                "k-cert-exact", NOW.minusSeconds(3600), NOW));
        assertEquals(200, exact.status());
        ApiException atDeadline = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open-exact")));
        assertEquals(422, atDeadline.status().value());

        // 换证：now < 截止 → 有效
        ApiResult valid = service.issueCertificate(rideId, new IssueCertificateRequest(
                "k-cert-valid", NOW.minusSeconds(3600), NOW.plusSeconds(1)));
        assertEquals(200, valid.status());
        ApiResult opened = service.open(rideId, new OpenRequest("k-open"));
        assertEquals("OPEN", bodyOf(opened).get("status"));
    }

    @Test
    @DisplayName("换证原子切换唯一当前指针，历史证书可查不可覆盖")
    void renewalSwitchesCurrentPointerAtomically() {
        Map<String, Object> first = issueDefaultCert(rideId, "k-cert-1");
        long firstId = ((Number) first.get("certificateId")).longValue();
        Map<String, Object> second = issueDefaultCert(rideId, "k-cert-2");
        long secondId = ((Number) second.get("certificateId")).longValue();
        assertNotEquals(firstId, secondId);

        // 当前指针唯一指向新证书
        Map<String, Object> current = service.getCurrentCertificate(rideId);
        assertEquals(secondId, ((Number) current.get("certificateId")).longValue());
        Integer pointerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM current_certificate WHERE ride_id = ?",
                Integer.class, rideId);
        assertEquals(1, pointerCount);

        // 历史证书可查且数据未被覆盖
        List<Map<String, Object>> all = service.listCertificates(rideId);
        assertEquals(2, all.size());
        assertEquals(firstId, ((Number) all.get(0).get("certificateId")).longValue());
        assertEquals(first.get("validUntil"), all.get(0).get("validUntil"));
    }

    @Test
    @DisplayName("撤销证书：expectedVersion匹配成功版本加1，异版本409，撤销后禁开放且留撤销证据")
    void revokeCertificateWithExpectedVersion() {
        makeReady(rideId, "k");
        issueDefaultCert(rideId, "k-cert");

        // 版本不匹配 → 409
        ApiException stale = assertThrows(ApiException.class,
                () -> service.revokeCertificate(rideId,
                        new RevokeCertificateRequest("k-rev-stale", 99L, "版本不符")));
        assertEquals(409, stale.status().value());
        assertEquals(1L, ((Number) service.getCurrentCertificate(rideId)
                .get("version")).longValue());

        // 版本匹配 → 撤销成功，版本加1
        ApiResult revoked = service.revokeCertificate(rideId,
                new RevokeCertificateRequest("k-rev", 1L, "抽检不合格"));
        Map<String, Object> revokedBody = bodyOf(revoked);
        assertTrue((Boolean) revokedBody.get("revoked"));
        assertEquals(2, ((Number) revokedBody.get("version")).longValue());
        assertEquals("抽检不合格", revokedBody.get("revokeReason"));

        // 撤销后无当前证书 → 404；开放被拒 → 422
        ApiException noCurrent = assertThrows(ApiException.class,
                () -> service.getCurrentCertificate(rideId));
        assertEquals(404, noCurrent.status().value());
        ApiException openDenied = assertThrows(ApiException.class,
                () -> service.open(rideId, new OpenRequest("k-open")));
        assertEquals(422, openDenied.status().value());

        // 撤销证据可查
        List<Map<String, Object>> revocations = service.listRevocations(rideId);
        assertEquals(1, revocations.size());
        assertEquals("抽检不合格", revocations.get(0).get("revokeReason"));
        // 重复撤销（新键）：已无当前证书 → 404
        ApiException again = assertThrows(ApiException.class,
                () -> service.revokeCertificate(rideId,
                        new RevokeCertificateRequest("k-rev-2", 2L, "重复")));
        assertEquals(404, again.status().value());
    }

    @Test
    @DisplayName("大修：原子递增版本、开放中暂停开放、旧监督失效，旧证书可查但不足以复开")
    void overhaulSuspendsAndInvalidatesOldSupervision() {
        issueDefaultCert(rideId, "k-cert");
        makeOpen(rideId, "k");
        // 先复开一次，验证大修后复开次数不重置
        service.safetyFault(rideId, new SafetyFaultRequest("k-f1", "压杠"));
        service.recheck(rideId, new RecheckRequest("k-r1",
                List.of(new CheckItem("压杠", true))));
        ApiResult reopen1 = service.reopen(rideId, new ReopenRequest("k-re1"));
        assertEquals(1, bodyOf(reopen1).get("reopenCount"));

        // 登记大修：开放中 → 暂停开放，大修版本 0→1
        ApiResult overhaul = service.registerOverhaul(rideId, new OverhaulRequest("k-oh1"));
        assertEquals(200, overhaul.status());
        assertEquals(1, ((Number) bodyOf(overhaul).get("overhaulVersion")).longValue());
        assertEquals("SUSPENDED", statusOf(rideId));
        assertEquals(1, service.listOverhauls(rideId).size());

        // 旧年度证书可查但不足以复开：复检合格后复开仍被门禁拒绝
        assertFalse(service.listCertificates(rideId).isEmpty());
        service.recheck(rideId, new RecheckRequest("k-r2",
                List.of(new CheckItem("压杠", true))));
        ApiException reopenDenied = assertThrows(ApiException.class,
                () -> service.reopen(rideId, new ReopenRequest("k-re2")));
        assertEquals(422, reopenDenied.status().value());
        assertTrue(reopenDenied.getMessage().contains("监督检验"));

        // 绑定旧大修版本的监督检验 → 422
        ApiException staleBinding = assertThrows(ApiException.class,
                () -> service.confirmSupervision(rideId, supervision("k-sup-old", 0L)));
        assertEquals(422, staleBinding.status().value());

        // 绑定当前版本确认 → 恢复资格但不自动开放、不重置复开次数
        ApiResult confirmed = service.confirmSupervision(rideId, supervision("k-sup1", 1L));
        assertEquals(200, confirmed.status());
        assertEquals("RECHECKED", statusOf(rideId));
        ApiResult reopen2 = service.reopen(rideId, new ReopenRequest("k-re2-ok"));
        assertEquals("OPEN", bodyOf(reopen2).get("status"));
        assertEquals(2, bodyOf(reopen2).get("reopenCount"), "大修不重置当日复开次数");
    }

    @Test
    @DisplayName("监督检验：同名检验人422、清单未全合格422、失败不占幂等键")
    void supervisionRequiresTwoDistinctInspectorsAndFullPass() {
        issueDefaultCert(rideId, "k-cert");
        makeReady(rideId, "k");
        service.registerOverhaul(rideId, new OverhaulRequest("k-oh1"));

        // 同名检验人 → 422
        ApiException sameInspector = assertThrows(ApiException.class,
                () -> service.confirmSupervision(rideId, new SupervisionConfirmRequest(
                        "k-sup-same", 1L, "检验员甲", "检验员甲",
                        List.of(new CheckItem("结构焊缝", true)))));
        assertEquals(422, sameInspector.status().value());

        // 清单未全合格 → 422，且失败不占幂等键
        ApiException notAllPassed = assertThrows(ApiException.class,
                () -> service.confirmSupervision(rideId, new SupervisionConfirmRequest(
                        "k-sup-fail", 1L, "检验员甲", "检验员乙",
                        List.of(new CheckItem("结构焊缝", false)))));
        assertEquals(422, notAllPassed.status().value());
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'k-sup-fail'",
                Integer.class);
        assertEquals(0, idemCount, "失败不占幂等键");
        // 同键换合格参数可正常执行（键未被失败占用）
        ApiResult fixed = service.confirmSupervision(rideId, new SupervisionConfirmRequest(
                "k-sup-fail", 1L, "检验员甲", "检验员乙",
                List.of(new CheckItem("结构焊缝", true))));
        assertEquals(200, fixed.status());

        // 资格恢复但不自动开放
        assertEquals("READY", statusOf(rideId));
        assertTrue((Boolean) service.getEligibility(rideId).get("openEligible"));
        ApiResult opened = service.open(rideId, new OpenRequest("k-open"));
        assertEquals("OPEN", bodyOf(opened).get("status"));
    }

    @Test
    @DisplayName("过期设备载客读数只记录并告警，正常读数不恢复资格")
    void expiredCertificateReadingOnlyAlarms() {
        makeReady(rideId, "k");
        // 证书恰好在开放后失效：先有效开放，再换证为已过期证书
        issueDefaultCert(rideId, "k-cert");
        ApiResult opened = service.open(rideId, new OpenRequest("k-open"));
        assertEquals("OPEN", bodyOf(opened).get("status"));
        ApiResult expired = service.issueCertificate(rideId, new IssueCertificateRequest(
                "k-cert-expired", NOW.minusSeconds(7200), NOW.minusSeconds(1)));
        assertEquals(200, expired.status());

        // 正常读数：记录并告警，不恢复资格
        Map<String, Object> reading = service.reading(rideId, new ReadingRequest(5, 50));
        assertTrue((Boolean) reading.get("alarm"));
        assertFalse((Boolean) reading.get("certificateValid"));
        assertFalse((Boolean) reading.get("exceeded"));
        assertEquals(1, service.listReadings(rideId).size());
        assertTrue(service.listEvents(rideId).stream()
                .anyMatch(ev -> "CERT_EXPIRED_ALARM".equals(ev.get("event_type"))));
        // 资格证据仍判定不可开放
        Map<String, Object> eligibility = service.getEligibility(rideId);
        assertFalse((Boolean) eligibility.get("openEligible"));
        assertFalse((Boolean) eligibility.get("certificateValid"));
    }

    @Test
    @DisplayName("未启用受检标志的旧设备行为不变：无证书可开放，证书流程拒绝")
    void plainRideBehaviorUnchanged() {
        long plainId = createPlainRide();
        makeReady(plainId, "p");
        ApiResult opened = service.open(plainId, new OpenRequest("p-open"));
        assertEquals("OPEN", bodyOf(opened).get("status"));
        assertFalse((Boolean) service.getRide(plainId).get("inspectionRequired"));
        assertTrue((Boolean) service.getEligibility(plainId).get("openEligible"));

        // 旧设备不适用年度检验流程 → 422
        ApiException issue = assertThrows(ApiException.class,
                () -> service.issueCertificate(plainId,
                        new IssueCertificateRequest("p-cert", null, null)));
        assertEquals(422, issue.status().value());
        ApiException overhaul = assertThrows(ApiException.class,
                () -> service.registerOverhaul(plainId, new OverhaulRequest("p-oh")));
        assertEquals(422, overhaul.status().value());
        ApiException supervision = assertThrows(ApiException.class,
                () -> service.confirmSupervision(plainId, supervision("p-sup", 0L)));
        assertEquals(422, supervision.status().value());
    }

    @Test
    @DisplayName("证书签发幂等：同键同参重放首次冻结结果，异参409")
    void certificateIssueIdempotency() {
        IssueCertificateRequest request = new IssueCertificateRequest(
                "k-cert-idem", NOW, NOW.plusSeconds(3600));
        ApiResult first = service.issueCertificate(rideId, request);
        ApiResult replay = service.issueCertificate(rideId, request);
        assertEquals(200, first.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, service.listCertificates(rideId).size());

        // 同键异参（精确时刻不同）→ 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.issueCertificate(rideId, new IssueCertificateRequest(
                        "k-cert-idem", NOW, NOW.plusSeconds(3601))));
        assertEquals(409, conflict.status().value());
        assertEquals(1, service.listCertificates(rideId).size());
    }

    @Test
    @DisplayName("指纹结构化无歧义：合法文本含分隔符也须区分")
    void fingerprintIsStructuredAndUnambiguous() {
        SupervisionConfirmRequest a = new SupervisionConfirmRequest(
                "r1", 1L, "张|三", "李四", List.of(new CheckItem("焊缝", true)));
        SupervisionConfirmRequest b = new SupervisionConfirmRequest(
                "r1", 1L, "张", "三|李四", List.of(new CheckItem("焊缝", true)));
        assertNotEquals(idempotency.fingerprint("r1", a), idempotency.fingerprint("r1", b),
                "检验人文本含分隔符的不同划分必须产生不同指纹");
        // 同一 requestId 文本位置不同也须区分
        assertNotEquals(idempotency.fingerprint("r|1", a), idempotency.fingerprint("r", a));
        // 集合顺序不同须区分
        SupervisionConfirmRequest reordered = new SupervisionConfirmRequest(
                "r1", 1L, "张|三", "李四",
                List.of(new CheckItem("制动", true), new CheckItem("焊缝", true)));
        SupervisionConfirmRequest reordered2 = new SupervisionConfirmRequest(
                "r1", 1L, "张|三", "李四",
                List.of(new CheckItem("焊缝", true), new CheckItem("制动", true)));
        assertNotEquals(idempotency.fingerprint("r1", reordered),
                idempotency.fingerprint("r1", reordered2));
        // 完全相同参数指纹稳定
        assertEquals(idempotency.fingerprint("r1", a), idempotency.fingerprint("r1", a));
    }

    @Test
    @DisplayName("资格证据查询：证书、监督检验与判定原因完整")
    void eligibilityEvidence() {
        makeReady(rideId, "k");
        // 无证书：证据显示不可开放及原因
        Map<String, Object> noCert = service.getEligibility(rideId);
        assertFalse((Boolean) noCert.get("openEligible"));
        assertTrue(((List<?>) noCert.get("reasons")).contains("无当前年度证书"));

        issueDefaultCert(rideId, "k-cert");
        Map<String, Object> withCert = service.getEligibility(rideId);
        assertTrue((Boolean) withCert.get("openEligible"));
        assertTrue((Boolean) withCert.get("certificateValid"));
        assertFalse((Boolean) withCert.get("supervisionRequired"));

        // 大修后：证书有效但缺监督检验
        service.registerOverhaul(rideId, new OverhaulRequest("k-oh1"));
        Map<String, Object> afterOverhaul = service.getEligibility(rideId);
        assertFalse((Boolean) afterOverhaul.get("openEligible"));
        assertTrue((Boolean) afterOverhaul.get("certificateValid"));
        assertTrue((Boolean) afterOverhaul.get("supervisionRequired"));
        assertFalse((Boolean) afterOverhaul.get("supervisionConfirmed"));
        assertEquals(1, ((Number) afterOverhaul.get("overhaulVersion")).longValue());

        // 监督确认后资格恢复
        service.confirmSupervision(rideId, supervision("k-sup1", 1L));
        Map<String, Object> confirmed = service.getEligibility(rideId);
        assertTrue((Boolean) confirmed.get("openEligible"));
        assertTrue((Boolean) confirmed.get("supervisionConfirmed"));
        assertEquals(1, service.listSupervisions(rideId).size());
    }
}
