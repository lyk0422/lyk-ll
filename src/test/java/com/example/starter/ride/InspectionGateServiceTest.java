package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.RegisterOverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RenewCertificateRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.SupervisoryInspectionRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 年度检验与大修门禁业务测试（真实 H2，MySQL 兼容模式；合成规则）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionGateServiceTest {

    private static final Instant NOW = TestClockConfig.FIXED_NOW;
    private static final Instant EXPIRES = NOW.plusSeconds(365L * 24L * 3600L);
    private static final List<CheckItem> FULL_CHECK = List.of(
            new CheckItem("压杠", true),
            new CheckItem("安全带", true),
            new CheckItem("联锁装置", true),
            new CheckItem("制动系统", true));

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM supervisory_check_item");
        jdbc.update("DELETE FROM supervisory_inspection");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
    }

    private long createRide(boolean inspected) {
        return ((Number) service.createRide(
                new CreateRideRequest("大摆锤", 10, 80, inspected, 1)).get("rideId")).longValue();
    }

    private void makeReady(long id, String prefix) {
        assertEquals(200, service.dailyCheck(id,
                new DailyCheckRequest(prefix + "-check", FULL_CHECK)).status());
        for (int i = 1; i <= 3; i++) {
            assertEquals(200, service.trialRun(id,
                    new TrialRunRequest(prefix + "-t" + i, true)).status());
        }
    }

    private long version(long id) {
        return ((Number) service.getRide(id).get("version")).longValue();
    }

    private ApiResult issueValidCert(long id, String requestId) {
        return service.issueCertificate(id,
                new IssueCertificateRequest(requestId, version(id), NOW, null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ApiResult result) {
        try {
            return objectMapper.readValue(result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("版本从1开始，成功变更加1；expectedVersion不匹配返回409")
    void versionStartsAtOneAndIncrements() {
        long id = createRide(true);
        assertEquals(1L, version(id));
        // 错误版本 → 409，不产生变更
        ApiException wrong = assertThrows(ApiException.class, () -> service.issueCertificate(id,
                new IssueCertificateRequest("v-bad", 99L, NOW, null)));
        assertEquals(409, wrong.status().value());
        assertEquals(1L, version(id));
        // 正确版本成功，版本加1
        ApiResult issued = issueValidCert(id, "v-ok");
        assertEquals(200, issued.status());
        assertEquals(2L, version(id));
        // 旧版本再次发证 → 409
        assertThrows(ApiException.class, () -> service.issueCertificate(id,
                new IssueCertificateRequest("v-old", 1L, NOW, null)));
    }

    @Test
    @DisplayName("受检设备无年度证书不得开放；旧设备（未启用标志）行为不变")
    void inspectedRideRequiresCertificateOldRideUnchanged() {
        long inspected = createRide(true);
        makeReady(inspected, "g1");
        ApiException blocked = assertThrows(ApiException.class,
                () -> service.open(inspected, new OpenRequest("g1-open")));
        assertEquals(422, blocked.status().value());
        assertTrue(blocked.getMessage().contains("年度证书"));
        assertEquals("READY", service.getRide(inspected).get("status"));

        long legacy = createRide(false);
        makeReady(legacy, "g2");
        assertEquals(200, service.open(legacy, new OpenRequest("g2-open")).status());
        assertEquals("OPEN", service.getRide(legacy).get("status"));
    }

    @Test
    @DisplayName("持当前有效年度证书方可开放，默认截止=合格时刻+365×24小时")
    void validCertificateOpensGate() {
        long id = createRide(true);
        makeReady(id, "c");
        ApiResult issued = issueValidCert(id, "c-cert");
        Map<String, Object> cert = bodyOf(issued);
        assertEquals("AC-" + id + "-1", cert.get("certificateNo"));
        assertEquals(1, cert.get("cycleVersion"));
        assertEquals(0, cert.get("overhaulVersion"));
        assertEquals(NOW.toString(), cert.get("issuedAt"));
        assertEquals(EXPIRES.toString(), cert.get("expiresAt"));
        assertTrue((Boolean) cert.get("validNow"));
        assertEquals(200, service.open(id, new OpenRequest("c-open")).status());
        assertEquals("OPEN", service.getRide(id).get("status"));
    }

    @Test
    @DisplayName("UTC下now<截止有效、恰截止失效")
    void expiryBoundaryExclusive() {
        // 恰截止：expiresAt == now → 失效
        long atBoundary = createRide(true);
        makeReady(atBoundary, "b1");
        Instant issuedAtBoundary = NOW.minusSeconds(365L * 24L * 3600L);
        assertEquals(200, service.issueCertificate(atBoundary,
                new IssueCertificateRequest("b1-cert", version(atBoundary),
                        issuedAtBoundary, NOW)).status());
        Map<String, Object> eligibility = service.getEligibility(atBoundary);
        @SuppressWarnings("unchecked")
        Map<String, Object> certGate = (Map<String, Object>)
                ((Map<String, Object>) eligibility.get("gates")).get("annualCertificate");
        assertFalse((Boolean) certGate.get("valid"));
        ApiException blocked = assertThrows(ApiException.class,
                () -> service.open(atBoundary, new OpenRequest("b1-open")));
        assertEquals(422, blocked.status().value());

        // 截止比 now 晚1秒 → 有效
        long oneSecondBefore = createRide(true);
        makeReady(oneSecondBefore, "b2");
        Instant issuedAtEarly = NOW.plusSeconds(1).minusSeconds(365L * 24L * 3600L);
        assertEquals(200, service.issueCertificate(oneSecondBefore,
                new IssueCertificateRequest("b2-cert", version(oneSecondBefore),
                        issuedAtEarly, NOW.plusSeconds(1))).status());
        assertEquals(200, service.open(oneSecondBefore, new OpenRequest("b2-open")).status());
    }

    @Test
    @DisplayName("显式截止必须恰为合格时刻+365×24小时，否则400")
    void explicitExpiryMustEqualDefault() {
        long id = createRide(true);
        ApiException e = assertThrows(ApiException.class, () -> service.issueCertificate(id,
                new IssueCertificateRequest("x-cert", version(id), NOW, NOW.plusSeconds(3600))));
        assertEquals(400, e.status().value());
        // 失败不占键：同键换合法参数可成功
        assertEquals(200, issueValidCert(id, "x-cert").status());
    }

    @Test
    @DisplayName("换证原子切换唯一当前指针，旧证SUPERSEDED可查且事实不可覆盖")
    void renewalSwitchesPointerAtomically() {
        long id = createRide(true);
        makeReady(id, "r");
        ApiResult first = issueValidCert(id, "r-cert1");
        long firstId = ((Number) bodyOf(first).get("certificateId")).longValue();
        ApiResult renewed = service.renewCertificate(id, new RenewCertificateRequest(
                "r-cert2", version(id), NOW.plusSeconds(10), null));
        assertEquals(200, renewed.status());
        Map<String, Object> second = bodyOf(renewed);
        // 换证发生在大修版本0上，新证快照大修版本为0
        assertEquals(0, second.get("overhaulVersion"));

        Map<String, Object> all = service.listCertificates(id);
        assertEquals(((Number) second.get("certificateId")).longValue(),
                ((Number) all.get("currentCertificateId")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> certs = (List<Map<String, Object>>) all.get("certificates");
        assertEquals(2, certs.size());
        assertEquals("SUPERSEDED", certs.get(0).get("status"));
        assertEquals(firstId, ((Number) certs.get(0).get("certificateId")).longValue());
        assertEquals("ACTIVE", certs.get(1).get("status"));
        // 旧证事实字段未被覆盖
        assertEquals(NOW.toString(), certs.get(0).get("issuedAt"));
        long activeCount = certs.stream().filter(c -> "ACTIVE".equals(c.get("status"))).count();
        assertEquals(1, activeCount, "当前指针唯一：" + certs);

        // 无当前证书时换证 → 422
        long bare = createRide(true);
        ApiException noCert = assertThrows(ApiException.class, () -> service.renewCertificate(
                bare, new RenewCertificateRequest("r-none", version(bare), NOW, null)));
        assertEquals(422, noCert.status().value());
    }

    @Test
    @DisplayName("撤销证书清空当前指针，撤销后不得开放；证书证据仍可查")
    void revokeClosesGate() {
        long id = createRide(true);
        makeReady(id, "rv");
        issueValidCert(id, "rv-cert");
        ApiResult revoked = service.revokeCertificate(id,
                new RevokeCertificateRequest("rv-revoke", version(id), NOW));
        assertEquals(200, revoked.status());
        assertEquals("REVOKED", bodyOf(revoked).get("status"));
        assertNull(service.getRide(id).get("currentCertificateId"));

        ApiException open = assertThrows(ApiException.class,
                () -> service.open(id, new OpenRequest("rv-open")));
        assertEquals(422, open.status().value());

        // 重复撤销 → 422
        ApiException again = assertThrows(ApiException.class, () -> service.revokeCertificate(id,
                new RevokeCertificateRequest("rv-revoke-2", version(id), NOW)));
        assertEquals(422, again.status().value());

        Map<String, Object> evidence = service.listCertificates(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> certs =
                (List<Map<String, Object>>) evidence.get("certificates");
        assertEquals(1, certs.size());
        assertEquals("REVOKED", certs.get(0).get("status"));
        assertEquals(NOW.toString(), certs.get(0).get("revokedAt"));
    }

    @Test
    @DisplayName("登记大修原子递增版本并暂停开放；旧证可查但不足以复开；监督检验合格只恢复资格不自动开放")
    void overhaulAndSupervisoryInspectionFlow() {
        long id = createRide(true);
        makeReady(id, "o");
        issueValidCert(id, "o-cert");
        assertEquals(200, service.open(id, new OpenRequest("o-open")).status());

        long versionBeforeOverhaul = version(id);
        ApiResult overhaul = service.registerOverhaul(id,
                new RegisterOverhaulRequest("o-oh1", versionBeforeOverhaul));
        assertEquals(200, overhaul.status());
        Map<String, Object> duringOverhaul = bodyOf(overhaul);
        assertEquals("OVERHAUL", duringOverhaul.get("status"));
        assertEquals(1, duringOverhaul.get("overhaulVersion"));
        assertEquals(versionBeforeOverhaul + 1, version(id));
        // 暂停开放：开放/复开均被状态机拒绝
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.open(id, new OpenRequest("o-open-2"))).status().value());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.reopen(id, new ReopenRequest("o-re-x"))).status().value());

        // 两名检验人不得相同 → 400
        ApiException sameInspector = assertThrows(ApiException.class,
                () -> service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                        "o-si-bad", version(id), "张三", "张三", FULL_CHECK)));
        assertEquals(400, sameInspector.status().value());

        // 清单未全合格 → 不恢复资格
        ApiResult failed = service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                "o-si-fail", version(id), "张三", "李四",
                List.of(new CheckItem("压杠", true), new CheckItem("制动系统", false))));
        assertEquals(200, failed.status());
        assertFalse((Boolean) bodyOf(failed).get("allPassed"));
        assertEquals("OVERHAUL", service.getRide(id).get("status"));

        // 全清单合格、两名不同检验人 → RECHECKED，但不自动开放、不动复开次数
        ApiResult passed = service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                "o-si-ok", version(id), "张三", "李四", FULL_CHECK));
        assertEquals(200, passed.status());
        Map<String, Object> passedBody = bodyOf(passed);
        assertTrue((Boolean) passedBody.get("allPassed"));
        assertEquals("RECHECKED", passedBody.get("status"));
        assertEquals(1, passedBody.get("overhaulVersion"));
        assertEquals(0, passedBody.get("reopenCount"));

        // 旧年度证书可查但快照大修版本落后 → 复开422
        Map<String, Object> certs = service.listCertificates(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history =
                (List<Map<String, Object>>) certs.get("certificates");
        assertEquals(1, history.size());
        assertEquals("ACTIVE", history.get(0).get("status"));
        ApiException staleCert = assertThrows(ApiException.class,
                () -> service.reopen(id, new ReopenRequest("o-re-stale")));
        assertEquals(422, staleCert.status().value());
        assertTrue(staleCert.getMessage().contains("大修"));

        // 换证绑定当前大修版本后方可复开
        ApiResult renewed = service.renewCertificate(id, new RenewCertificateRequest(
                "o-cert2", version(id), NOW.plusSeconds(20), null));
        assertEquals(200, renewed.status());
        assertEquals(1, bodyOf(renewed).get("overhaulVersion"));
        ApiResult reopened = service.reopen(id, new ReopenRequest("o-re1"));
        assertEquals(200, reopened.status());
        assertEquals("OPEN", bodyOf(reopened).get("status"));
        assertEquals(1, bodyOf(reopened).get("reopenCount"));
    }

    @Test
    @DisplayName("再次大修使旧监督检验失效，须绑定新大修版本重检；监督检验不重置当日复开次数")
    void secondOverhaulInvalidatesOldInspectionAndKeepsReopenCount() {
        long id = createRide(true);
        makeReady(id, "s");
        issueValidCert(id, "s-cert");
        service.open(id, new OpenRequest("s-open"));
        // 先用掉两次当日复开额度（故障→复检→复开 ×2）
        for (int i = 1; i <= 2; i++) {
            service.safetyFault(id, new SafetyFaultRequest("s-f" + i, "压杠"));
            service.recheck(id, new RecheckRequest("s-rc" + i,
                    List.of(new CheckItem("压杠", true))));
            ApiResult re = service.reopen(id, new ReopenRequest("s-re" + i));
            assertEquals(200, re.status());
            assertEquals(i, bodyOf(re).get("reopenCount"));
        }
        // 登记大修 → 监督检验合格 → 换证，但当日复开次数不被重置，依旧当日禁开
        service.registerOverhaul(id, new RegisterOverhaulRequest("s-oh1", version(id)));
        service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                "s-si1", version(id), "张三", "李四", FULL_CHECK));
        service.renewCertificate(id, new RenewCertificateRequest(
                "s-cert2", version(id), NOW.plusSeconds(30), null));
        ApiException noQuota = assertThrows(ApiException.class,
                () -> service.reopen(id, new ReopenRequest("s-re-after-oh")));
        assertEquals(422, noQuota.status().value());
        assertEquals(2, service.getRide(id).get("reopenCount"));

        // 再次大修：版本2，版本1的监督检验证据 current=false
        service.registerOverhaul(id, new RegisterOverhaulRequest("s-oh2", version(id)));
        assertEquals(2, service.getRide(id).get("overhaulVersion"));
        List<Map<String, Object>> evidence = service.listSupervisoryInspections(id);
        assertEquals(1, evidence.size());
        assertEquals(false, evidence.get(0).get("current"), "旧大修版本监督检验应标记失效");
        assertEquals(1, ((Number) evidence.get(0).get("overhaul_version")).intValue());
        assertNotNull(evidence.get(0).get("items"));

        // 不做版本2监督检验前状态停留在 OVERHAUL
        assertEquals("OVERHAUL", service.getRide(id).get("status"));
        service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                "s-si2", version(id), "王五", "赵六", FULL_CHECK));
        assertEquals("RECHECKED", service.getRide(id).get("status"));
        List<Map<String, Object>> evidence2 = service.listSupervisoryInspections(id);
        assertEquals(true, evidence2.get(1).get("current"));
    }

    @Test
    @DisplayName("证书失效后载客读数只记录并告警，超阈值也不缓停；正常读数不恢复资格")
    void expiredQualificationReadingsOnlyWarn() {
        long id = createRide(true);
        makeReady(id, "w");
        issueValidCert(id, "w-cert");
        service.open(id, new OpenRequest("w-open"));
        // 撤销证书 → 资格失效，但设备状态仍为开放中
        service.revokeCertificate(id, new RevokeCertificateRequest("w-revoke", version(id), NOW));

        Map<String, Object> exceeded = service.reading(id, new ReadingRequest(99, 99));
        assertTrue((Boolean) exceeded.get("warning"));
        assertFalse((Boolean) exceeded.get("certificateValid"));
        assertEquals(true, exceeded.get("exceeded"));
        assertEquals("OPEN", exceeded.get("status"), "读数不触发缓停状态流转");

        Map<String, Object> normal = service.reading(id, new ReadingRequest(1, 1));
        assertTrue((Boolean) normal.get("warning"));
        assertEquals("OPEN", service.getRide(id).get("status"));
        // 资格证据仍判定不可复开放行，读数从未恢复资格
        assertFalse((Boolean) service.getEligibility(id).get("eligibleToReopen"));

        List<Map<String, Object>> readings = service.listReadings(id);
        assertEquals(2, readings.size());
        assertTrue(readings.stream().allMatch(r -> Boolean.TRUE.equals(r.get("warning"))));
        long warnings = service.listEvents(id).stream()
                .filter(ev -> "CERT_WARNING".equals(ev.get("event_type"))).count();
        assertEquals(2, warnings);
        long slowStops = service.listEvents(id).stream()
                .filter(ev -> "SLOW_STOP".equals(ev.get("event_type"))).count();
        assertEquals(0, slowStops);

        // 重新取得有效证书后读数恢复正常路径
        issueValidCert(id, "w-cert-again");
        Map<String, Object> recovered = service.reading(id, new ReadingRequest(10, 80));
        assertFalse(recovered.containsKey("warning"), "恢复有效证书后不再告警");
        assertFalse((Boolean) recovered.get("exceeded"));
        assertEquals("OPEN", recovered.get("status"));
    }

    @Test
    @DisplayName("新增写操作幂等：同键同参冻结首次结果，异参409，失败不占键")
    void newOperationsAreIdempotent() {
        long id = createRide(true);
        // 失败不占键：同键先因截止时刻非法400，改缺省截止后同键成功
        ApiException badExpiry = assertThrows(ApiException.class,
                () -> service.issueCertificate(id, new IssueCertificateRequest(
                        "i-cert-fail", version(id), NOW, NOW.plusSeconds(60))));
        assertEquals(400, badExpiry.status().value());
        assertEquals(200, service.issueCertificate(id, new IssueCertificateRequest(
                "i-cert-fail", version(id), NOW, null)).status());
        // 已有当前证时再次发证 → 422
        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.issueCertificate(id,
                        new IssueCertificateRequest("i-cert-dup", version(id), NOW, null)));
        assertEquals(422, duplicate.status().value());

        // 大修同键同参重放：冻结首次结果，大修版本不重复递增
        makeReady(id, "i");
        long v = version(id);
        RegisterOverhaulRequest overhaul = new RegisterOverhaulRequest("i-oh", v);
        ApiResult first = service.registerOverhaul(id, overhaul);
        ApiResult replay = service.registerOverhaul(id, overhaul);
        assertEquals(first.body(), replay.body());
        assertEquals(1, service.getRide(id).get("overhaulVersion"));

        // 同键异参 → 409
        ApiException different = assertThrows(ApiException.class, () -> service
                .registerOverhaul(id, new RegisterOverhaulRequest("i-oh", v + 1)));
        assertEquals(409, different.status().value());

        // 监督检验同键重放不重复落记录
        service.renewCertificate(id, new RenewCertificateRequest(
                "i-cert2", version(id), NOW, null));
        SupervisoryInspectionRequest si = new SupervisoryInspectionRequest(
                "i-si", version(id), "张三", "李四", FULL_CHECK);
        ApiResult siFirst = service.supervisoryInspection(id, si);
        ApiResult siReplay = service.supervisoryInspection(id, si);
        assertEquals(siFirst.body(), siReplay.body());
        assertEquals(1, service.listSupervisoryInspections(id).size());

        // 失败不占键：同键先失败（检验人相同400），改合法参数后成功
        ApiException badInspectors = assertThrows(ApiException.class,
                () -> service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                        "i-si2", version(id), "王五", "王五", FULL_CHECK)));
        assertEquals(400, badInspectors.status().value());
        // 已 RECHECKED，不能再监督检验；先再登记一次大修制造合法状态
        service.registerOverhaul(id, new RegisterOverhaulRequest("i-oh2", version(id)));
        ApiResult reused = service.supervisoryInspection(id, new SupervisoryInspectionRequest(
                "i-si2", version(id), "王五", "赵六", FULL_CHECK));
        assertEquals(200, reused.status());
    }

    @Test
    @DisplayName("对象不存在404；大修中重复登记422；归档设备拒绝发证与大修")
    void notFoundAndStateErrors() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.issueCertificate(999999,
                        new IssueCertificateRequest("nope", 1L, NOW, null))).status().value());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.getEligibility(999999)).status().value());

        long id = createRide(true);
        issueValidCert(id, "n-cert");
        service.registerOverhaul(id, new RegisterOverhaulRequest("n-oh", version(id)));
        ApiException twice = assertThrows(ApiException.class, () -> service.registerOverhaul(
                id, new RegisterOverhaulRequest("n-oh2", version(id))));
        assertEquals(422, twice.status().value());

        long archived = createRide(true);
        makeReady(archived, "n2");
        service.archive(archived,
                new com.example.starter.ride.dto.RideDtos.ArchiveRequest("n2-arch"));
        assertEquals(409, assertThrows(ApiException.class, () -> issueValidCert(archived, "n2-c"))
                .status().value());
        assertEquals(409, assertThrows(ApiException.class, () -> service.registerOverhaul(
                archived, new RegisterOverhaulRequest("n2-oh", version(archived))))
                .status().value());
    }

    @Test
    @DisplayName("资格证据查询：门禁逐项可复核")
    void eligibilityEvidence() {
        long id = createRide(true);
        makeReady(id, "e");
        issueValidCert(id, "e-cert");
        Map<String, Object> eligibility = service.getEligibility(id);
        assertEquals(true, eligibility.get("eligibleToOpen"));
        @SuppressWarnings("unchecked")
        Map<String, Object> gates = (Map<String, Object>) eligibility.get("gates");
        assertEquals(true, gates.get("dailyCheckToday"));
        assertEquals(true, gates.get("trialRunReady"));
        assertEquals(0, gates.get("overhaulVersion"));
        assertEquals(true, gates.get("supervisoryInspectionReady"));
        @SuppressWarnings("unchecked")
        Map<String, Object> certGate = (Map<String, Object>) gates.get("annualCertificate");
        assertEquals(true, certGate.get("valid"));
        assertNotNull(certGate.get("expiresAt"));
    }
}
