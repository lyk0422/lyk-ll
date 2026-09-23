package com.example.starter.calibration.service;

import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CreateParamRequest;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.api.ResolveResponse;
import com.example.starter.calibration.api.RollbackRequest;
import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.ParamStatus;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.repo.CalibrationRepository;
import com.example.starter.calibration.support.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 H2（MODE=MySQL）的事务/并发集成测试：
 * 错误标定拒绝、生效范围冲突、灰度部分失败整批回滚、未参与车辆不受影响、
 * 回滚恢复发布前版本、resolutionId 历史不可变、并发灰度串行化。
 */
@SpringBootTest
class CalibrationServiceIntegrationTest {

    private static final String KEY = "cam_front.intrinsic";
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    @Autowired
    private CalibrationService service;
    @Autowired
    private CalibrationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeProvider timeProvider;

    @BeforeEach
    void clean() {
        timeProvider.setClock(Clock.fixed(T0, ZoneOffset.UTC));
        jdbc.update("DELETE FROM resolution_record");
        jdbc.update("DELETE FROM canary_vehicle");
        jdbc.update("DELETE FROM canary_release");
        jdbc.update("DELETE FROM param_status_log");
        jdbc.update("DELETE FROM calibration_param");
    }

    // ---------- helpers ----------

    private Map<String, Object> intrinsic(double fx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fx", fx);
        m.put("fy", 1048.5);
        m.put("cx", 960.0);
        m.put("cy", 540.0);
        m.put("distortion", List.of(-0.05, 0.01, 0.0, 0.0, 0.0));
        return m;
    }

    private void createDraft(String key, String version, double fx, List<String> vehicles) {
        createDraft(key, version, fx, vehicles, null, null);
    }

    private void createDraft(String key, String version, double fx, List<String> vehicles,
                             Instant from, Instant to) {
        service.create(new CreateParamRequest(key, CalibrationKind.INTRINSIC,
                SensorType.CAMERA, version, intrinsic(fx), from, to,
                vehicles, null, null, null, "tester"));
    }

    private void baselineActive(String version) {
        createDraft(KEY, version, 1050.0, null);
        service.approve(KEY, version, "tester");
        service.activate(KEY, version, "tester");
    }

    private String resolveVersion(String vehicle) {
        return service.resolve(new ResolveRequest(KEY, vehicle, null, null, T0)).version();
    }

    // ---------- 校验失败：不能创建可审批版本 ----------

    @Test
    void rejectsInvalidCalibrationAndPersistsNothing() {
        Map<String, Object> bad = intrinsic(1050.0);
        bad.put("fx", -5.0);
        CreateParamRequest req = new CreateParamRequest(KEY, CalibrationKind.INTRINSIC,
                SensorType.CAMERA, "bad", bad, null, null, null, null, null, null, "tester");
        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(req));
        assertTrue(ex.getMessage().contains("焦距"));
        // 校验失败不写入任何版本
        assertEquals(0, repository.listByParamKey(KEY).size());
        assertEquals(0, count("calibration_param"));
    }

    @Test
    void rejectsApproveOfNonExistentAndWrongState() {
        baselineActive("v1");
        // 重复审批已生效版本 → 冲突
        assertThrows(ConflictException.class, () -> service.approve(KEY, "v1", "tester"));
        assertThrows(NotFoundException.class, () -> service.approve(KEY, "nope", "tester"));
    }

    // ---------- 生效范围冲突 ----------

    @Test
    void rejectsTwoApprovedVersionsWithIdenticalEffectiveRange() {
        createDraft(KEY, "v1", 1050.0, null);
        service.approve(KEY, "v1", "tester");
        createDraft(KEY, "v2", 1055.0, null);
        // 完全相同的车辆（全部）与时间范围：不得同时存在两个待发布有效版本
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.approve(KEY, "v2", "tester"));
        assertTrue(ex.getMessage().contains("冲突"));
        // v2 仍是草稿，不能进入发布
        assertEquals(ParamStatus.DRAFT,
                repository.findParamByKeyAndVersion(KEY, "v2").getStatus());
    }

    @Test
    void rejectsOverlappingTimeWindows() {
        createDraft(KEY, "v1", 1050.0, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));
        service.approve(KEY, "v1", "tester");
        createDraft(KEY, "v2", 1055.0, null,
                Instant.parse("2026-05-01T00:00:00Z"), null);
        assertThrows(ValidationException.class, () -> service.approve(KEY, "v2", "tester"));
    }

    @Test
    void resolvesByTimeWindow() {
        createDraft(KEY, "v1", 1050.0, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));
        service.approve(KEY, "v1", "tester");
        service.activate(KEY, "v1", "tester");
        createDraft(KEY, "v2", 1055.0, null,
                Instant.parse("2026-06-01T00:00:00Z"), null);
        service.approve(KEY, "v2", "tester");
        service.activate(KEY, "v2", "tester");

        assertEquals("v1", service.resolve(new ResolveRequest(KEY, "V1", null, null,
                Instant.parse("2026-05-01T00:00:00Z"))).version());
        // 边界：effectiveTo 不含
        assertEquals("v2", service.resolve(new ResolveRequest(KEY, "V1", null, null,
                Instant.parse("2026-06-01T00:00:00Z"))).version());
    }

    // ---------- 灰度：未参与车辆不受影响 ----------

    @Test
    void canaryOnlyAffectsSpecifiedVehicles() {
        baselineActive("v1");
        createDraft(KEY, "v2", 1055.0, List.of("V001", "V002"));
        service.approve(KEY, "v2", "tester");
        CanaryRelease release = service.canaryRelease(new CanaryReleaseRequest(
                KEY, "v2", List.of("V001"), "tester", "首批"));

        assertEquals("v2", resolveVersion("V001"));
        assertEquals("v1", resolveVersion("V002"));
        assertEquals("v1", resolveVersion("V999"));
        assertEquals(ParamStatus.CANARY,
                repository.findParamByKeyAndVersion(KEY, "v2").getStatus());
        assertNotNull(release.getReleaseId());
    }

    // ---------- 灰度部分失败：整批原子回滚 ----------

    @Test
    void canaryBatchFailsAtomicallyWhenOneVehicleConflicts() {
        baselineActive("v1");
        // 第一批灰度 V001 成功
        createDraft(KEY, "v2", 1055.0, List.of("V001", "V002", "V003"));
        service.approve(KEY, "v2", "tester");
        service.canaryRelease(new CanaryReleaseRequest(
                KEY, "v2", List.of("V001"), "tester", "第一批"));

        // 第二版本尝试批量 [V002, V001]，其中 V001 已在灰度 → 整批失败
        createDraft(KEY, "v3", 1060.0, List.of("V001", "V002", "V003"));
        service.approve(KEY, "v3", "tester");
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.canaryRelease(new CanaryReleaseRequest(
                        KEY, "v3", List.of("V002", "V001"), "tester", "冲突批")));
        assertTrue(ex.getMessage().contains("V001"));

        // 整批回滚：没有产生第二个发布单与车辆明细，v3 回到已批准，V002 仍解析 v1
        List<CanaryRelease> releases = repository.listActiveReleasesByParamKey(KEY);
        assertEquals(1, releases.size());
        assertEquals(1, count("canary_release"));
        assertEquals(1, count("canary_vehicle"));
        assertEquals(ParamStatus.APPROVED,
                repository.findParamByKeyAndVersion(KEY, "v3").getStatus());
        assertEquals("v1", resolveVersion("V002"));
        assertEquals("v2", resolveVersion("V001"));
    }

    @Test
    void canaryRejectsDuplicateVehiclesInBatch() {
        baselineActive("v1");
        createDraft(KEY, "v2", 1055.0, null);
        service.approve(KEY, "v2", "tester");
        assertThrows(ValidationException.class, () -> service.canaryRelease(
                new CanaryReleaseRequest(KEY, "v2", List.of("V001", "V001"), "tester", null)));
        assertEquals(0, count("canary_release"));
    }

    // ---------- 回滚：仅影响灰度车辆并恢复各自发布前版本 ----------

    @Test
    void rollbackRestoresPerVehiclePreviousVersions() {
        baselineActive("v1");
        createDraft(KEY, "v2", 1055.0, List.of("V001", "V002"));
        service.approve(KEY, "v2", "tester");
        CanaryRelease release = service.canaryRelease(new CanaryReleaseRequest(
                KEY, "v2", List.of("V001", "V002"), "tester", "灰度"));
        assertEquals("v2", resolveVersion("V001"));
        assertEquals("v2", resolveVersion("V002"));

        service.rollback(new RollbackRequest(release.getReleaseId(), "tester", "发现异常"));

        // 两辆车都恢复发布前 v1；灰度版本置为已回滚
        assertEquals("v1", resolveVersion("V001"));
        assertEquals("v1", resolveVersion("V002"));
        assertEquals("v1", resolveVersion("V999"));
        assertEquals(ParamStatus.ROLLED_BACK,
                repository.findParamByKeyAndVersion(KEY, "v2").getStatus());
        // 已回滚发布单不可重复回滚
        assertThrows(ConflictException.class, () -> service.rollback(
                new RollbackRequest(release.getReleaseId(), "tester", null)));
    }

    @Test
    void rollbackToNullWhenNoPreviousVersion() {
        // 该参数此前无全量版本，灰度车辆发布前无版本；回滚后这些车辆无版本可解析
        createDraft(KEY, "v1", 1050.0, List.of("V001"));
        service.approve(KEY, "v1", "tester");
        CanaryRelease release = service.canaryRelease(new CanaryReleaseRequest(
                KEY, "v1", List.of("V001"), "tester", null));
        assertEquals("v1", resolveVersion("V001"));
        service.rollback(new RollbackRequest(release.getReleaseId(), "tester", null));
        assertThrows(NotFoundException.class, () -> resolveVersion("V001"));
    }

    // ---------- 全量推广 ----------

    @Test
    void promoteMakesCanaryVersionActiveEverywhereAndSupersedesOld() {
        baselineActive("v1");
        createDraft(KEY, "v2", 1055.0, List.of("V001"));
        service.approve(KEY, "v2", "tester");
        CanaryRelease release = service.canaryRelease(new CanaryReleaseRequest(
                KEY, "v2", List.of("V001"), "tester", null));
        service.promote(release.getReleaseId(), "tester");

        assertEquals("v2", resolveVersion("V001"));
        assertEquals("v2", resolveVersion("V999"));
        assertEquals(ParamStatus.ACTIVE,
                repository.findParamByKeyAndVersion(KEY, "v2").getStatus());
        assertEquals(ParamStatus.SUPERSEDED,
                repository.findParamByKeyAndVersion(KEY, "v1").getStatus());
    }

    // ---------- 历史 resolutionId 不可变 ----------

    @Test
    void resolutionIdStaysStableAcrossLaterReleaseAndRollback() {
        baselineActive("v1");
        ResolveResponse before = service.resolve(
                new ResolveRequest(KEY, "V001", null, null, T0));
        assertEquals("v1", before.version());
        String rid = before.resolutionId();

        createDraft(KEY, "v2", 1055.0, List.of("V001"));
        service.approve(KEY, "v2", "tester");
        CanaryRelease release = service.canaryRelease(new CanaryReleaseRequest(
                KEY, "v2", List.of("V001"), "tester", null));
        // 灰度后实时解析变为 v2
        assertEquals("v2", resolveVersion("V001"));

        // 历史 resolutionId 仍指向 v1 及其内容快照
        var history = service.getResolution(rid);
        assertEquals("v1", history.resolvedVersion());
        assertEquals(before.contentDigest(), history.contentDigest());
        assertEquals(1050.0, ((Number) ((Map<?, ?>) history.content()).get("fx")).doubleValue(), 0.0);

        service.rollback(new RollbackRequest(release.getReleaseId(), "tester", null));
        var historyAfter = service.getResolution(rid);
        assertEquals("v1", historyAfter.resolvedVersion());
        assertEquals(before.contentDigest(), historyAfter.contentDigest());
        // 历史记录只有一条（只增不改）
        assertEquals(1, countWhere("resolution_record", "resolution_id", rid));
    }

    // ---------- 并发：重叠车辆集合的灰度被串行化，只有一批成功 ----------

    @Test
    void concurrentCanaryReleasesOverOverlappingVehiclesOnlyOneSucceeds() throws Exception {
        baselineActive("v1");
        createDraft(KEY, "v2", 1055.0, List.of("V001", "V002", "V003"));
        service.approve(KEY, "v2", "tester");
        createDraft(KEY, "v3", 1060.0, List.of("V001", "V002", "V003"));
        service.approve(KEY, "v3", "tester");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                await(start);
                service.canaryRelease(new CanaryReleaseRequest(
                        KEY, "v2", List.of("V001", "V002"), "op-1", null));
            });
            Future<?> f2 = pool.submit(() -> {
                await(start);
                service.canaryRelease(new CanaryReleaseRequest(
                        KEY, "v3", List.of("V002", "V003"), "op-2", null));
            });
            start.countDown();

            boolean firstOk;
            boolean secondOk;
            try {
                f1.get(20, TimeUnit.SECONDS);
                firstOk = true;
            } catch (Exception e) {
                firstOk = false;
            }
            try {
                f2.get(20, TimeUnit.SECONDS);
                secondOk = true;
            } catch (Exception e) {
                secondOk = false;
            }
            assertNotEquals(firstOk, secondOk, "重叠车辆并发灰度必须恰好一批成功");

            // 数据库最终一致：只有一个进行中发布单，冲突车辆 V002 只属于一个版本
            assertEquals(1, repository.listActiveReleasesByParamKey(KEY).size());
            String v002 = resolveVersion("V002");
            String v001 = resolveVersion("V001");
            String v003 = resolveVersion("V003");
            if (firstOk) {
                assertEquals("v2", v001);
                assertEquals("v2", v002);
                assertEquals("v1", v003);
            } else {
                assertEquals("v1", v001);
                assertEquals("v3", v002);
                assertEquals("v3", v003);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 状态转换审计保存操作者与时间 ----------

    @Test
    void statusTransitionsRecordOperatorAndTime() {
        baselineActive("v1");
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM param_status_log l JOIN calibration_param p ON p.id = l.param_id "
                        + "WHERE p.param_key = ? AND l.operator = 'tester' "
                        + "AND l.operated_at = ?",
                Integer.class, KEY, java.sql.Timestamp.from(T0));
        assertNotNull(logs);
        assertTrue(logs >= 3, "应至少记录 创建/审批/生效 三次状态转换");
    }

    // ---------- 小工具 ----------

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
