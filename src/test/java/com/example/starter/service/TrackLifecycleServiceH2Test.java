package com.example.starter.service;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.Detection;
import com.example.starter.domain.DetectionOutcome;
import com.example.starter.domain.EvidenceType;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.domain.TrackStatus;
import com.example.starter.domain.VersionConflictException;
import com.example.starter.testsupport.TestDataCleaner;
import com.example.starter.web.dto.AssociateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轨迹状态机 H2（MODE=MySQL）业务集成测试：覆盖建轨/观测、短时遮挡、
 * 目标交叉、ID 切换、重复/乱序/低置信度检测、乐观锁冲突、非法迁移与事件流重放。
 */
@SpringBootTest
class TrackLifecycleServiceH2Test {

    @Autowired
    private TrackLifecycleService service;

    @Autowired
    private TestDataCleaner cleaner;

    @BeforeEach
    void cleanData() {
        cleaner.clean();
    }

    private Detection det(String id, long t, String category, double x, double y, double conf) {
        return new Detection(id, t, "cam-1", category, x, y, 0.0, conf);
    }

    @Test
    void firstDetectionCreatesTrackingTrackAndSecondObserves() {
        AssociateResponse created = service.associate(
                det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);

        assertThat(created.outcome()).isEqualTo(DetectionOutcome.CREATED);
        assertThat(created.trackUid()).isEqualTo("T-000001");
        assertThat(created.track().status()).isEqualTo(TrackStatus.TRACKING);
        // 建轨事件 v1 + 首观测 CAS v2。
        assertThat(created.version()).isEqualTo(2L);
        assertThat(created.track().lastDetectionId()).isEqualTo("d1");

        AssociateResponse observed = service.associate(
                det("d2", 2000L, "car", 1.0, 0, 0.8), null, null, null);
        assertThat(observed.outcome()).isEqualTo(DetectionOutcome.ASSOCIATED);
        assertThat(observed.trackUid()).isEqualTo("T-000001");
        assertThat(observed.version()).isEqualTo(3L);
        assertThat(observed.track().x()).isEqualTo(1.0);
        assertThat(observed.track().confidence()).isEqualTo(0.8);
        assertThat(observed.track().lastObservedAt()).isEqualTo(2000L);
    }

    @Test
    void shortOcclusionThenRedetectionReturnsToTracking() {
        service.associate(det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);
        TrackSnapshot beforeOcclusion = service.getTrack("T-000001");

        TrackSnapshot occluded = service.transition("T-000001",
                beforeOcclusion.version(), "OCCLUDED", 1500L, "目标被立柱短暂遮挡");
        assertThat(occluded.status()).isEqualTo(TrackStatus.OCCLUDED);
        assertThat(occluded.version()).isEqualTo(3L);

        AssociateResponse redetect = service.associate(
                det("d2", 2000L, "car", 0.5, 0, 0.85), null, null, null);
        assertThat(redetect.outcome()).isEqualTo(DetectionOutcome.ASSOCIATED);
        assertThat(redetect.track().status()).isEqualTo(TrackStatus.TRACKING);
        assertThat(redetect.version()).isEqualTo(4L);
        assertThat(redetect.track().lastDetectionId()).isEqualTo("d2");
    }

    @Test
    void crossingDetectionAssociatesNearestAndKeepsBidirectionalEvidence() {
        // A、B 先在相距较远的位置独立建轨。
        service.associate(det("a1", 1000L, "car", 0, 0, 0.9), null, null, null);
        service.associate(det("b1", 2000L, "car", 10, 0, 0.9), null, null, null);

        // 用显式关联把门限放大，把两条轨迹移动到相互接近的位置。
        AssociateResponse a2 = service.associate(
                det("a2", 3000L, "car", 5.0, 0, 0.9), "T-000001", 2L, 10.0);
        AssociateResponse b2 = service.associate(
                det("b2", 4000L, "car", 5.5, 0, 0.9), "T-000002", 2L, 10.0);
        assertThat(a2.trackUid()).isEqualTo("T-000001");
        assertThat(b2.trackUid()).isEqualTo("T-000002");

        // 交叉点检测：距 A 0.1m、距 B 0.4m，默认 2m 门限内同时命中两个候选。
        AssociateResponse crossing = service.associate(
                det("x1", 5000L, "car", 5.1, 0, 0.95), null, null, null);

        assertThat(crossing.outcome()).isEqualTo(DetectionOutcome.ASSOCIATED);
        assertThat(crossing.trackUid()).isEqualTo("T-000001");
        assertThat(crossing.evidence()).hasSize(1);
        assertThat(crossing.evidence().get(0).evidenceType()).isEqualTo(EvidenceType.CROSSING);
        assertThat(crossing.evidence().get(0).linkedTrackUid()).isEqualTo("T-000002");

        List<AssociationEvidence> evidenceOnB = service.evidence("T-000002");
        assertThat(evidenceOnB).anyMatch(e ->
                e.evidenceType() == EvidenceType.CROSSING
                        && "T-000001".equals(e.linkedTrackUid())
                        && "x1".equals(e.detectionId()));

        // 未被选中的 B 轨迹版本、最近观测均不得变化。
        TrackSnapshot bSnapshot = service.getTrack("T-000002");
        assertThat(bSnapshot.version()).isEqualTo(3L);
        assertThat(bSnapshot.lastDetectionId()).isEqualTo("b2");
        assertThat(bSnapshot.lastObservedAt()).isEqualTo(4000L);
    }

    @Test
    void idSwitchRecyclesOldTrackAndCreatesNewIdentityWithEvidence() {
        service.associate(det("d1", 1000L, "person", 0, 0, 0.9), null, null, null);

        AssociateResponse switched = service.idSwitch("T-000001", 2L,
                det("d2", 2000L, "person", 20, 0, 0.92));

        assertThat(switched.outcome()).isEqualTo(DetectionOutcome.CREATED);
        assertThat(switched.trackUid()).isEqualTo("T-000002");
        assertThat(switched.track().status()).isEqualTo(TrackStatus.TRACKING);
        // 新旧身份各留一条 ID_SWITCH 证据。
        assertThat(switched.evidence()).hasSize(2);
        assertThat(switched.evidence()).extracting(AssociationEvidence::trackUid)
                .containsExactly("T-000001", "T-000002");
        assertThat(switched.evidence()).allMatch(e ->
                e.evidenceType() == EvidenceType.ID_SWITCH);

        assertThat(service.getTrack("T-000001").status()).isEqualTo(TrackStatus.RECYCLED);
        // 回收轨迹不再是自动关联候选，远处新检测会再建第三条轨迹。
        AssociateResponse later = service.associate(
                det("d3", 3000L, "person", 20.5, 0, 0.9), null, null, null);
        assertThat(later.outcome()).isEqualTo(DetectionOutcome.ASSOCIATED);
        assertThat(later.trackUid()).isEqualTo("T-000002");

        // 显式向已回收轨迹关联被拒绝。
        AssociateResponse toRecycled = service.associate(
                det("d4", 4000L, "person", 0, 0, 0.9), "T-000001", 3L, 100.0);
        assertThat(toRecycled.outcome()).isEqualTo(DetectionOutcome.REJECTED);
        assertThat(toRecycled.rejectReason()).isEqualTo("ILLEGAL_STATE");

        // 含 ID 切换的完整事件流重放必须与当前态表完全一致。
        assertThat(service.replayAsOf(Long.MAX_VALUE))
                .containsExactlyInAnyOrderElementsOf(service.listTracks());
    }

    @Test
    void duplicateDetectionIsIdempotentAndChangesNothing() {
        AssociateResponse first = service.associate(
                det("dup1", 1000L, "car", 0, 0, 0.9), null, null, null);
        AssociateResponse second = service.associate(
                det("dup1", 1000L, "car", 0, 0, 0.9), null, null, null);

        assertThat(second.outcome()).isEqualTo(DetectionOutcome.DUPLICATE);
        assertThat(second.trackUid()).isEqualTo("T-000001");
        assertThat(second.version()).isEqualTo(first.version());
        assertThat(service.listTracks()).hasSize(1);
        assertThat(service.getTrack("T-000001").version()).isEqualTo(2L);
    }

    @Test
    void outOfOrderDetectionRejectedWithoutRollingBack() {
        service.associate(det("late", 2000L, "car", 0, 0, 0.9), null, null, null);

        AssociateResponse early = service.associate(
                det("early", 1000L, "car", 0, 0, 0.9), null, null, null);
        assertThat(early.outcome()).isEqualTo(DetectionOutcome.REJECTED);
        assertThat(early.rejectReason()).isEqualTo("OUT_OF_ORDER");

        TrackSnapshot track = service.getTrack("T-000001");
        assertThat(track.version()).isEqualTo(2L);
        assertThat(track.lastObservedAt()).isEqualTo(2000L);
        assertThat(track.lastDetectionId()).isEqualTo("late");

        // 被拒绝的检测同样幂等：重复提交得到首次的 REJECTED 结果。
        AssociateResponse earlyAgain = service.associate(
                det("early", 1000L, "car", 0, 0, 0.9), null, null, null);
        assertThat(earlyAgain.outcome()).isEqualTo(DetectionOutcome.DUPLICATE);
        assertThat(earlyAgain.rejectReason()).isEqualTo("OUT_OF_ORDER");
        assertThat(service.listTracks()).hasSize(1);
    }

    @Test
    void lowConfidenceDetectionRejectedAndCreatesNoTrack() {
        AssociateResponse weak = service.associate(
                det("weak", 1000L, "car", 0, 0, 0.49), null, null, null);
        assertThat(weak.outcome()).isEqualTo(DetectionOutcome.REJECTED);
        assertThat(weak.rejectReason()).isEqualTo("LOW_CONFIDENCE");
        assertThat(service.listTracks()).isEmpty();

        AssociateResponse weakAgain = service.associate(
                det("weak", 1000L, "car", 0, 0, 0.49), null, null, null);
        assertThat(weakAgain.outcome()).isEqualTo(DetectionOutcome.DUPLICATE);
    }

    @Test
    void explicitAssociationOutOfGateRejected() {
        service.associate(det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);

        AssociateResponse far = service.associate(
                det("d2", 2000L, "car", 10, 0, 0.9), "T-000001", 2L, 2.0);
        assertThat(far.outcome()).isEqualTo(DetectionOutcome.REJECTED);
        assertThat(far.rejectReason()).isEqualTo("OUT_OF_GATE");
        assertThat(service.getTrack("T-000001").version()).isEqualTo(2L);
    }

    @Test
    void staleExpectedVersionRaisesConflictAndKeepsState() {
        service.associate(det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);

        assertThatThrownBy(() -> service.associate(
                det("d2", 2000L, "car", 0.5, 0, 0.9), "T-000001", 1L, 2.0))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> {
                    VersionConflictException vce = (VersionConflictException) ex;
                    assertThat(vce.getExpectedVersion()).isEqualTo(1L);
                    assertThat(vce.getActualVersion()).isEqualTo(2L);
                });

        // 冲突后用正确版本仍可关联。
        AssociateResponse retry = service.associate(
                det("d3", 3000L, "car", 0.5, 0, 0.9), "T-000001", 2L, 2.0);
        assertThat(retry.outcome()).isEqualTo(DetectionOutcome.ASSOCIATED);
        assertThat(retry.version()).isEqualTo(3L);
    }

    @Test
    void illegalTransitionRejectedAndOutOfOrderTransitionRejected() {
        service.associate(det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);

        // TRACKING -> REIDENTIFIED 不是合法路径。
        assertThatThrownBy(() -> service.transition("T-000001", 2L, "REIDENTIFIED",
                1500L, "非法跳跃"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法状态迁移");

        // 合法地走到 LOST：TRACKING -> OCCLUDED -> LOST。
        service.transition("T-000001", 2L, "OCCLUDED", 1500L, "遮挡");
        TrackSnapshot lost = service.transition("T-000001", 3L, "LOST", 2500L, "超时丢失");
        assertThat(lost.status()).isEqualTo(TrackStatus.LOST);

        // 迟到的迁移事件时间不得回退。
        assertThatThrownBy(() -> service.transition("T-000001", 4L, "RECYCLED",
                2000L, "迟到回收"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("早于轨迹最近变化时间");

        // LOST -> REIDENTIFIED 需经观测驱动；显式合法迁移 LOST -> RECYCLED。
        TrackSnapshot recycled = service.transition("T-000001", 4L, "RECYCLED",
                3000L, "确定离开场景");
        assertThat(recycled.status()).isEqualTo(TrackStatus.RECYCLED);
    }

    @Test
    void lostTrackRedetectionMarksReidentifiedThenResumesTracking() {
        service.associate(det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);
        service.transition("T-000001", 2L, "OCCLUDED", 1500L, "遮挡");
        service.transition("T-000001", 3L, "LOST", 2500L, "丢失");

        AssociateResponse reidentified = service.associate(
                det("d2", 3000L, "car", 0.8, 0, 0.95), null, null, null);
        assertThat(reidentified.outcome()).isEqualTo(DetectionOutcome.ASSOCIATED);
        assertThat(reidentified.track().status()).isEqualTo(TrackStatus.REIDENTIFIED);
        assertThat(reidentified.version()).isEqualTo(5L);
        assertThat(reidentified.evidence().get(0).evidenceType())
                .isEqualTo(EvidenceType.REIDENTIFICATION);

        // 再获一次观测后合法回到 TRACKING。
        AssociateResponse resumed = service.associate(
                det("d3", 4000L, "car", 1.0, 0, 0.9), null, null, null);
        assertThat(resumed.track().status()).isEqualTo(TrackStatus.TRACKING);
        assertThat(resumed.version()).isEqualTo(6L);
    }

    @Test
    void replayRebuildsSnapshotsAtAnyPointAndMatchesLiveState() {
        service.associate(det("a1", 1000L, "car", 0, 0, 0.9), null, null, null);
        service.associate(det("a2", 2000L, "car", 1.0, 0, 0.8), null, null, null);
        service.transition("T-000001", 3L, "OCCLUDED", 2500L, "遮挡");
        service.associate(det("b1", 3000L, "car", 10, 0, 0.9), null, null, null);

        // t=1500：A 已建轨并处于 TRACKING/v2，B 尚不存在。
        List<TrackSnapshot> at1500 = service.replayAsOf(1500L);
        assertThat(at1500).hasSize(1);
        TrackSnapshot a1500 = at1500.get(0);
        assertThat(a1500.trackUid()).isEqualTo("T-000001");
        assertThat(a1500.status()).isEqualTo(TrackStatus.TRACKING);
        assertThat(a1500.version()).isEqualTo(2L);
        assertThat(a1500.lastObservedAt()).isEqualTo(1000L);

        // t=2500：A 已进入 OCCLUDED/v4（建轨 v1、两次观测 v2/v3、遮挡迁移 v4），最近观测仍是 a2@2000。
        List<TrackSnapshot> at2500 = service.replayAsOf(2500L);
        TrackSnapshot a2500 = byUid(at2500, "T-000001");
        assertThat(a2500.status()).isEqualTo(TrackStatus.OCCLUDED);
        assertThat(a2500.version()).isEqualTo(4L);
        assertThat(a2500.lastDetectionId()).isEqualTo("a2");
        assertThat(at2500).noneMatch(s -> "T-000002".equals(s.trackUid()));

        // t=3000：B 已建轨。
        List<TrackSnapshot> at3000 = service.replayAsOf(3000L);
        assertThat(at3000).hasSize(2);
        assertThat(byUid(at3000, "T-000002").status()).isEqualTo(TrackStatus.TRACKING);

        // 全量重放必须与当前态表完全一致（状态/版本/最近观测/位置/置信度）。
        List<TrackSnapshot> full = service.replayAsOf(Long.MAX_VALUE);
        assertThat(full).containsExactlyInAnyOrderElementsOf(service.listTracks());
    }

    @Test
    void replayIsDeterministicAcrossRepeatedReads() {
        service.associate(det("d1", 1000L, "car", 0, 0, 0.9), null, null, null);
        service.associate(det("d2", 2000L, "car", 1.0, 0, 0.8), null, null, null);
        service.transition("T-000001", 3L, "LOST", 2500L, "遮挡超时");

        List<TrackSnapshot> first = service.replayAsOf(Long.MAX_VALUE);
        List<TrackSnapshot> second = service.replayAsOf(Long.MAX_VALUE);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void reprocessingSameDetectionSetOnCleanStoreYieldsIdenticalTracksAndVersions() {
        List<TrackSnapshot> firstRun = runScenario();
        // 清空库与序号后，按完全相同的顺序重放同一检测集合。
        cleaner.clean();
        List<TrackSnapshot> secondRun = runScenario();
        // 轨迹号、状态、版本、最近观测、置信度与位置必须逐项一致。
        assertThat(secondRun).isEqualTo(firstRun);
        assertThat(secondRun).extracting(TrackSnapshot::trackUid)
                .containsExactly("T-000001", "T-000002");
    }

    private List<TrackSnapshot> runScenario() {
        service.associate(det("s1", 1000L, "car", 0, 0, 0.9), null, null, null);
        service.associate(det("s2", 2000L, "car", 1.0, 0, 0.85), null, null, null);
        service.associate(det("s3", 3000L, "car", 9.0, 0, 0.9), null, null, null);
        service.transition("T-000001", 3L, "OCCLUDED", 3500L, "遮挡");
        service.associate(det("s4", 4000L, "car", 1.2, 0, 0.8), null, null, null);
        return service.listTracks();
    }

    private TrackSnapshot byUid(List<TrackSnapshot> snapshots, String uid) {
        return snapshots.stream()
                .filter(s -> uid.equals(s.trackUid()))
                .findFirst()
                .orElseThrow();
    }
}
