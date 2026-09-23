package com.example.starter.calibration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.ResolutionRecord;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.domain.payload.CalibrationPayload;
import com.example.starter.calibration.testsupport.AbstractCalibrationIntegrationTest;
import com.example.starter.calibration.testsupport.CalibrationDsl;
import com.example.starter.calibration.web.ApiException;
import com.example.starter.calibration.web.dto.CreateVersionRequest;

/**
 * 标定仓库 H2 集成测试：校验拒绝、范围冲突、原子灰度、未参与车辆隔离、回滚恢复、历史不可变。
 */
@SpringBootTest
@ActiveProfiles("test")
class CalibrationServiceIntegrationTest extends AbstractCalibrationIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private void createApproveActivate(String version) {
        service.createVersion(CalibrationDsl.allVehiclesVersion(version, FROM, null));
        service.approve(CalibrationDsl.KEY, version, "reviewer");
        service.activate(CalibrationDsl.KEY, version, "release-bot");
    }

    private void createApprove(String version) {
        service.createVersion(CalibrationDsl.allVehiclesVersion(version, FROM, null));
        service.approve(CalibrationDsl.KEY, version, "reviewer");
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void createApproveActivateAndResolveMainFlow() {
        createApproveActivate("v1");

        var resolved = service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW));
        assertThat(resolved.getSelectedVersion()).isEqualTo("v1");
        assertThat(resolved.getResolutionId()).isNotBlank();
        assertThat(resolved.getCanaryReleaseId()).isNull();

        var stored = service.getVersion(CalibrationDsl.KEY, "v1");
        assertThat(stored.getStatus()).isEqualTo(CalibrationStatus.EFFECTIVE);
        assertThat(stored.getApprovedBy()).isEqualTo("reviewer");
        assertThat(stored.getActivatedBy()).isEqualTo("release-bot");
        assertThat(stored.getApprovedAt()).isEqualTo(FIXED_NOW);
        assertThat(stored.getActivatedAt()).isEqualTo(FIXED_NOW);
        assertThat(stored.getContentDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void resolveOutsideEffectiveWindowFails() {
        createApproveActivate("v1");
        Instant before = Instant.parse("2025-12-31T23:59:59Z");
        assertThatThrownBy(() -> service.resolve(CalibrationDsl.resolve("car-a", null, before)))
                .isInstanceOf(ApiException.NotFound.class);
    }

    // ------------------------------------------------------------------
    // 错误标定拒绝：422 且不存在可审批版本
    // ------------------------------------------------------------------

    @Test
    void invalidCameraCalibrationIsRejectedAndNotPersisted() {
        CalibrationPayload bad = CalibrationDsl.cameraPayload(0.0);
        CreateVersionRequest request = new CreateVersionRequest(
                CalibrationDsl.KEY, SensorType.CAMERA, "bad", bad,
                OffsetDateTime.ofInstant(FROM, ZoneOffset.UTC), null,
                true, List.of(), CalibrationDsl.SENSOR, null, null, "tester");

        assertThatThrownBy(() -> service.createVersion(request))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("焦距");
        assertThat(count("calibration_version")).isZero();
    }

    @Test
    void nonOrthogonalExtrinsicsRejectedWith422() {
        CalibrationPayload bad = new CalibrationPayload(
                CalibrationDsl.cameraPayload(1000).cameraIntrinsics(),
                new com.example.starter.calibration.domain.payload.Extrinsics(
                        List.of(List.of(1.0, 0.5, 0.0), List.of(0.0, 1.0, 0.0), List.of(0.0, 0.0, 1.0)),
                        List.of(0.0, 0.0, 0.0)),
                null);
        CreateVersionRequest request = new CreateVersionRequest(
                CalibrationDsl.KEY, SensorType.CAMERA, "bad-rotation", bad,
                OffsetDateTime.ofInstant(FROM, ZoneOffset.UTC), null,
                true, List.of(), CalibrationDsl.SENSOR, null, null, "tester");

        assertThatThrownBy(() -> service.createVersion(request))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("正交性");
        assertThat(count("calibration_version")).isZero();
    }

    // ------------------------------------------------------------------
    // 生效范围冲突：完全相同范围/时间不允许两个有效版本
    // ------------------------------------------------------------------

    @Test
    void overlappingEffectiveRangeRejected() {
        createApproveActivate("v1");
        createApprove("v2");

        // 审批本身允许（之后可灰度），全量生效时才与已生效版本冲突
        assertThat(service.getVersion(CalibrationDsl.KEY, "v2").getStatus())
                .isEqualTo(CalibrationStatus.APPROVED);

        assertThatThrownBy(() -> service.activate(CalibrationDsl.KEY, "v2", "release-bot"))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("生效范围冲突");

        // v2 未被置为 EFFECTIVE，仍是 APPROVED
        assertThat(service.getVersion(CalibrationDsl.KEY, "v2").getStatus())
                .isEqualTo(CalibrationStatus.APPROVED);
        assertThat(service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
    }

    @Test
    void nonOverlappingTimeRangesCanBothBeEffective() {
        service.createVersion(CalibrationDsl.allVehiclesVersion(
                "old", Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")));
        service.approve(CalibrationDsl.KEY, "old", "reviewer");
        service.activate(CalibrationDsl.KEY, "old", "release-bot");

        createApproveActivate("v1");

        assertThat(service.resolve(CalibrationDsl.resolve(
                "car-a", null, Instant.parse("2025-06-01T00:00:00Z")))
                .getSelectedVersion()).isEqualTo("old");
        assertThat(service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
    }

    // ------------------------------------------------------------------
    // 灰度发布：参与车辆切换，未参与车辆继续解析旧版本
    // ------------------------------------------------------------------

    @Test
    void canarySwitchesOnlySpecifiedVehicles() {
        createApproveActivate("v1");
        createApprove("v2");

        var context = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a", "car-b"), "ops"));
        long releaseId = context.release().getId();

        assertThat(service.getVersion(CalibrationDsl.KEY, "v2").getStatus())
                .isEqualTo(CalibrationStatus.IN_CANARY);
        assertThat(context.vehicles()).extracting(CanaryVehicle::getVehicleId)
                .containsExactly("car-a", "car-b");
        assertThat(context.vehicles()).extracting(CanaryVehicle::getPreviousVersionId)
                .doesNotContainNull();

        ResolutionRecord canaryHit =
                service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW));
        assertThat(canaryHit.getSelectedVersion()).isEqualTo("v2");
        assertThat(canaryHit.getCanaryReleaseId()).isEqualTo(releaseId);

        // 未参与灰度的车辆继续解析旧版本
        ResolutionRecord unaffected =
                service.resolve(CalibrationDsl.resolve("car-c", null, FIXED_NOW));
        assertThat(unaffected.getSelectedVersion()).isEqualTo("v1");
        assertThat(unaffected.getCanaryReleaseId()).isNull();
    }

    @Test
    void disjointCanaryFleetsCanCoexist() {
        createApproveActivate("v1");
        createApprove("v2");
        createApprove("v3");

        service.canaryRelease(CalibrationDsl.canary("v2", List.of("car-a"), "ops"));
        service.canaryRelease(CalibrationDsl.canary("v3", List.of("car-b"), "ops"));

        assertThat(service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v2");
        assertThat(service.resolve(CalibrationDsl.resolve("car-b", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v3");
        assertThat(service.resolve(CalibrationDsl.resolve("car-c", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
    }

    // ------------------------------------------------------------------
    // 灰度部分失败：任一车辆冲突/验证失败，整批原子回滚
    // ------------------------------------------------------------------

    @Test
    void canaryWithConflictingVehicleRollsBackEntireBatch() {
        createApproveActivate("v1");
        createApprove("v2");
        createApprove("v3");

        var first = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a", "car-b"), "ops"));

        long releasesBefore = count("canary_release");
        long vehiclesBefore = count("canary_vehicle");
        long activeBefore = count("active_canary_vehicle");

        // v3 批次包含已在灰度中的 car-b：冲突，整批回滚
        assertThatThrownBy(() -> service.canaryRelease(
                CalibrationDsl.canary("v3", List.of("car-b", "car-c"), "ops")))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("全部回滚");

        // 发布单、明细、活跃占用均因事务回滚而未落库
        assertThat(count("canary_release")).isEqualTo(releasesBefore);
        assertThat(count("canary_vehicle")).isEqualTo(vehiclesBefore);
        assertThat(count("active_canary_vehicle")).isEqualTo(activeBefore);

        // v3 未进入灰度中；car-c 仍解析 v1，car-b 仍属于第一次灰度的 v2
        assertThat(service.getVersion(CalibrationDsl.KEY, "v3").getStatus())
                .isEqualTo(CalibrationStatus.APPROVED);
        assertThat(service.resolve(CalibrationDsl.resolve("car-c", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
        assertThat(service.resolve(CalibrationDsl.resolve("car-b", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v2");
        // 第一次发布不受影响
        assertThat(service.getRelease(first.release().getId()).getStatus().name())
                .isEqualTo("ROLLED_OUT");
    }

    @Test
    void canaryRejectsVehicleOutsideDeclaredScopeAtomically() {
        createApproveActivate("v1");
        service.createVersion(CalibrationDsl.vehiclesVersion(
                "v2", List.of("car-a"), FROM, null));
        service.approve(CalibrationDsl.KEY, "v2", "reviewer");

        assertThatThrownBy(() -> service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a", "car-c"), "ops")))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("车辆范围");

        assertThat(count("canary_release")).isZero();
        assertThat(count("active_canary_vehicle")).isZero();
        assertThat(service.resolve(CalibrationDsl.resolve("car-c", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
    }

    // ------------------------------------------------------------------
    // 并发：两个灰度批次同时争夺同一车辆，必须恰好一个成功
    // ------------------------------------------------------------------

    @Test
    void concurrentCanaryReleasesOnSameVehicleAreSerializedByDatabase() throws Exception {
        createApproveActivate("v1");
        createApprove("v2");
        createApprove("v3");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                service.canaryRelease(CalibrationDsl.canary("v2", List.of("car-a", "car-b"), "ops1"));
            });
            Future<?> second = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                service.canaryRelease(CalibrationDsl.canary("v3", List.of("car-b", "car-c"), "ops2"));
            });
            start.countDown();

            int failures = 0;
            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    failures++;
                    assertThat(ex.getCause()).isInstanceOf(ApiException.Conflict.class);
                }
            }
            assertThat(failures).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 只有一个发布单落库，且只占用它自己的两辆车
        assertThat(count("canary_release")).isEqualTo(1);
        assertThat(count("active_canary_vehicle")).isEqualTo(2);
        // car-b 只可能解析到 v2 或 v3 中的一个，两个灰度版本不能同时对它有效
        String carBVersion = service.resolve(CalibrationDsl.resolve("car-b", null, FIXED_NOW))
                .getSelectedVersion();
        assertThat(carBVersion).isIn("v2", "v3");
        // 落败版本保持 APPROVED，未被错误置为 IN_CANARY
        String winner = carBVersion;
        String loser = "v2".equals(winner) ? "v3" : "v2";
        assertThat(service.getVersion(CalibrationDsl.KEY, loser).getStatus())
                .isEqualTo(CalibrationStatus.APPROVED);
    }

    // ------------------------------------------------------------------
    // 回滚：仅影响该次灰度车辆，并恢复各自发布前版本
    // ------------------------------------------------------------------

    @Test
    void rollbackRestoresPerVehiclePreviousVersions() {
        createApproveActivate("v1");
        createApprove("v2");
        var context = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a", "car-b"), "ops"));
        long releaseId = context.release().getId();

        // 灰度期间记录 car-a 命中 v2 的历史
        ResolutionRecord duringCanary =
                service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW));
        assertThat(duringCanary.getSelectedVersion()).isEqualTo("v2");

        service.rollback(releaseId, "sre-oncall", "融合抖动超阈值");

        assertThat(service.getRelease(releaseId).getStatus().name()).isEqualTo("ROLLED_BACK");
        assertThat(service.getRelease(releaseId).getRollbackOperator()).isEqualTo("sre-oncall");
        assertThat(service.getRelease(releaseId).getReason()).isEqualTo("融合抖动超阈值");
        assertThat(service.getRelease(releaseId).getRolledBackAt()).isEqualTo(FIXED_NOW);
        assertThat(service.getVersion(CalibrationDsl.KEY, "v2").getStatus())
                .isEqualTo(CalibrationStatus.ROLLED_BACK);
        assertThat(count("active_canary_vehicle")).isZero();

        // 灰度车辆恢复发布前版本
        assertThat(service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
        assertThat(service.resolve(CalibrationDsl.resolve("car-b", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
        // 未参与车辆本来就是 v1
        assertThat(service.resolve(CalibrationDsl.resolve("car-c", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");

        // 历史 resolutionId 仍然指向当时的 v2 快照，回滚与后续发布不得改写
        ResolutionRecord historical = service.getResolution(duringCanary.getResolutionId());
        assertThat(historical.getSelectedVersion()).isEqualTo("v2");
        assertThat(historical.getPayload()).isEqualTo(duringCanary.getPayload());
        assertThat(historical.getContentDigest()).isEqualTo(duringCanary.getContentDigest());
        assertThat(historical.getCanaryReleaseId()).isEqualTo(releaseId);
    }

    @Test
    void rollbackOnlyAffectsOwnCanaryVehicles() {
        createApproveActivate("v1");
        createApprove("v2");
        createApprove("v3");

        var releaseA = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a"), "ops"));
        service.canaryRelease(CalibrationDsl.canary("v3", List.of("car-b"), "ops"));

        service.rollback(releaseA.release().getId(), "sre-oncall", "车辆 a 异常");

        assertThat(service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v1");
        // car-b 属于另一次灰度，不受本次回滚影响
        assertThat(service.resolve(CalibrationDsl.resolve("car-b", null, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("v3");
        assertThat(count("active_canary_vehicle")).isEqualTo(1);
    }

    @Test
    void historyResolutionRemainsImmutableAfterLaterReleases() {
        // v1 窗口在 v4 起始时结束（左闭右开），二者可先后全量生效
        service.createVersion(CalibrationDsl.version(
                "v1", true, List.of(), FROM, Instant.parse("2026-06-02T00:00:00Z"), null, null));
        service.approve(CalibrationDsl.KEY, "v1", "reviewer");
        service.activate(CalibrationDsl.KEY, "v1", "release-bot");
        ResolutionRecord oldResolution =
                service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW));
        String oldId = oldResolution.getResolutionId();
        String oldPayload = oldResolution.getPayload();

        // 新版本灰度、再回滚、再全量上线 v4，期间产生多次发布
        createApprove("v2");
        var release = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a"), "ops"));
        service.rollback(release.release().getId(), "sre", null);

        service.createVersion(CalibrationDsl.allVehiclesVersion(
                "v4", Instant.parse("2026-06-02T00:00:00Z"), null));
        service.approve(CalibrationDsl.KEY, "v4", "reviewer");
        service.activate(CalibrationDsl.KEY, "v4", "release-bot");

        ResolutionRecord historical = service.getResolution(oldId);
        assertThat(historical.getSelectedVersion()).isEqualTo("v1");
        assertThat(historical.getPayload()).isEqualTo(oldPayload);
        // 当前实时解析已变为 v4，但历史引用不变
        assertThat(service.resolve(CalibrationDsl.resolve(
                "car-a", null, Instant.parse("2026-06-03T00:00:00Z")))
                .getSelectedVersion()).isEqualTo("v4");
    }

    @Test
    void rollbackWhenNoBaselineLeavesVehicleWithoutVersion() {
        // v2 仅对显式车辆发布灰度，此前没有任何全量版本
        service.createVersion(CalibrationDsl.vehiclesVersion(
                "v2", List.of("car-a"), FROM, null));
        service.approve(CalibrationDsl.KEY, "v2", "reviewer");
        var release = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a"), "ops"));
        assertThat(release.vehicles().get(0).getPreviousVersionId()).isNull();

        service.rollback(release.release().getId(), "sre", null);
        assertThatThrownBy(() -> service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW)))
                .isInstanceOf(ApiException.NotFound.class);
    }

    @Test
    void duplicateCanaryOfSameVersionIsRejected() {
        createApproveActivate("v1");
        createApprove("v2");

        service.canaryRelease(CalibrationDsl.canary("v2", List.of("car-a"), "ops"));
        // v2 已在灰度中，再次发起必须拒绝
        assertThatThrownBy(() -> service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-c"), "ops")))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("已批准");
        assertThat(count("canary_release")).isEqualTo(1);
        assertThat(count("active_canary_vehicle")).isEqualTo(1);
    }

    @Test
    void doubleRollbackIsRejectedAndKeepsState() {
        createApproveActivate("v1");
        createApprove("v2");
        var release = service.canaryRelease(
                CalibrationDsl.canary("v2", List.of("car-a"), "ops"));
        long releaseId = release.release().getId();

        service.rollback(releaseId, "sre", "first");
        assertThatThrownBy(() -> service.rollback(releaseId, "sre", "second"))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("ROLLED_BACK");
        // 原因未被第二次调用改写
        assertThat(service.getRelease(releaseId).getReason()).isEqualTo("first");
    }

    @Test
    void draftCannotBeApprovedTwiceOrActivatedDirectly() {
        service.createVersion(CalibrationDsl.allVehiclesVersion("v1", FROM, null));
        // 草稿不能直接全量生效
        assertThatThrownBy(() -> service.activate(CalibrationDsl.KEY, "v1", "release-bot"))
                .isInstanceOf(ApiException.Unprocessable.class);

        service.approve(CalibrationDsl.KEY, "v1", "reviewer");
        // 已批准版本不能重复审批
        assertThatThrownBy(() -> service.approve(CalibrationDsl.KEY, "v1", "reviewer-2"))
                .isInstanceOf(ApiException.Unprocessable.class);
    }

    @Test
    void resolvesByMapVersionRanges() {
        // 旧地图版本使用 old（地图 1..5），新版本不设地图边界
        service.createVersion(CalibrationDsl.version(
                "old", true, List.of(), FROM, null, 1, 5));
        service.approve(CalibrationDsl.KEY, "old", "reviewer");
        service.activate(CalibrationDsl.KEY, "old", "release-bot");

        service.createVersion(CalibrationDsl.version(
                "cur", true, List.of(), FROM, null, 6, null));
        service.approve(CalibrationDsl.KEY, "cur", "reviewer");
        service.activate(CalibrationDsl.KEY, "cur", "release-bot");

        assertThat(service.resolve(CalibrationDsl.resolve("car-a", 5, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("old");
        assertThat(service.resolve(CalibrationDsl.resolve("car-a", 6, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("cur");
        assertThat(service.resolve(CalibrationDsl.resolve("car-a", 100, FIXED_NOW))
                .getSelectedVersion()).isEqualTo("cur");
        // 不区分地图版本时，设置了地图边界的版本均不命中
        assertThatThrownBy(() -> service.resolve(CalibrationDsl.resolve("car-a", null, FIXED_NOW)))
                .isInstanceOf(ApiException.NotFound.class);
    }

    @Test
    void duplicateKeyVersionPairIsRejected() {
        service.createVersion(CalibrationDsl.allVehiclesVersion("v1", FROM, null));
        assertThatThrownBy(() -> service.createVersion(
                CalibrationDsl.allVehiclesVersion("v1", FROM, null)))
                .isInstanceOf(ApiException.Conflict.class);
    }
}
