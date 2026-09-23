package com.example.starter.scene;

import com.example.starter.scene.dto.ApplyRequest;
import com.example.starter.scene.dto.ApplyResponse;
import com.example.starter.scene.dto.AttachOp;
import com.example.starter.scene.dto.CollisionObjectBody;
import com.example.starter.scene.dto.JointUpdate;
import com.example.starter.scene.dto.ObjectOp;
import com.example.starter.scene.dto.Pose;
import com.example.starter.scene.dto.Size3;
import com.example.starter.scene.dto.SnapshotView;
import com.example.starter.scene.exception.RequestConflictException;
import com.example.starter.scene.exception.SceneValidationException;
import com.example.starter.scene.exception.VersionConflictException;
import com.example.starter.scene.service.SceneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
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
 * Planning Scene 版本服务核心业务测试，使用嵌入式 H2（MODE=MySQL）验证真实数据库边界。
 */
@SpringBootTest
class SceneServiceH2Test {

    @Autowired
    private SceneService sceneService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetScene() {
        jdbc.update("DELETE FROM scene_event");
        jdbc.update("DELETE FROM scene_snapshot");
        jdbc.update("DELETE FROM applied_request");
        jdbc.update("DELETE FROM world_object");
        jdbc.update("DELETE FROM attached_object");
        jdbc.update("UPDATE robot_joint SET position = 0.0");
        jdbc.update("UPDATE scene_meta SET version = 0 WHERE id = 0");
        sceneService.initializeBaselineSnapshot();
    }

    @Test
    void baselineSnapshotForVersionZeroExists() {
        SnapshotView snapshot = sceneService.snapshotAt(0L);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.joints()).extracting(SnapshotView.JointState::name)
                .containsExactly("joint_1", "joint_2", "joint_3");
        assertThat(snapshot.worldObjects()).isEmpty();
        assertThat(snapshot.attachedObjects()).isEmpty();
        assertThat(sceneService.currentSnapshot().version()).isZero();
    }

    @Test
    void successfulBatchCreatesVersionOneAndSnapshot() {
        ApplyRequest request = new ApplyRequest("req-1", 0L,
                List.of(new JointUpdate("joint_1", 0.5)),
                List.of(addBox("box_a", "world", 1.0, 0.0, 0.0)),
                List.of());

        ApplyResponse response = sceneService.apply(request);

        assertThat(response.version()).isEqualTo(1L);
        assertThat(response.replayed()).isFalse();
        SnapshotView current = sceneService.currentSnapshot();
        assertThat(current.version()).isEqualTo(1L);
        assertThat(current.worldObjects()).hasSize(1);
        assertThat(current.worldObjects().get(0).id()).isEqualTo("box_a");
        assertThat(current.worldObjects().get(0).frameId()).isEqualTo("world");
        assertThat(current.joints()).filteredOn(j -> j.name().equals("joint_1"))
                .singleElement()
                .extracting(SnapshotView.JointState::position)
                .isEqualTo(0.5);

        SnapshotView historical = sceneService.snapshotAt(0L);
        assertThat(historical.worldObjects()).isEmpty();
        assertThat(historical.joints()).filteredOn(j -> j.name().equals("joint_1"))
                .singleElement()
                .extracting(SnapshotView.JointState::position)
                .isEqualTo(0.0);
    }

    @Test
    void sameRequestIdAndSameParametersReplaysOriginalResult() {
        ApplyRequest request = new ApplyRequest("req-idem", 0L,
                List.of(), List.of(addBox("box_idem", "world", 0.2, 0.0, 0.0)), List.of());
        ApplyResponse first = sceneService.apply(request);

        ApplyResponse replay = sceneService.apply(request);

        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scene_meta WHERE version = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM applied_request", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParametersIsRejected() {
        ApplyRequest first = new ApplyRequest("req-conflict", 0L,
                List.of(), List.of(addBox("box_x", "world", 0.1, 0.0, 0.0)), List.of());
        sceneService.apply(first);

        ApplyRequest different = new ApplyRequest("req-conflict", 1L,
                List.of(), List.of(addBox("box_y", "world", 0.1, 0.0, 0.0)), List.of());
        assertThatThrownBy(() -> sceneService.apply(different))
                .isInstanceOf(RequestConflictException.class)
                .hasMessageContaining("req-conflict");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object", Integer.class)).isEqualTo(1);
    }

    @Test
    void staleExpectedVersionReturnsCurrentVersionAndChangedIds() {
        ApplyRequest first = new ApplyRequest("req-v1", 0L,
                List.of(new JointUpdate("joint_2", 0.25)),
                List.of(addBox("box_v1", "world", 0.3, 0.0, 0.0)),
                List.of());
        sceneService.apply(first);

        ApplyRequest stale = new ApplyRequest("req-stale", 0L,
                List.of(), List.of(addBox("box_stale", "world", 0.9, 0.0, 0.0)), List.of());
        assertThatThrownBy(() -> sceneService.apply(stale))
                .isInstanceOfSatisfying(VersionConflictException.class, ex -> {
                    assertThat(ex.getCurrentVersion()).isEqualTo(1L);
                    assertThat(ex.getChangedObjects()).containsExactly("box_v1");
                    assertThat(ex.getChangedJoints()).containsExactly("joint_2");
                });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidBatchLeavesZeroWritesAndUnchangedVersion() {
        sceneService.apply(new ApplyRequest("req-good", 0L, List.of(),
                List.of(addBox("box_good", "world", 0.1, 0.0, 0.0)), List.of()));

        ApplyRequest bad = new ApplyRequest("req-bad", 1L,
                List.of(new JointUpdate("joint_2", 5.0)),
                List.of(addBox("box_bad", "world", 0.2, 0.0, 0.0)),
                List.of());
        assertThatThrownBy(() -> sceneService.apply(bad))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("out of range");

        assertThat(currentVersion()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object WHERE id = 'box_bad'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scene_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM applied_request", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsNonFiniteValuesMissingReferencesAndDuplicates() {
        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-nf", 0L, List.of(),
                List.of(addBox("box_nf", "world", Double.NaN, 0.0, 0.0)), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("pose");

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-size", 0L, List.of(),
                List.of(new ObjectOp(ObjectOp.ADD, null, null, new CollisionObjectBody("box_size", "world",
                        new Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                        new Size3(0.1, -1.0, 0.1)))), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("size");

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-frame", 0L, List.of(),
                List.of(addBox("box_frame", "missing_frame", 0.1, 0.0, 0.0)), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("frame");

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-joint", 0L,
                List.of(new JointUpdate("joint_missing", 0.1)), List.of(), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("joint");

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-nonfinite-joint", 0L,
                List.of(new JointUpdate("joint_1", Double.POSITIVE_INFINITY)), List.of(), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("finite");

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-dup-joint", 0L,
                List.of(new JointUpdate("joint_1", 0.1), new JointUpdate("joint_1", 0.2)),
                List.of(), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("multiple times");

        assertThat(currentVersion()).isZero();
    }

    @Test
    void duplicateObjectIdAcrossObjectOpsRejectsWholeBatch() {
        ApplyRequest request = new ApplyRequest("req-dup-obj", 0L, List.of(),
                List.of(addBox("box_dup", "world", 0.1, 0.0, 0.0),
                        addBox("box_dup", "world", 0.2, 0.0, 0.0)),
                List.of());
        assertThatThrownBy(() -> sceneService.apply(request))
                .isInstanceOf(SceneValidationException.class);
        assertThat(currentVersion()).isZero();
    }

    @Test
    void renameMaintainsObjectContentAndAttachReference() {
        sceneService.apply(new ApplyRequest("req-add", 0L, List.of(),
                List.of(addBox("tool_box", "world", 0.4, 0.0, 0.0)), List.of()));
        sceneService.apply(new ApplyRequest("req-attach", 1L, List.of(), List.of(),
                List.of(new AttachOp(AttachOp.ATTACH, "tool_box", "tool0", null))));

        ApplyResponse renamed = sceneService.apply(new ApplyRequest("req-rename", 2L, List.of(),
                List.of(new ObjectOp(ObjectOp.RENAME, "tool_box", "tool_box_v2", null)), List.of()));
        assertThat(renamed.version()).isEqualTo(3L);

        SnapshotView snapshot = sceneService.currentSnapshot();
        assertThat(snapshot.attachedObjects()).hasSize(1);
        assertThat(snapshot.attachedObjects().get(0).id()).isEqualTo("tool_box_v2");
        assertThat(snapshot.attachedObjects().get(0).linkName()).isEqualTo("tool0");
        assertThat(snapshot.worldObjects()).isEmpty();

        VersionConflictException conflict = expectConflict(new ApplyRequest("req-rename-stale", 2L,
                List.of(), List.of(), List.of()));
        assertThat(conflict.getChangedObjects()).containsExactly("tool_box", "tool_box_v2");
    }

    @Test
    void renameToExistingIdIsRejected() {
        sceneService.apply(new ApplyRequest("req-two", 0L, List.of(),
                List.of(addBox("box_one", "world", 0.1, 0.0, 0.0),
                        addBox("box_two", "world", 0.2, 0.0, 0.0)),
                List.of()));
        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-rename-bad", 1L, List.of(),
                List.of(new ObjectOp(ObjectOp.RENAME, "box_one", "box_two", null)), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("already exists");
        assertThat(currentVersion()).isEqualTo(1L);
    }

    @Test
    void attachAndDetachSwitchObjectBetweenSets() {
        sceneService.apply(new ApplyRequest("req-add", 0L, List.of(),
                List.of(addBox("gripper_cup", "world", 0.1, 0.2, 0.3)), List.of()));

        sceneService.apply(new ApplyRequest("req-attach", 1L, List.of(), List.of(),
                List.of(new AttachOp(AttachOp.ATTACH, "gripper_cup", "tool0", null))));
        SnapshotView attached = sceneService.currentSnapshot();
        assertThat(attached.attachedObjects()).extracting(SnapshotView.AttachedObjectView::id)
                .containsExactly("gripper_cup");
        assertThat(attached.worldObjects()).isEmpty();

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-attach-again", 2L,
                List.of(), List.of(),
                List.of(new AttachOp(AttachOp.ATTACH, "gripper_cup", "tool0", null)))))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("already attached");

        sceneService.apply(new ApplyRequest("req-add-2", 2L, List.of(),
                List.of(addBox("spare_cup", "world", 0.5, 0.0, 0.0)), List.of()));
        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-attach-bad-link", 3L,
                List.of(), List.of(),
                List.of(new AttachOp(AttachOp.ATTACH, "spare_cup", "no_such_link", null)))))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("link");

        sceneService.apply(new ApplyRequest("req-detach", 3L, List.of(), List.of(),
                List.of(new AttachOp(AttachOp.DETACH, "gripper_cup", null, "base_link"))));
        SnapshotView detached = sceneService.currentSnapshot();
        assertThat(detached.worldObjects()).extracting(SnapshotView.WorldObjectView::id)
                .containsExactlyInAnyOrder("gripper_cup", "spare_cup");
        assertThat(detached.worldObjects().stream()
                .filter(o -> o.id().equals("gripper_cup")).findFirst().orElseThrow().frameId())
                .isEqualTo("base_link");
        assertThat(detached.attachedObjects()).isEmpty();

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-detach-again", 4L,
                List.of(), List.of(),
                List.of(new AttachOp(AttachOp.DETACH, "gripper_cup", null, "world")))))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("not attached");
    }

    @Test
    void deletingAttachedObjectClearsReference() {
        sceneService.apply(new ApplyRequest("req-add", 0L, List.of(),
                List.of(addBox("temp_cup", "world", 0.1, 0.0, 0.0)), List.of()));
        sceneService.apply(new ApplyRequest("req-attach", 1L, List.of(), List.of(),
                List.of(new AttachOp(AttachOp.ATTACH, "temp_cup", "tool0", null))));

        sceneService.apply(new ApplyRequest("req-delete", 2L, List.of(),
                List.of(new ObjectOp(ObjectOp.DELETE, "temp_cup", null, null)), List.of()));

        SnapshotView snapshot = sceneService.currentSnapshot();
        assertThat(snapshot.worldObjects()).isEmpty();
        assertThat(snapshot.attachedObjects()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attached_object", Integer.class)).isZero();
    }

    @Test
    void objectCannotSimultaneouslyOccupyWorldAndAttachedSets() {
        sceneService.apply(new ApplyRequest("req-add", 0L, List.of(),
                List.of(addBox("single_box", "world", 0.1, 0.0, 0.0)), List.of()));
        sceneService.apply(new ApplyRequest("req-attach", 1L, List.of(), List.of(),
                List.of(new AttachOp(AttachOp.ATTACH, "single_box", "tool0", null))));

        assertThatThrownBy(() -> sceneService.apply(new ApplyRequest("req-add-same-id", 2L, List.of(),
                List.of(addBox("single_box", "world", 0.2, 0.0, 0.0)), List.of())))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("already exists");
        assertThat(sceneService.currentSnapshot().attachedObjects()).hasSize(1);
        assertThat(sceneService.currentSnapshot().worldObjects()).isEmpty();
    }

    @Test
    void concurrentEditsAtSameVersionOnlyOneSucceeds() throws Exception {
        ApplyRequest first = new ApplyRequest("cc-win", 0L, List.of(),
                List.of(addBox("box_winner", "world", 0.1, 0.0, 0.0)), List.of());
        ApplyRequest second = new ApplyRequest("cc-lose", 0L, List.of(),
                List.of(addBox("box_loser", "world", 0.2, 0.0, 0.0)), List.of());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();
        try {
            Future<?> fa = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    resultA.set(sceneService.apply(first));
                } catch (RuntimeException ex) {
                    resultA.set(ex);
                }
            });
            Future<?> fb = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    resultB.set(sceneService.apply(second));
                } catch (RuntimeException ex) {
                    resultB.set(ex);
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Object> results = new ArrayList<>(List.of(resultA.get(), resultB.get()));
        long successCount = results.stream().filter(ApplyResponse.class::isInstance).count();
        long conflictCount = results.stream().filter(VersionConflictException.class::isInstance).count();
        assertThat(successCount).isEqualTo(1L);
        assertThat(conflictCount).isEqualTo(1L);

        VersionConflictException conflict = results.stream()
                .filter(VersionConflictException.class::isInstance)
                .map(VersionConflictException.class::cast)
                .findFirst().orElseThrow();
        assertThat(conflict.getCurrentVersion()).isEqualTo(1L);
        assertThat(conflict.getChangedObjects()).hasSize(1)
                .allMatch(id -> id.equals("box_winner") || id.equals("box_loser"));

        assertThat(currentVersion()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM world_object", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scene_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void historySnapshotsRemainReadableWhileWritesContinue() throws Exception {
        sceneService.apply(new ApplyRequest("hw-v1", 0L, List.of(),
                List.of(addBox("hist_box", "world", 0.1, 0.0, 0.0)), List.of()));
        SnapshotView v1 = sceneService.snapshotAt(1L);
        assertThat(v1).isNotNull();

        int writeCount = 20;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> writer = pool.submit(() -> {
            long expected = 1L;
            for (int i = 2; i <= writeCount + 1; i++) {
                ApplyResponse response = sceneService.apply(new ApplyRequest(
                        "hw-write-" + i, expected, List.of(),
                        List.of(new ObjectOp(ObjectOp.MODIFY, "hist_box", null,
                                boxBody("hist_box", "world", 0.1 * i, 0.0, 0.0))),
                        List.of()));
                assertThat(response.version()).isEqualTo((long) i);
                expected = i;
            }
        });
        try {
            for (int i = 0; i < 50; i++) {
                SnapshotView reread = sceneService.snapshotAt(1L);
                assertThat(reread).usingRecursiveComparison().isEqualTo(v1);
                assertThat(sceneService.snapshotAt(0L).worldObjects()).isEmpty();
            }
            writer.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(currentVersion()).isEqualTo((long) writeCount + 1);
        SnapshotView latest = sceneService.currentSnapshot();
        assertThat(latest.worldObjects()).hasSize(1);
        assertThat(latest.worldObjects().get(0).pose().x()).isGreaterThan(0.1);
        assertThat(sceneService.snapshotAt(1L).worldObjects().get(0).pose().x())
                .isEqualTo(0.1);
    }

    private VersionConflictException expectConflict(ApplyRequest request) {
        try {
            sceneService.apply(request);
            throw new AssertionError("expected VersionConflictException");
        } catch (VersionConflictException ex) {
            return ex;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long currentVersion() {
        return jdbc.queryForObject("SELECT version FROM scene_meta WHERE id = 0", Long.class);
    }

    private ObjectOp addBox(String id, String frameId, double x, double y, double z) {
        return new ObjectOp(ObjectOp.ADD, null, null, boxBody(id, frameId, x, y, z));
    }

    private CollisionObjectBody boxBody(String id, String frameId, double x, double y, double z) {
        return new CollisionObjectBody(id, frameId,
                new Pose(x, y, z, 0.0, 0.0, 0.0, 1.0),
                new Size3(0.1, 0.1, 0.1));
    }
}
