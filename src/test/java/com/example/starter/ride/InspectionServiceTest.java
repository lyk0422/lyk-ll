package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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
import com.example.starter.ride.dto.RideDtos.SupervisionInspectionRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 年度检验与大修后开放资格门禁测试（真实 H2，MySQL 兼容模式，可控时钟）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionServiceTest {

    @Autowired
    private RideService rideService;

    @Autowired
    private InspectionService inspectionService;

    @Autowired
    private Idempotency.Executor idempotency;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(TestClockConfig.FIXED_NOW);
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM supervision_inspection_item");
        jdbc.update("DELETE FROM supervision_inspection");
        jdbc.update("DELETE FROM overhaul_record");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
    }

    @AfterEach
    void resetClock() {
        clock.setInstant(TestClockConfig.FIXED_NOW);
    }

    private long createRide(boolean inspectionRequired) {
        return ((Number) rideService.createRide(new CreateRideRequest(
                "过山车", 10, 80, inspectionRequired)).get("rideId")).longValue();
    }

    private long versionOf(long rideId) {
        return ((Number) rideService.getRide(rideId).get("version")).longValue();
    }

    private String statusOf(long rideId) {
        return (String) rideService.getRide(rideId).get("status");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ApiResult result) {
        try {
            return objectMapper.readValue(result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void makeReady(long id, String keyPrefix) {
        assertEquals(200, rideService.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check",
                List.of(new CheckItem("压杠", true),
                        new CheckItem("安全带", true),
                        new CheckItem("联锁装置", true)))).status());
        assertEquals(200, rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t1", true)).status());
        assertEquals(200, rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t2", true)).status());
        assertEquals(200, rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t3", true)).status());
    }

    private ApiResult issueCert(long rideId, String requestId,
            Instant passedAt, Instant validUntil) {
        return inspectionService.issueCertificate(rideId, new IssueCertificateRequest(
                requestId, versionOf(rideId), passedAt, validUntil));
    }

    private void makeOpenWithCert(long id, String keyPrefix) {
        makeReady(id, keyPrefix);
        assertEquals(200, issueCert(id, keyPrefix + "-cert", null, null).status());
        assertEquals(200, rideService.open(id, new OpenRequest(keyPrefix + "-open")).status());
    }

    @Test
    @DisplayName("版本从1开始、成功变更加1，expectedVersion不匹配返回409")
    void versionStartsAtOneAndIncrementsOnChange() {
        long id = createRide(true);
        assertEquals(1, versionOf(id), "版本从1开始");

        ApiResult issued = issueCert(id, "v-cert", null, null);
        assertEquals(200, issued.status());
        assertEquals(2, bodyOf(issued).get("rideVersion"));
        assertEquals(2, versionOf(id), "成功变更加1");

        // 旧版本再次变更 → 409
        ApiException stale = assertThrows(ApiException.class, () -> inspectionService
                .issueCertificate(id, new IssueCertificateRequest("v-cert-2", 1L, null, null)));
        assertEquals(409, stale.status().value());
        assertEquals(2, versionOf(id));

        // 携带当前版本 → 成功
        ApiResult renewed = inspectionService.issueCertificate(id,
                new IssueCertificateRequest("v-cert-2", 2L, null, null));
        assertEquals(200, renewed.status());
        assertEquals(3, versionOf(id));
    }

    @Test
    @DisplayName("受检设备无有效年度证书禁开放，发证后可开放；旧设备行为不变")
    void inspectedRideRequiresValidCertificateToOpen() {
        long inspected = createRide(true);
        makeReady(inspected, "g1");
        ApiException denied = assertThrows(ApiException.class,
                () -> rideService.open(inspected, new OpenRequest("g1-open")));
        assertEquals(422, denied.status().value());
        assertTrue(denied.getMessage().contains("年度检验证书"));
        assertEquals("READY", statusOf(inspected));

        assertEquals(200, issueCert(inspected, "g1-cert", null, null).status());
        ApiResult opened = rideService.open(inspected, new OpenRequest("g1-open"));
        assertEquals(200, opened.status());
        assertEquals("OPEN", bodyOf(opened).get("status"));

        // 未启用受检标志的旧设备：无证书也可按原规则开放
        long legacy = createRide(false);
        makeReady(legacy, "g2");
        ApiResult legacyOpened = rideService.open(legacy, new OpenRequest("g2-open"));
        assertEquals(200, legacyOpened.status());
        assertEquals("OPEN", bodyOf(legacyOpened).get("status"));
        assertEquals(false, bodyOf(legacyOpened).get("inspectionRequired"));
    }

    @Test
    @DisplayName("有效期边界：now<截止有效、恰截止失效；默认截止为合格时刻+365×24小时")
    void certificateValidityBoundary() {
        long id = createRide(true);
        makeReady(id, "b1");

        // 默认截止 = 合格时刻 + 365×24小时
        ApiResult issued = issueCert(id, "b1-cert", TestClockConfig.FIXED_NOW, null);
        assertEquals(200, issued.status());
        assertEquals(TestClockConfig.FIXED_NOW.plus(Duration.ofHours(365L * 24)).toString(),
                bodyOf(issued).get("validUntil"));

        // 恰截止失效：validUntil == now → 禁开放
        long id2 = createRide(true);
        makeReady(id2, "b2");
        assertEquals(200, issueCert(id2, "b2-cert",
                TestClockConfig.FIXED_NOW.minus(Duration.ofDays(10)),
                TestClockConfig.FIXED_NOW).status());
        ApiException atDeadline = assertThrows(ApiException.class,
                () -> rideService.open(id2, new OpenRequest("b2-open")));
        assertEquals(422, atDeadline.status().value());

        // 换证：截止晚于当前时刻1秒 → 有效，原子切换当前指针
        ApiResult renewed = issueCert(id2, "b2-cert-2",
                TestClockConfig.FIXED_NOW.minus(Duration.ofDays(10)),
                TestClockConfig.FIXED_NOW.plusSeconds(1));
        assertEquals(200, renewed.status());
        assertEquals(200, rideService.open(id2, new OpenRequest("b2-open")).status());

        List<Map<String, Object>> certs = inspectionService.listCertificates(id2);
        assertEquals(2, certs.size(), "历史证书可查不可覆盖");
        assertEquals(false, certs.get(0).get("current"));
        assertEquals(true, certs.get(1).get("current"));
    }

    @Test
    @DisplayName("签发参数非法400且失败不占键：同键修正参数后可成功")
    void issueValidationFailureDoesNotOccupyKey() {
        long id = createRide(true);
        // 有效截止不晚于合格时刻 → 400
        ApiException bad = assertThrows(ApiException.class, () -> inspectionService
                .issueCertificate(id, new IssueCertificateRequest("iv-cert", 1L,
                        TestClockConfig.FIXED_NOW, TestClockConfig.FIXED_NOW)));
        assertEquals(400, bad.status().value());

        // 失败不占键：同 requestId 修正参数后成功
        ApiResult ok = inspectionService.issueCertificate(id, new IssueCertificateRequest(
                "iv-cert", 1L, TestClockConfig.FIXED_NOW,
                TestClockConfig.FIXED_NOW.plusSeconds(60)));
        assertEquals(200, ok.status());

        // 版本冲突同样不占键
        ApiException stale = assertThrows(ApiException.class, () -> inspectionService
                .issueCertificate(id, new IssueCertificateRequest("iv-cert-2", 99L, null, null)));
        assertEquals(409, stale.status().value());
        ApiResult retried = inspectionService.issueCertificate(id,
                new IssueCertificateRequest("iv-cert-2", versionOf(id), null, null));
        assertEquals(200, retried.status());
    }

    @Test
    @DisplayName("撤销证书立即失去开放资格；重复撤销422、证书不存在404")
    void revokeCertificateBlocksOpen() {
        long id = createRide(true);
        makeReady(id, "r1");
        long certId = ((Number) bodyOf(issueCert(id, "r1-cert", null, null)).get("certificateId"))
                .longValue();

        ApiResult revoked = inspectionService.revokeCertificate(id, certId,
                new RevokeCertificateRequest("r1-revoke", versionOf(id)));
        assertEquals(200, revoked.status());
        assertEquals(true, bodyOf(revoked).get("revoked"));

        ApiException denied = assertThrows(ApiException.class,
                () -> rideService.open(id, new OpenRequest("r1-open")));
        assertEquals(422, denied.status().value());

        // 重复撤销 → 422
        ApiException again = assertThrows(ApiException.class, () -> inspectionService
                .revokeCertificate(id, certId,
                        new RevokeCertificateRequest("r1-revoke-2", versionOf(id))));
        assertEquals(422, again.status().value());
        // 证书不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () -> inspectionService
                .revokeCertificate(id, 999999L,
                        new RevokeCertificateRequest("r1-revoke-3", versionOf(id))));
        assertEquals(404, missing.status().value());
        // 他设备证书 → 404
        long other = createRide(true);
        long otherCert = ((Number) bodyOf(issueCert(other, "r2-cert", null, null))
                .get("certificateId")).longValue();
        ApiException foreign = assertThrows(ApiException.class, () -> inspectionService
                .revokeCertificate(id, otherCert,
                        new RevokeCertificateRequest("r1-revoke-4", versionOf(id))));
        assertEquals(404, foreign.status().value());
    }

    @Test
    @DisplayName("换证原子切换唯一当前指针，历史证书不可覆盖且可查")
    void renewalSwitchesCurrentPointerAtomically() {
        long id = createRide(true);
        long certA = ((Number) bodyOf(issueCert(id, "sw-cert-a",
                TestClockConfig.FIXED_NOW.minus(Duration.ofDays(100)), null))
                .get("certificateId")).longValue();
        inspectionService.revokeCertificate(id, certA,
                new RevokeCertificateRequest("sw-revoke", versionOf(id)));
        long certB = ((Number) bodyOf(issueCert(id, "sw-cert-b", null, null))
                .get("certificateId")).longValue();

        List<Map<String, Object>> certs = inspectionService.listCertificates(id);
        assertEquals(2, certs.size());
        // 历史证书A：已撤销、非当前，内容未被覆盖
        assertEquals(certA, ((Number) certs.get(0).get("certificateId")).longValue());
        assertEquals(true, certs.get(0).get("revoked"));
        assertEquals(false, certs.get(0).get("current"));
        assertEquals(TestClockConfig.FIXED_NOW.minus(Duration.ofDays(100)).toString(),
                certs.get(0).get("passedAt"));
        // 当前证书B
        assertEquals(certB, ((Number) certs.get(1).get("certificateId")).longValue());
        assertEquals(true, certs.get(1).get("current"));
        assertEquals(true, certs.get(1).get("valid"));
        assertEquals(certB, ((Number) rideService.getRide(id).get("currentCertificateId"))
                .longValue());
    }

    @Test
    @DisplayName("大修登记：原子递增版本、暂停开放、旧证书可查但不足以复开；监督检验确认后不自动开放且不重置复开次数")
    void overhaulSuspendsAndRequiresSupervision() {
        long id = createRide(true);
        makeOpenWithCert(id, "oh");
        // 先消耗一次当日复开：故障→复检→复开（reopenCount=1）
        rideService.safetyFault(id, new SafetyFaultRequest("oh-f1", "压杠"));
        rideService.recheck(id, new RecheckRequest("oh-rc1",
                List.of(new CheckItem("压杠", true))));
        assertEquals(200, rideService.reopen(id, new ReopenRequest("oh-re1")).status());
        assertEquals(1, rideService.getRide(id).get("reopenCount"));

        // 登记大修：版本原子递增、开放中设备暂停开放
        ApiResult overhaul = inspectionService.registerOverhaul(id,
                new OverhaulRequest("oh-ov1", versionOf(id), null, "更换制动器"));
        assertEquals(200, overhaul.status());
        assertEquals(1, bodyOf(overhaul).get("overhaulVersion"));
        assertEquals(true, bodyOf(overhaul).get("openingSuspended"));
        assertEquals("STOPPED", statusOf(id));

        // 旧年度证书可查但不足以复开
        List<Map<String, Object>> certs = inspectionService.listCertificates(id);
        assertEquals(1, certs.size());
        assertEquals(true, certs.get(0).get("valid"));
        rideService.recheck(id, new RecheckRequest("oh-rc2",
                List.of(new CheckItem("制动系统", true))));
        ApiException denied = assertThrows(ApiException.class,
                () -> rideService.reopen(id, new ReopenRequest("oh-re2")));
        assertEquals(422, denied.status().value());
        assertTrue(denied.getMessage().contains("监督检验"));

        // 绑定错误大修版本 → 422
        ApiException wrongVersion = assertThrows(ApiException.class, () -> inspectionService
                .confirmSupervision(id, new SupervisionInspectionRequest("oh-si0",
                        versionOf(id), 0L, "检验员甲", "检验员乙",
                        List.of(new CheckItem("制动系统", true)))));
        assertEquals(422, wrongVersion.status().value());
        // 两名检验人相同 → 422
        ApiException sameInspector = assertThrows(ApiException.class, () -> inspectionService
                .confirmSupervision(id, new SupervisionInspectionRequest("oh-si1",
                        versionOf(id), 1L, "检验员甲", "检验员甲",
                        List.of(new CheckItem("制动系统", true)))));
        assertEquals(422, sameInspector.status().value());
        // 清单未全合格 → 记录但不恢复资格
        ApiResult failed = inspectionService.confirmSupervision(id,
                new SupervisionInspectionRequest("oh-si2", versionOf(id), 1L,
                        "检验员甲", "检验员乙",
                        List.of(new CheckItem("制动系统", false))));
        assertEquals(200, failed.status());
        assertEquals(false, bodyOf(failed).get("allPassed"));
        assertEquals(false, bodyOf(failed).get("eligibilityRestored"));

        // 两名不同检验人全清单合格 → 只恢复资格，不自动开放、不重置复开次数
        ApiResult confirmed = inspectionService.confirmSupervision(id,
                new SupervisionInspectionRequest("oh-si3", versionOf(id), 1L,
                        "检验员甲", "检验员乙",
                        List.of(new CheckItem("制动系统", true),
                                new CheckItem("压杠", true))));
        assertEquals(200, confirmed.status());
        assertEquals(true, bodyOf(confirmed).get("eligibilityRestored"));
        assertEquals("RECHECKED", bodyOf(confirmed).get("status"), "确认后不自动开放");
        assertEquals(1, bodyOf(confirmed).get("reopenCount"), "确认后不重置当日复开次数");

        // 复开成功且复开次数在原有基础上累加（未被重置）
        ApiResult reopened = rideService.reopen(id, new ReopenRequest("oh-re2"));
        assertEquals(200, reopened.status());
        assertEquals(2, bodyOf(reopened).get("reopenCount"));
        assertEquals("OPEN", statusOf(id));
    }

    @Test
    @DisplayName("再次大修使旧监督检验失效：须绑定当前大修版本重新确认")
    void newOverhaulInvalidatesPreviousSupervision() {
        long id = createRide(true);
        makeOpenWithCert(id, "no");
        inspectionService.registerOverhaul(id, new OverhaulRequest("no-ov1", versionOf(id),
                null, "第一次大修"));
        assertEquals(200, inspectionService.confirmSupervision(id,
                new SupervisionInspectionRequest("no-si1", versionOf(id), 1L,
                        "检验员甲", "检验员乙", List.of(new CheckItem("制动系统", true)))).status());
        assertTrue((Boolean) inspectionService.eligibility(id).get("gatePassed"));

        // 第二次大修：旧监督检验失效
        inspectionService.registerOverhaul(id, new OverhaulRequest("no-ov2", versionOf(id),
                null, "第二次大修"));
        Map<String, Object> evidence = inspectionService.eligibility(id);
        assertEquals(2L, evidence.get("overhaulVersion"));
        assertEquals(false, evidence.get("gatePassed"));
        assertTrue((Boolean) evidence.get("supervisionRequired"));
        assertFalse((Boolean) evidence.get("supervisionSatisfied"));

        // 绑定旧版本v1 → 422；绑定当前版本v2 → 恢复资格
        ApiException stale = assertThrows(ApiException.class, () -> inspectionService
                .confirmSupervision(id, new SupervisionInspectionRequest("no-si2",
                        versionOf(id), 1L, "检验员甲", "检验员乙",
                        List.of(new CheckItem("制动系统", true)))));
        assertEquals(422, stale.status().value());
        assertEquals(200, inspectionService.confirmSupervision(id,
                new SupervisionInspectionRequest("no-si3", versionOf(id), 2L,
                        "检验员甲", "检验员丙", List.of(new CheckItem("制动系统", true)))).status());
        assertTrue((Boolean) inspectionService.eligibility(id).get("gatePassed"));
        // 监督检验记录均可查（含清单明细）
        assertEquals(2, inspectionService.listInspections(id).size());
        assertEquals(2, inspectionService.listOverhauls(id).size());
    }

    @Test
    @DisplayName("证书过期设备的载客读数只记录并告警，不以正常读数恢复资格")
    void expiredCertificateReadingOnlyAlarms() {
        long id = createRide(true);
        makeReady(id, "ex");
        assertEquals(200, issueCert(id, "ex-cert", null,
                TestClockConfig.FIXED_NOW.plus(Duration.ofHours(2))).status());
        assertEquals(200, rideService.open(id, new OpenRequest("ex-open")).status());

        // 时钟推进到证书过期后
        clock.setInstant(TestClockConfig.FIXED_NOW.plus(Duration.ofHours(3)));
        // 正常读数：只记录并告警，状态不变
        Map<String, Object> normal = rideService.reading(id, new ReadingRequest(5, 50));
        assertEquals(true, normal.get("alarm"));
        assertEquals(false, normal.get("certificateValid"));
        assertEquals(false, normal.get("exceeded"));
        assertEquals("OPEN", normal.get("status"));
        // 超阈值读数：同样只记录并告警，不触发缓停
        Map<String, Object> over = rideService.reading(id, new ReadingRequest(999, 999));
        assertEquals(true, over.get("alarm"));
        assertEquals(true, over.get("exceeded"));
        assertEquals("OPEN", statusOf(id));

        // 读数与告警事件均已记录
        assertEquals(2, rideService.listReadings(id).size());
        long alarms = rideService.listEvents(id).stream()
                .filter(ev -> "CERT_INVALID_ALARM".equals(ev.get("event_type"))).count();
        assertEquals(2, alarms);
        // 收到正常读数不恢复资格
        Map<String, Object> evidence = inspectionService.eligibility(id);
        assertEquals(false, evidence.get("gatePassed"));
        assertTrue(evidence.get("reasons").toString().contains("已过期"));
    }

    @Test
    @DisplayName("开放后撤销证书：载客读数告警且资格证据失效")
    void revokedCertificateReadingAlarms() {
        long id = createRide(true);
        makeOpenWithCert(id, "rv");
        long certId = ((Number) rideService.getRide(id).get("currentCertificateId")).longValue();
        assertEquals(200, inspectionService.revokeCertificate(id, certId,
                new RevokeCertificateRequest("rv-revoke", versionOf(id))).status());

        Map<String, Object> reading = rideService.reading(id, new ReadingRequest(5, 50));
        assertEquals(true, reading.get("alarm"));
        Map<String, Object> evidence = inspectionService.eligibility(id);
        assertEquals(false, evidence.get("gatePassed"));
        assertTrue(evidence.get("reasons").toString().contains("已撤销"));
    }

    @Test
    @DisplayName("幂等：同键同参重放首次冻结结果，异参409，业务只执行一次")
    void idempotentReplayAndConflict() {
        long id = createRide(true);
        IssueCertificateRequest request = new IssueCertificateRequest("idem-cert", 1L,
                TestClockConfig.FIXED_NOW, null);
        ApiResult first = inspectionService.issueCertificate(id, request);
        ApiResult replay = inspectionService.issueCertificate(id, request);
        assertEquals(200, first.status());
        assertEquals(first.body(), replay.body(), "同键同参重放首次冻结结果");
        assertEquals(1, inspectionService.listCertificates(id).size(), "业务只执行一次");

        // 同键异参 → 409，状态不变
        ApiException conflict = assertThrows(ApiException.class, () -> inspectionService
                .issueCertificate(id, new IssueCertificateRequest("idem-cert", 1L,
                        TestClockConfig.FIXED_NOW.plusSeconds(1), null)));
        assertEquals(409, conflict.status().value());
        assertEquals(1, inspectionService.listCertificates(id).size());
        assertEquals(2, versionOf(id));

        // 大修登记同键重放：版本不重复递增
        OverhaulRequest overhaul = new OverhaulRequest("idem-ov", versionOf(id), null, "大修");
        ApiResult oh1 = inspectionService.registerOverhaul(id, overhaul);
        ApiResult oh2 = inspectionService.registerOverhaul(id, overhaul);
        assertEquals(oh1.body(), oh2.body());
        assertEquals(1, inspectionService.listOverhauls(id).size());
        assertEquals(1L, rideService.getRide(id).get("overhaulVersion"));
    }

    @Test
    @DisplayName("指纹结构化无歧义：合法文本含分隔符也须区分")
    void fingerprintIsStructuredAndUnambiguous() {
        SupervisionInspectionRequest a = new SupervisionInspectionRequest("fp", 1L, 1L,
                "检验员|甲", "检验员乙", List.of(new CheckItem("制动系统", true)));
        SupervisionInspectionRequest b = new SupervisionInspectionRequest("fp", 1L, 1L,
                "检验员", "甲|检验员乙", List.of(new CheckItem("制动系统", true)));
        assertNotEquals(idempotency.fingerprint("fp", a), idempotency.fingerprint("fp", b),
                "含分隔符的不同参数组合必须产生不同指纹");
        assertEquals(idempotency.fingerprint("fp", a), idempotency.fingerprint("fp", a),
                "相同参数指纹必须确定");
    }

    @Test
    @DisplayName("资格证据查询：未受检设备直接通过，受检设备给出失效原因")
    void eligibilityEvidence() {
        long legacy = createRide(false);
        Map<String, Object> legacyEvidence = inspectionService.eligibility(legacy);
        assertEquals(false, legacyEvidence.get("inspectionRequired"));
        assertEquals(true, legacyEvidence.get("gatePassed"));

        long id = createRide(true);
        Map<String, Object> noCert = inspectionService.eligibility(id);
        assertEquals(false, noCert.get("gatePassed"));
        assertTrue(noCert.get("reasons").toString().contains("无当前年度检验证书"));
        assertEquals(false, noCert.get("supervisionRequired"));

        issueCert(id, "ee-cert", null, null);
        Map<String, Object> withCert = inspectionService.eligibility(id);
        assertEquals(true, withCert.get("gatePassed"));
        assertEquals(true, ((Map<?, ?>) withCert.get("currentCertificate")).get("current"));
    }

    @Test
    @DisplayName("已归档设备拒绝签发证书、登记大修与监督检验（422）")
    void archivedRideRejectsInspectionOperations() {
        long id = createRide(true);
        makeOpenWithCert(id, "ar");
        rideService.archive(id, new com.example.starter.ride.dto.RideDtos.ArchiveRequest("ar-arch"));

        ApiException issue = assertThrows(ApiException.class,
                () -> issueCert(id, "ar-cert-2", null, null));
        assertEquals(422, issue.status().value());
        ApiException overhaul = assertThrows(ApiException.class, () -> inspectionService
                .registerOverhaul(id, new OverhaulRequest("ar-ov", versionOf(id), null, null)));
        assertEquals(422, overhaul.status().value());
        ApiException supervision = assertThrows(ApiException.class, () -> inspectionService
                .confirmSupervision(id, new SupervisionInspectionRequest("ar-si",
                        versionOf(id), 0L, "检验员甲", "检验员乙",
                        List.of(new CheckItem("制动系统", true)))));
        assertEquals(422, supervision.status().value());
    }
}
