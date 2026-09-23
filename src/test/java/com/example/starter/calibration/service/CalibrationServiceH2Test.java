package com.example.starter.calibration.service;

import com.example.starter.calibration.AbstractH2Test;
import com.example.starter.calibration.ConflictException;
import com.example.starter.calibration.NotFoundException;
import com.example.starter.calibration.ValidationException;
import com.example.starter.calibration.api.ApproveRequest;
import com.example.starter.calibration.api.CalibrationResponse;
import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CanaryReleaseResponse;
import com.example.starter.calibration.api.CreateCalibrationRequest;
import com.example.starter.calibration.api.OperatorRequest;
import com.example.starter.calibration.api.ResolutionHistoryResponse;
import com.example.starter.calibration.api.ResolutionResponse;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CanaryStatus;
import com.example.starter.calibration.domain.ParamKind;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于真实 H2（MODE=MySQL）与真实事务的服务集成测试：
 * 覆盖错误标定拒绝、生效范围冲突、灰度部分失败整批回滚、未参与车辆不受影响、
 * 回滚恢复发布前版本、历史 resolutionId 不变、转正替代与并发灰度冲突。
 */
class CalibrationServiceH2Test extends AbstractH2Test {

    @Autowired
    private CalibrationService service;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private TimeService timeService;

    private final Clock systemClock = Clock.systemUTC();

    @AfterEach
    void resetClock() {
        timeService.setClock(systemClock);
    }

    // ------------------------------------------------------------------
    // 错误标定拒绝
    // ------------------------------------------------------------------

    @Test
    void invalidCalibrationRejectedWith422AndNotPersisted() {
        JsonNode bad = mapper.createObjectNode().put("fx", 0.0).put("fy", 1.0);
        CreateCalibrationRequest req = intrinsicRequest("cam_front", "1.0.0", bad, null, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("fx");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_version WHERE param_key = 'cam_front'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void draftWithCorruptedContentCannotBeApproved() {
        // 正常创建一份草稿后，绕过服务直接把内容篡改为非法数据，审批必须再次拦截
        CalibrationResponse draft = createCandidate("cam_front", "1.0.0", 1000.0, null, null, false);
        jdbc.update("UPDATE calibration_version SET content_json = ? WHERE id = ?",
                "{\"fx\":-1,\"fy\":1}", draft.id());

        assertThatThrownBy(() -> service.approve(draft.id(), new ApproveRequest("bob")))
                .isInstanceOf(ValidationException.class);
        assertThat(service.getTransitions(draft.id()))
                .extracting(t -> t.toStatus())
                .containsExactly("DRAFT");
    }

    // ------------------------------------------------------------------
    // 审批 / 生效 / 状态流转审计
    // ------------------------------------------------------------------

    @Test
    void approveAndActivateRecordOperatorAndTime() {
        CalibrationResponse v = createCandidate("cam_front", "1.0.0", 1000.0, null, null, false);
        CalibrationResponse approved = service.approve(v.id(), new ApproveRequest("bob"));
        assertThat(approved.status()).isEqualTo(CalibrationStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("bob");
        assertThat(approved.approvedAt()).isNotNull();

        CalibrationResponse active = service.activate(v.id(), new OperatorRequest("carol"));
        assertThat(active.status()).isEqualTo(CalibrationStatus.ACTIVE);
        assertThat(active.activatedBy()).isEqualTo("carol");
        assertThat(active.activatedAt()).isNotNull();

        assertThat(service.getTransitions(v.id()))
                .extracting(t -> t.toStatus() + "/" + t.operator())
                .containsExactly("DRAFT/alice", "APPROVED/bob", "ACTIVE/carol");
    }

    @Test
    void draftCannotBeActivatedDirectly() {
        CalibrationResponse draft = createCandidate("cam_front", "1.0.0", 1000.0, null, null, false);
        assertThatThrownBy(() -> service.activate(draft.id(), new OperatorRequest("bob")))
                .isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------
    // 生效范围冲突
    // ------------------------------------------------------------------

    @Test
    void identicalScopeCannotHaveTwoActiveVersions() {
        CalibrationResponse v1 = createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "1.0.1", 1010.0, null, null, false);

        assertThatThrownBy(() -> service.activate(v2.id(), new OperatorRequest("carol")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("完全相同");

        // v1 保持生效
        assertThat(jdbc.queryForObject(
                "SELECT status FROM calibration_version WHERE id = ?", String.class, v1.id()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_version WHERE param_key = 'cam_front' AND status = 'ACTIVE'",
                Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 解析：车辆/传感器/地图/时间匹配 + resolutionId
    // ------------------------------------------------------------------

    @Test
    void resolveMatchesVehicleSensorMapAndTimeAndIsTraceable() {
        CalibrationResponse v = createAndActivate("lidar_top", "2.0.0", null, null, null);

        // 传感器/地图/时间命中
        ResolutionResponse hit = service.resolve(new ResolveRequest(
                "lidar_top", "V010", "lidar-top-A", "map-v3", null));
        assertThat(hit.selectedVersionId()).isEqualTo(v.id());
        assertThat(hit.resolutionId()).isNotBlank();
        assertThat(hit.status()).isEqualTo(CalibrationStatus.ACTIVE);

        // 新增只覆盖传感器 lidar-top-A 的版本（范围在审批/生效前写入，避免与基线范围完全相同）
        CalibrationResponse scopedDraft = createCandidate("lidar_top", "2.1.0", null, null, null, true);
        jdbc.update("UPDATE calibration_version SET sensor_from = ?, sensor_to = ? WHERE id = ?",
                "lidar-top-A", "lidar-top-A", scopedDraft.id());
        CalibrationResponse scoped = service.approve(scopedDraft.id(), new ApproveRequest("bob"));
        service.activate(scoped.id(), new OperatorRequest("carol"));
        ResolutionResponse narrow = service.resolve(new ResolveRequest(
                "lidar_top", "V010", "lidar-top-A", "map-v3", null));
        assertThat(narrow.selectedVersionId()).isEqualTo(scoped.id());
        ResolutionResponse miss = service.resolve(new ResolveRequest(
                "lidar_top", "V010", "lidar-top-Z", "map-v3", null));
        assertThat(miss.selectedVersionId()).isEqualTo(v.id());

        // 与开放基线 v 完全相同范围的新版本不得再次全量生效
        CalibrationResponse duplicate = createCandidate("lidar_top", "9.0.0", null, null, null, true);
        service.approve(duplicate.id(), new ApproveRequest("bob"));
        assertThatThrownBy(() -> service.activate(duplicate.id(), new OperatorRequest("carol")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("完全相同");

        // 完全无匹配的参数键 → 404
        assertThatThrownBy(() -> service.resolve(new ResolveRequest(
                "unknown_key", "V010", null, null, Instant.parse("2026-06-01T00:00:00Z"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void canaryFailsWhenOutsideEffectiveTimeWindow() {
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        CalibrationResponse future = createApprovedCandidate("cam_front", "2.0.0", 1010.0,
                null, null, true);
        jdbc.update("UPDATE calibration_version SET effective_from = ? WHERE id = ?",
                java.sql.Timestamp.from(t0.plusSeconds(86400)), future.id());

        assertThatThrownBy(() -> service.startCanary(
                new CanaryReleaseRequest(future.id(), List.of("V001"), "bob")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("生效时间范围");

        // 时钟拨到生效之后，灰度可发起
        timeService.setClock(Clock.fixed(t0.plusSeconds(2 * 86400), ZoneOffset.UTC));
        CanaryReleaseResponse release = service.startCanary(
                new CanaryReleaseRequest(future.id(), List.of("V001"), "bob"));
        assertThat(release.status()).isEqualTo(CanaryStatus.IN_PROGRESS);
    }

    // ------------------------------------------------------------------
    // 灰度：原子批量、部分失败整批回滚、未参与车辆不受影响
    // ------------------------------------------------------------------

    @Test
    void canaryPartialFailureRollsBackEntireBatch() {
        CalibrationResponse v1 = createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        // v2 车辆范围 V000..V050：V070 不在范围，整批失败
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "2.0.0", 1010.0, "V000", "V050", false);

        assertThatThrownBy(() -> service.startCanary(
                new CanaryReleaseRequest(v2.id(), List.of("V001", "V070"), "bob")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("V070");

        // 整批回滚：无批次、无车辆条目、无占用锁、v2 仍为 APPROVED
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_release", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_vehicle", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_vehicle_lock", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM calibration_version WHERE id = ?",
                String.class, v2.id())).isEqualTo("APPROVED");

        // 参与/未参与车辆都继续解析旧版本
        assertThat(resolveVersion("cam_front", "V001")).isEqualTo(v1.id());
        assertThat(resolveVersion("cam_front", "V070")).isEqualTo(v1.id());
    }

    @Test
    void canarySwitchesOnlyListedVehicles() {
        CalibrationResponse v1 = createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "2.0.0", 1010.0, null, null, false);
        service.startCanary(new CanaryReleaseRequest(v2.id(), List.of("V001"), "bob"));

        assertThat(resolveVersion("cam_front", "V001")).isEqualTo(v2.id());
        // 未参与灰度的车辆继续解析旧版本
        assertThat(resolveVersion("cam_front", "V002")).isEqualTo(v1.id());
        assertThat(resolveVersion("cam_front", "V099")).isEqualTo(v1.id());
    }

    @Test
    void duplicateOrEmptyVehicleSetRejected() {
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "1.0.0", 1000.0, null, null, false);
        assertThatThrownBy(() -> service.startCanary(
                new CanaryReleaseRequest(v2.id(), List.of(), "bob")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.startCanary(
                new CanaryReleaseRequest(v2.id(), List.of("V001", "V001"), "bob")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void overlappingCanariesConflictAndRollbackWholeBatch() throws Exception {
        createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "2.0.0", 1010.0, null, null, false);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                await(start);
                service.startCanary(new CanaryReleaseRequest(v2.id(), List.of("V001", "V002"), "bob"));
            });
            Future<?> f2 = pool.submit(() -> {
                await(start);
                try {
                    service.startCanary(new CanaryReleaseRequest(v2.id(), List.of("V002", "V003"), "carol"));
                } catch (Throwable t) {
                    firstError.set(t);
                    throw t;
                }
            });
            start.countDown();
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 其中一个事务必然失败
        } finally {
            pool.shutdownNow();
        }

        // 恰好一个进行中批次、只有赢家车辆持有锁、版本仅一次进入 CANARY
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM canary_release WHERE status = 'IN_PROGRESS'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_vehicle", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_vehicle_lock", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM calibration_version WHERE id = ?",
                String.class, v2.id())).isEqualTo("CANARY");
    }

    // ------------------------------------------------------------------
    // 回滚恢复 + 历史 resolutionId 不变
    // ------------------------------------------------------------------

    @Test
    void rollbackRestoresPreviousVersionsAndFreezesHistory() {
        CalibrationResponse v1 = createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "2.0.0", 1010.0, null, null, false);
        CanaryReleaseResponse release = service.startCanary(
                new CanaryReleaseRequest(v2.id(), List.of("V001", "V002"), "bob"));

        ResolutionResponse duringCanary = service.resolve(
                new ResolveRequest("cam_front", "V001", null, null, null));
        assertThat(duringCanary.selectedVersionId()).isEqualTo(v2.id());
        assertThat(duringCanary.canaryReleaseId()).isEqualTo(release.releaseId());
        String resolutionId = duringCanary.resolutionId();

        // 回滚只影响该次灰度车辆
        CanaryReleaseResponse rolledBack = service.rollbackCanary(
                release.releaseId(), new OperatorRequest("dave"));
        assertThat(rolledBack.status()).isEqualTo(CanaryStatus.ROLLED_BACK);

        assertThat(resolveVersion("cam_front", "V001")).isEqualTo(v1.id());
        assertThat(resolveVersion("cam_front", "V002")).isEqualTo(v1.id());
        assertThat(resolveVersion("cam_front", "V003")).isEqualTo(v1.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_vehicle_lock", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM calibration_version WHERE id = ?",
                String.class, v2.id())).isEqualTo("ROLLED_BACK");

        // 历史 resolutionId 仍指向当时的 v2，内容不变
        ResolutionHistoryResponse history = service.getHistory(resolutionId);
        assertThat(history.selectedVersionId()).isEqualTo(v2.id());
        assertThat(history.selectedVersion()).isEqualTo("2.0.0");
        assertThat(history.content().get("fx").asDouble()).isEqualTo(1010.0);
        assertThat(history.canaryReleaseId()).isEqualTo(release.releaseId());

        // 已回滚批次不可重复回滚
        assertThatThrownBy(() -> service.rollbackCanary(release.releaseId(), new OperatorRequest("dave")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void promotionReplacesBaselineButHistoryStaysImmutable() {
        CalibrationResponse v1 = createAndActivate("cam_front", "1.0.0", 1000.0, null, null);
        CalibrationResponse v2 = createApprovedCandidate("cam_front", "2.0.0", 1010.0, null, null, false);
        CanaryReleaseResponse release = service.startCanary(
                new CanaryReleaseRequest(v2.id(), List.of("V001"), "bob"));
        ResolutionResponse historyDuringCanary = service.resolve(
                new ResolveRequest("cam_front", "V001", null, null, null));

        service.promoteCanary(release.releaseId(), new OperatorRequest("carol"));

        // 转正后所有车辆解析新版本，旧基线被标记为已替代
        assertThat(resolveVersion("cam_front", "V001")).isEqualTo(v2.id());
        assertThat(resolveVersion("cam_front", "V099")).isEqualTo(v2.id());
        assertThat(jdbc.queryForObject("SELECT status FROM calibration_version WHERE id = ?",
                String.class, v1.id())).isEqualTo("SUPERSEDED");

        // 历史引用不随后续发布改变
        ResolutionHistoryResponse history = service.getHistory(historyDuringCanary.resolutionId());
        assertThat(history.selectedVersionId()).isEqualTo(v2.id());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long resolveVersion(String key, String vehicle) {
        return service.resolve(new ResolveRequest(key, vehicle, null, null, null)).selectedVersionId();
    }

    private CalibrationResponse createAndActivate(String key, String version, Double fx,
                                                  String vehicleFrom, String vehicleTo) {
        boolean extrinsic = fx == null;
        CalibrationResponse v = createApprovedCandidate(key, version, fx, vehicleFrom, vehicleTo, extrinsic);
        return service.activate(v.id(), new OperatorRequest("carol"));
    }

    /** 创建并审批通过（可直接发起灰度或全量生效）。 */
    private CalibrationResponse createApprovedCandidate(String key, String version, Double fx,
                                                        String vehicleFrom, String vehicleTo,
                                                        boolean extrinsic) {
        CalibrationResponse draft = createCandidate(key, version, fx, vehicleFrom, vehicleTo, extrinsic);
        return service.approve(draft.id(), new ApproveRequest("bob"));
    }

    /** 仅创建草稿。 */
    private CalibrationResponse createCandidate(String key, String version, Double fx,
                                                String vehicleFrom, String vehicleTo,
                                                boolean extrinsic) {
        JsonNode content;
        SensorType type;
        ParamKind kind;
        if (extrinsic) {
            content = extrinsicContent();
            type = SensorType.LIDAR;
            kind = ParamKind.EXTRINSIC;
        } else {
            content = mapper.createObjectNode()
                    .put("fx", fx).put("fy", fx - 20.0).put("cx", 640).put("cy", 360);
            type = SensorType.CAMERA;
            kind = ParamKind.INTRINSIC;
        }
        CreateCalibrationRequest req = new CreateCalibrationRequest(key, type, kind, version, content,
                null, null, vehicleFrom, vehicleTo, null, null, null, null, "alice");
        return service.create(req);
    }

    private JsonNode extrinsicContent() {
        try {
            return mapper.readTree("""
                    {"rotation":[[1,0,0],[0,1,0],[0,0,1]],"translation":[0,0,0]}
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CreateCalibrationRequest intrinsicRequest(String key, String version, JsonNode content,
                                                      String vehicleFrom, String vehicleTo) {
        return new CreateCalibrationRequest(key, SensorType.CAMERA, ParamKind.INTRINSIC, version, content,
                null, null, vehicleFrom, vehicleTo, null, null, null, null, "alice");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
