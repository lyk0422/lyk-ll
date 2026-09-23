package com.example.starter.scene;

import com.example.starter.scene.model.AttachmentCommand;
import com.example.starter.scene.model.AttachmentOp;
import com.example.starter.scene.model.JointUpdate;
import com.example.starter.scene.model.ObjectCommand;
import com.example.starter.scene.model.ObjectOp;
import com.example.starter.scene.model.RobotPose;
import com.example.starter.scene.model.SceneSnapshot;
import com.example.starter.scene.model.SceneUpdateRequest;
import com.example.starter.scene.model.UpdateResult;
import com.example.starter.scene.repo.PlanningSceneRepository;
import com.example.starter.scene.service.PlanningSceneService;
import com.example.starter.scene.service.SceneConflictException;
import com.example.starter.scene.service.SceneValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Planning Scene 业务主流程、失败分支、引用维护与并发边界测试；
 * 全部通过真实 H2（MODE=MySQL）事务与约束验证，不使用 mock。
 */
class PlanningSceneServiceTest extends AbstractSceneIntegrationTest {

    @Autowired
    private PlanningSceneService service;

    @Autowired
    private PlanningSceneRepository repository;

    private SceneUpdateRequest addBoxRequest(String requestId, long expectedVersion) {
        return new SceneUpdateRequest(
                requestId, expectedVersion,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_1", null, "base_link",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode().put("shape", "box"))),
                List.of(new JointUpdate("joint_1", 0.5)),
                new RobotPose(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                null);
    }

    @Test
    void batchAddObjectJointsAndPoseCreatesVersionAndImmutableHistory() {
        UpdateResult result = service.submit(addBoxRequest("req-1", 0));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();
        assertThat(currentVersionInDb()).isEqualTo(1);

        SceneSnapshot current = service.currentSnapshot();
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.worldObjects()).extracting(SceneSnapshot.ObjectView::objectId)
                .containsExactly("box_1");
        assertThat(current.worldObjects()).singleElement().satisfies(o -> {
            assertThat(o.frameId()).isEqualTo("base_link");
            assertThat(o.payload()).contains("\"shape\":\"box\"");
        });
        assertThat(current.joints()).filteredOn(j -> j.jointName().equals("joint_1"))
                .singleElement().extracting(SceneSnapshot.JointStateView::position)
                .isEqualTo(0.5);
        assertThat(current.robotPose().x()).isEqualTo(1.0);
        assertThat(current.attachments()).isEmpty();

        SceneSnapshot initial = service.historicalSnapshot(0);
        assertThat(initial.version()).isZero();
        assertThat(initial.worldObjects()).isEmpty();
        assertThat(initial.robotPose().x()).isZero();
        assertThat(initial.joints()).allSatisfy(j -> assertThat(j.position()).isZero());
    }

    @Test
    void sameRequestIdSameParametersReplaysOriginalResultWithoutNewVersion() {
        UpdateResult first = service.submit(addBoxRequest("req-idem", 0));
        UpdateResult replay = service.submit(addBoxRequest("req-idem", 0));

        assertThat(replay.version()).isEqualTo(first.version()).isEqualTo(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(currentVersionInDb()).isEqualTo(1);
        assertThat(count("scene_version")).isEqualTo(2); // 版本 0 + 版本 1
        assertThat(count("request_log")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParametersConflicts() {
        service.submit(addBoxRequest("req-conflict", 0));

        SceneUpdateRequest different = new SceneUpdateRequest(
                "req-conflict", 1,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_2", null, "tool0", null)),
                null, null, null);

        assertThatThrownBy(() -> service.submit(different))
                .isInstanceOf(SceneConflictException.class)
                .satisfies(ex -> assertThat(((SceneConflictException) ex)
                        .getResponse().reason())
                        .isEqualTo("IDEMPOTENT_PARAM_MISMATCH"));
        assertThat(currentVersionInDb()).isEqualTo(1);
        assertThat(count("collision_object")).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithReorderedPayloadFieldsIsIdempotent() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectCommand first = new ObjectCommand(ObjectOp.ADD, "box_p", null, "base_link",
                mapper.createObjectNode().put("shape", "box").put("size", 0.1));
        ObjectCommand reordered = new ObjectCommand(ObjectOp.ADD, "box_p", null, "base_link",
                mapper.createObjectNode().put("size", 0.1).put("shape", "box"));

        UpdateResult v1 = service.submit(new SceneUpdateRequest(
                "req-payload-order", 0, List.of(first), null, null, null));
        UpdateResult replay = service.submit(new SceneUpdateRequest(
                "req-payload-order", 0, List.of(reordered), null, null, null));

        assertThat(replay.version()).isEqualTo(v1.version()).isEqualTo(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(currentVersionInDb()).isEqualTo(1);
    }

    @Test
    void staleExpectedVersionReturnsChangedObjectAndJointIds() {
        service.submit(addBoxRequest("req-base", 0));
        service.submit(new SceneUpdateRequest(
                "req-v2", 1,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_2", null, "link_1", null)),
                List.of(new JointUpdate("joint_3", -1.0)),
                null, null));

        SceneConflictException ex = (SceneConflictException) catchConflict(
                addBoxRequest("req-late", 0));
        assertThat(ex.getResponse().currentVersion()).isEqualTo(2);
        assertThat(ex.getResponse().changedObjects())
                .containsExactly("box_1", "box_2");
        assertThat(ex.getResponse().changedJoints())
                .containsExactly("joint_1", "joint_3");

        SceneConflictException exFromV1 = (SceneConflictException) catchConflict(
                addBoxRequest("req-late2", 1));
        assertThat(exFromV1.getResponse().changedObjects()).containsExactly("box_2");
        assertThat(exFromV1.getResponse().changedJoints()).containsExactly("joint_3");
    }

    @Test
    void invalidBatchRollsBackEverything() {
        // 第一步合法（新增对象），后续步骤非法：关节超出范围。
        SceneUpdateRequest outOfRange = new SceneUpdateRequest(
                "req-bad-range", 0,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_bad", null, "base_link", null)),
                List.of(new JointUpdate("joint_2", 99.0)),
                null, null);
        assertThatThrownBy(() -> service.submit(outOfRange))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();

        // 引用不存在的坐标系。
        SceneUpdateRequest badFrame = new SceneUpdateRequest(
                "req-bad-frame", 0,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_bad", null, "no_such_frame", null)),
                null, null, null);
        assertThatThrownBy(() -> service.submit(badFrame))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();

        // 引用不存在的关节。
        SceneUpdateRequest badJoint = new SceneUpdateRequest(
                "req-bad-joint", 0, null,
                List.of(new JointUpdate("joint_x", 0.1)), null, null);
        assertThatThrownBy(() -> service.submit(badJoint))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();

        // 数值非有限。
        SceneUpdateRequest nan = new SceneUpdateRequest(
                "req-bad-nan", 0, null,
                List.of(new JointUpdate("joint_1", Double.NaN)), null, null);
        assertThatThrownBy(() -> service.submit(nan))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();

        // 同一批内重复新增同一对象 ID。
        SceneUpdateRequest dup = new SceneUpdateRequest(
                "req-bad-dup", 0,
                List.of(
                        new ObjectCommand(ObjectOp.ADD, "box_dup", null, "base_link", null),
                        new ObjectCommand(ObjectOp.ADD, "box_dup", null, "tool0", null)),
                null, null, null);
        assertThatThrownBy(() -> service.submit(dup))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();

        // 附着到不存在的 Link。
        SceneUpdateRequest badLink = new SceneUpdateRequest(
                "req-bad-link", 0,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_link", null, "base_link", null)),
                null, null,
                List.of(new AttachmentCommand(AttachmentOp.ATTACH, "box_link", "no_link", null)));
        assertThatThrownBy(() -> service.submit(badLink))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();

        // 对未附着对象执行 detach。
        SceneUpdateRequest badDetach = new SceneUpdateRequest(
                "req-bad-detach", 0,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_d", null, "base_link", null)),
                null, null,
                List.of(new AttachmentCommand(AttachmentOp.DETACH, "box_d", null, "base_link")));
        assertThatThrownBy(() -> service.submit(badDetach))
                .isInstanceOf(SceneValidationException.class);
        assertZeroWrite();
    }

    @Test
    void deleteAndRenameMaintainAttachmentReferences() {
        // 同批：新增 box_a 并附着到 link_2。
        service.submit(new SceneUpdateRequest("req-attach", 0,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_a", null, "base_link", null)),
                null, null,
                List.of(new AttachmentCommand(AttachmentOp.ATTACH, "box_a", "link_2", null))));

        SceneSnapshot attached = service.currentSnapshot();
        assertThat(attached.worldObjects()).isEmpty();
        assertThat(attached.attachments())
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.objectId()).isEqualTo("box_a");
                    assertThat(a.linkName()).isEqualTo("link_2");
                });

        // 重命名被附着对象：attachment 引用必须同步改名。
        service.submit(new SceneUpdateRequest("req-rename", 1,
                List.of(new ObjectCommand(ObjectOp.RENAME, "box_a", "box_renamed",
                        null, null)),
                null, null, null));
        SceneSnapshot renamed = service.currentSnapshot();
        assertThat(renamed.attachments())
                .singleElement()
                .extracting(SceneSnapshot.AttachmentView::objectId)
                .isEqualTo("box_renamed");
        assertThat(jdbc.queryForObject(
                "SELECT link_name FROM attachment WHERE object_id = 'box_renamed'",
                String.class)).isEqualTo("link_2");
        assertThat(jdbc.queryForList("SELECT object_id FROM attachment", String.class))
                .doesNotContain("box_a");

        // 删除被附着对象：attachment 引用同步删除。
        service.submit(new SceneUpdateRequest("req-delete", 2,
                List.of(new ObjectCommand(ObjectOp.DELETE, "box_renamed", null, null, null)),
                null, null, null));
        assertThat(count("attachment")).isZero();
        assertThat(count("collision_object")).isZero();
    }

    @Test
    void attachDetachMovesObjectBetweenWorldAndAttachmentSets() {
        service.submit(new SceneUpdateRequest("req-add", 0,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_s", null, "base_link", null)),
                null, null, null));

        service.submit(new SceneUpdateRequest("req-attach2", 1,
                null, null, null,
                List.of(new AttachmentCommand(AttachmentOp.ATTACH, "box_s", "tool0", null))));
        SceneSnapshot attached = service.currentSnapshot();
        assertThat(attached.attachments()).singleElement()
                .extracting(SceneSnapshot.AttachmentView::objectId).isEqualTo("box_s");
        assertThat(attached.worldObjects()).isEmpty();
        // 附着期间同一对象不得出现在世界集合：frame_id 必须为 NULL。
        assertThat(jdbc.queryForObject(
                "SELECT frame_id FROM collision_object WHERE object_id = 'box_s'",
                String.class)).isNull();

        // 重复 attach 非法。
        assertThatThrownBy(() -> service.submit(new SceneUpdateRequest(
                "req-attach-again", 2, null, null, null,
                List.of(new AttachmentCommand(AttachmentOp.ATTACH, "box_s", "tool0", null)))))
                .isInstanceOf(SceneValidationException.class);
        assertThat(currentVersionInDb()).isEqualTo(2);

        service.submit(new SceneUpdateRequest("req-detach2", 2,
                null, null, null,
                List.of(new AttachmentCommand(AttachmentOp.DETACH, "box_s", null, "link_3"))));
        SceneSnapshot detached = service.currentSnapshot();
        assertThat(detached.attachments()).isEmpty();
        assertThat(detached.worldObjects()).singleElement().satisfies(o -> {
            assertThat(o.objectId()).isEqualTo("box_s");
            assertThat(o.frameId()).isEqualTo("link_3");
        });
    }

    @Test
    void concurrentEditorsOnSameVersionExactlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                start.await();
                try {
                    service.submit(new SceneUpdateRequest(
                            "req-conc-" + idx, 0,
                            List.of(new ObjectCommand(ObjectOp.ADD,
                                    "box_conc_" + idx, null, "base_link", null)),
                            null, null, null));
                    success.incrementAndGet();
                } catch (SceneConflictException ex) {
                    conflicts.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(currentVersionInDb()).isEqualTo(1);
        assertThat(count("collision_object")).isEqualTo(1);
        assertThat(count("scene_version")).isEqualTo(2);

        // 失败方基于新版本重试可成功，版本继续单调推进。
        UpdateResult retry = service.submit(new SceneUpdateRequest(
                "req-conc-retry", 1,
                List.of(new ObjectCommand(ObjectOp.ADD, "box_retry", null, "tool0", null)),
                null, null, null));
        assertThat(retry.version()).isEqualTo(2);
        assertThat(currentVersionInDb()).isEqualTo(2);
    }

    @Test
    void historicalSnapshotReadsWhileWritesKeepCommitting() throws Exception {
        // 先建立版本 1。
        service.submit(addBoxRequest("req-h0", 0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch stopReader = new CountDownLatch(1);
        AtomicInteger readerIterations = new AtomicInteger();

        Future<?> reader = pool.submit((Callable<Void>) () -> {
            while (stopReader.getCount() > 0) {
                SceneSnapshot v0 = repository.loadSnapshot(0);
                assertThat(v0).isNotNull();
                assertThat(v0.worldObjects()).isEmpty();
                assertThat(v0.joints()).allSatisfy(j -> assertThat(j.position()).isZero());
                SceneSnapshot v1 = repository.loadSnapshot(1);
                assertThat(v1.worldObjects()).singleElement()
                        .extracting(SceneSnapshot.ObjectView::objectId).isEqualTo("box_1");
                readerIterations.incrementAndGet();
            }
            return null;
        });

        List<Future<?>> writes = new ArrayList<>();
        writes.add(pool.submit((Callable<Void>) () -> {
            for (int i = 2; i <= 12; i++) {
                service.submit(new SceneUpdateRequest(
                        "req-hw-" + i, i - 1,
                        List.of(new ObjectCommand(ObjectOp.ADD,
                                "box_w_" + i, null, "link_1", null)),
                        null, null, null));
            }
            return null;
        }));
        for (Future<?> f : writes) {
            f.get(30, TimeUnit.SECONDS);
        }
        stopReader.countDown();
        reader.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(currentVersionInDb()).isEqualTo(12);
        assertThat(readerIterations.get()).isPositive();
        // 历史版本 0 与 1 的快照仍然不可变。
        assertThat(repository.loadSnapshot(0).worldObjects()).isEmpty();
        assertThat(repository.loadSnapshot(1).worldObjects()).singleElement()
                .extracting(SceneSnapshot.ObjectView::objectId).isEqualTo("box_1");
        assertThat(repository.loadSnapshot(12).worldObjects()).hasSize(12);
    }

    @Test
    void missingHistoricalVersionIsNotFound() {
        assertThatThrownBy(() -> service.historicalSnapshot(99))
                .isInstanceOf(com.example.starter.scene.service.SceneNotFoundException.class);
    }

    private Throwable catchConflict(SceneUpdateRequest request) {
        try {
            service.submit(request);
            throw new AssertionError("期望抛出 SceneConflictException");
        } catch (SceneConflictException ex) {
            return ex;
        }
    }

    private void assertZeroWrite() {
        assertThat(currentVersionInDb()).isZero();
        assertThat(count("collision_object")).isZero();
        assertThat(count("attachment")).isZero();
        assertThat(count("scene_version")).isEqualTo(1);
        assertThat(count("request_log")).isZero();
        assertThat(count("object_history")).isZero();
        assertThat(count("attachment_history")).isZero();
        // 关节状态保持初始 0。
        Map<String, Double> positions = jdbc.query(
                "SELECT joint_name, position FROM joint_state",
                rs -> {
                    Map<String, Double> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getDouble(2));
                    }
                    return map;
                });
        assertThat(positions).containsValue(0.0);
        assertThat(positions).hasSize(6);
    }
}
