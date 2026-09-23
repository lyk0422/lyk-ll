package com.example.starter.scene.service;

import com.example.starter.scene.db.SchemaInitializer;
import com.example.starter.scene.exception.SceneConflictException;
import com.example.starter.scene.exception.SceneNotFoundException;
import com.example.starter.scene.exception.SceneValidationException;
import com.example.starter.scene.model.SceneApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Planning Scene 业务规则与 H2 真实事务测试：
 * 覆盖幂等、版本冲突列表、批量失败零写入、引用维护、attach/detach、
 * 历史快照、同版本并发编辑与读历史不阻塞写入。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanningSceneServiceTest {

    @Autowired
    private PlanningSceneService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchemaInitializer schemaInitializer;

    @BeforeEach
    void resetDatabase() {
        for (String table : List.of(
                "scene_idempotent_request", "scene_joint_snapshot", "scene_object_snapshot",
                "scene_version", "scene_object_current", "scene_joint_current",
                "scene_joint_def", "scene_link", "scene_meta")) {
            jdbc.update("DELETE FROM " + table);
        }
        // 重建表（幂等）并恢复 initialized=FALSE 的占位 meta 行
        schemaInitializer.initialize();
    }

    private void initModel() {
        long version = service.initializeRobotModel(new SceneApi.RobotModelRequest(
                List.of("base_link", "arm_link"),
                List.of(new SceneApi.JointSpec("joint_a", -1.5, 1.5, 0.0),
                        new SceneApi.JointSpec("joint_b", -3.14, 3.14, 0.5)),
                null));
        assertThat(version).isZero();
    }

    private SceneApi.OperationRequest upsert(String id, String frame, double x, double y, double z) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.OBJECT_UPSERT,
                id, null, frame, "box", 0.1, 0.2, 0.3, x, y, z,
                0.0, 0.0, 0.0, 1.0, null, null, null);
    }

    private SceneApi.OperationRequest delete(String id) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.OBJECT_DELETE,
                id, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private SceneApi.OperationRequest rename(String id, String newId) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.OBJECT_RENAME,
                id, newId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private SceneApi.OperationRequest joint(String name, double value) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.JOINT_UPDATE,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, name, value, null);
    }

    private SceneApi.OperationRequest attach(String id, String link) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.ATTACH,
                id, null, null, null, null, null, null, 0.3, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, null, null, link);
    }

    @Test
    @DisplayName("并发初始化：并发创建机器人模型只有一方成功，另一方得到 409")
    void concurrentRobotModelInitOnlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int client = 0; client < 2; client++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.initializeRobotModel(new SceneApi.RobotModelRequest(
                                List.of("base_link"),
                                List.of(new SceneApi.JointSpec("j", -1.0, 1.0, 0.0)),
                                null));
                        successes.incrementAndGet();
                    } catch (SceneConflictException ex) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(successes).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(service.currentSnapshot().version()).isZero();
    }

    @Test
    @DisplayName("主流程：初始化版本0，批量新增对象+更新关节提交为版本1")
    void commitBatchProducesNewVersion() {
        initModel();
        SceneApi.ChangeRequest request = new SceneApi.ChangeRequest("req-1", 0,
                List.of(upsert("box_a", "world", 1, 0, 0),
                        joint("joint_a", 0.7)),
                null);

        SceneApi.CommitResult result = service.apply(request);

        assertThat(result.committed()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.version()).isEqualTo(1);

        SceneApi.SnapshotResponse current = service.currentSnapshot();
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.objects()).hasSize(1);
        assertThat(current.objects().get(0).objectId()).isEqualTo("box_a");
        assertThat(current.objects().get(0).attachedLink()).isNull();
        assertThat(current.joints()).filteredOn(j -> j.name().equals("joint_a"))
                .singleElement()
                .extracting(SceneApi.JointSnapshot::value).isEqualTo(0.7);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参数重放返回原结果且不产生新版本")
    void sameRequestIdSameParametersReplaysOriginalResult() {
        initModel();
        SceneApi.ChangeRequest request = new SceneApi.ChangeRequest("idem-1", 0,
                List.of(upsert("box_a", "world", 1, 0, 0)), null);

        SceneApi.CommitResult first = service.apply(request);
        SceneApi.CommitResult second = service.apply(request);

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.replayed()).isTrue();
        assertThat(second.committed()).isFalse();
        assertThat(second.version()).isEqualTo(1);
        assertThat(service.currentSnapshot().version()).isEqualTo(1);

        Integer historyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_version", Integer.class);
        assertThat(historyRows).isEqualTo(2); // 版本 0 基线 + 版本 1
    }

    @Test
    @DisplayName("幂等冲突：同 requestId 异参数返回 409 且不写入")
    void sameRequestIdDifferentParametersConflicts() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("idem-2", 0,
                List.of(upsert("box_a", "world", 1, 0, 0)), null));

        SceneApi.ChangeRequest different = new SceneApi.ChangeRequest("idem-2", 1,
                List.of(upsert("box_a", "world", 2, 0, 0)), null);

        assertThatThrownBy(() -> service.apply(different))
                .isInstanceOf(SceneConflictException.class)
                .hasMessageContaining("already used with different parameters")
                .extracting(ex -> ((SceneConflictException) ex).getCode())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");
        assertThat(service.currentSnapshot().version()).isEqualTo(1);
        assertThat(service.currentSnapshot().objects().get(0).x()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("版本过期：409 返回当前版本与自期望版本后变化的对象/关节 ID")
    void staleVersionReturnsChangedIds() {
        initModel();
        // 客户端 A 先提交版本 1：新增 box_a、更新 joint_a 并改变机器人姿态
        service.apply(new SceneApi.ChangeRequest("a-1", 0,
                List.of(upsert("box_a", "world", 1, 0, 0), joint("joint_a", 0.4)),
                new SceneApi.PoseData("base_link", 0, 0, 0, 0, 0, 0, 1)));
        // 客户端 B 基于版本 0 新增 box_b、更新 joint_b
        SceneApi.ChangeRequest stale = new SceneApi.ChangeRequest("b-stale", 0,
                List.of(upsert("box_b", "world", 0, 2, 0), joint("joint_b", -0.5)), null);

        assertThatThrownBy(() -> service.apply(stale))
                .isInstanceOfSatisfying(SceneConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(ex.getCurrentVersion()).isEqualTo(1);
                    assertThat(ex.getExpectedVersion()).isZero();
                    assertThat(ex.getChangedObjectIds()).containsExactly("box_a");
                    assertThat(ex.getChangedJointNames()).containsExactly("joint_a");
                    assertThat(ex.isRobotPoseChanged()).isTrue();
                });
    }

    @Test
    @DisplayName("批量失败零写入：合法操作后跟非法操作，整批回滚版本与数据不变")
    void invalidBatchRollsBackEverything() {
        initModel();
        SceneApi.ChangeRequest badBatch = new SceneApi.ChangeRequest("bad-1", 0, List.of(
                upsert("box_a", "world", 1, 0, 0),
                new SceneApi.OperationRequest(SceneApi.OperationType.ATTACH,
                        "ghost", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, "arm_link")),
                null);

        assertThatThrownBy(() -> service.apply(badBatch))
                .isInstanceOf(SceneValidationException.class);

        SceneApi.SnapshotResponse current = service.currentSnapshot();
        assertThat(current.version()).isZero();
        assertThat(current.objects()).isEmpty();
        Integer objectRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_object_current", Integer.class);
        Integer versionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_version", Integer.class);
        Integer idempotentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_idempotent_request", Integer.class);
        assertThat(objectRows).isZero();
        assertThat(versionRows).isEqualTo(1); // 仅基线版本 0
        assertThat(idempotentRows).isZero();
    }

    @Test
    @DisplayName("关节规则：不存在、越界、非有限数值均 400；合法更新生效")
    void jointValidationRules() {
        initModel();

        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("j-1", 0,
                List.of(joint("missing_joint", 0.1)), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("does not exist");

        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("j-2", 0,
                List.of(joint("joint_a", 2.0)), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("out of range");

        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("j-3", 0,
                List.of(joint("joint_a", Double.NaN)), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("finite");

        service.apply(new SceneApi.ChangeRequest("j-ok", 0,
                List.of(joint("joint_b", -1.25)), null));
        assertThat(service.currentSnapshot().joints())
                .filteredOn(j -> j.name().equals("joint_b"))
                .singleElement()
                .extracting(SceneApi.JointSnapshot::value).isEqualTo(-1.25);
    }

    @Test
    @DisplayName("引用校验：对象坐标系不存在 400，附着物 Link 不存在 400")
    void frameAndLinkMustExist() {
        initModel();

        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("f-1", 0,
                List.of(upsert("box_a", "no_such_frame", 0, 0, 0)), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("frame");

        service.apply(new SceneApi.ChangeRequest("f-2", 0,
                List.of(upsert("box_a", "world", 0, 0, 0)), null));

        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("f-3", 1,
                List.of(attach("box_a", "no_such_link")), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("robot link");
    }

    @Test
    @DisplayName("互斥：对象不能同时位于世界与附着集合")
    void objectCannotBeWorldAndAttachedSimultaneously() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("w-1", 0,
                List.of(upsert("box_a", "world", 0, 0, 0)), null));
        service.apply(new SceneApi.ChangeRequest("w-2", 1,
                List.of(attach("box_a", "arm_link")), null));

        // 已附着对象再次 attach → 400
        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("w-3", 2,
                List.of(attach("box_a", "base_link")), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("already attached");

        // 已附着对象直接世界 upsert → 400
        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("w-4", 2,
                List.of(upsert("box_a", "world", 9, 9, 9)), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("DETACH");

        // 世界对象 detach → 400
        service.apply(new SceneApi.ChangeRequest("w-5", 2,
                List.of(detachOp("box_a", "world")), null));
        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("w-6", 3,
                List.of(detachOp("box_a", "world")), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("not attached");
    }

    private SceneApi.OperationRequest detachOp(String id, String frame) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.DETACH,
                id, null, frame, null, null, null, null, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, null, null, null);
    }

    @Test
    @DisplayName("attach/detach 切换：附着后 attachedLink 非空，detach 后回到世界")
    void attachDetachTransition() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("t-1", 0,
                List.of(upsert("box_a", "world", 1, 2, 3)), null));
        service.apply(new SceneApi.ChangeRequest("t-2", 1,
                List.of(attach("box_a", "arm_link")), null));

        SceneApi.ObjectSnapshot attached = service.currentSnapshot().objects().get(0);
        assertThat(attached.attachedLink()).isEqualTo("arm_link");
        assertThat(attached.frameId()).isEqualTo("arm_link");

        service.apply(new SceneApi.ChangeRequest("t-3", 2,
                List.of(detachOp("box_a", "world")), null));
        SceneApi.ObjectSnapshot inWorld = service.currentSnapshot().objects().get(0);
        assertThat(inWorld.attachedLink()).isNull();
        assertThat(inWorld.frameId()).isEqualTo("world");
    }

    @Test
    @DisplayName("删除被引用（附着）对象：同步清除附着关系，不存在悬空引用")
    void deleteAttachedObjectClearsReference() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("d-1", 0,
                List.of(upsert("box_a", "world", 0, 0, 0)), null));
        service.apply(new SceneApi.ChangeRequest("d-2", 1,
                List.of(attach("box_a", "arm_link")), null));
        service.apply(new SceneApi.ChangeRequest("d-3", 2,
                List.of(delete("box_a")), null));

        assertThat(service.currentSnapshot().objects()).isEmpty();
        Integer attachedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_object_current WHERE attached_link IS NOT NULL",
                Integer.class);
        assertThat(attachedRows).isZero();
    }

    @Test
    @DisplayName("重命名被引用（附着）对象：附着关系迁移到新 ID，旧 ID 消失")
    void renameAttachedObjectMigratesReference() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("r-1", 0,
                List.of(upsert("box_a", "world", 0, 0, 0)), null));
        service.apply(new SceneApi.ChangeRequest("r-2", 1,
                List.of(attach("box_a", "arm_link")), null));
        service.apply(new SceneApi.ChangeRequest("r-3", 2,
                List.of(rename("box_a", "box_b")), null));

        List<SceneApi.ObjectSnapshot> objects = service.currentSnapshot().objects();
        assertThat(objects).hasSize(1);
        assertThat(objects.get(0).objectId()).isEqualTo("box_b");
        assertThat(objects.get(0).attachedLink()).isEqualTo("arm_link");
    }

    @Test
    @DisplayName("重命名冲突：目标 ID 已存在则整批 400")
    void renameToExistingIdRejected() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("rn-1", 0,
                List.of(upsert("box_a", "world", 0, 0, 0), upsert("box_b", "world", 1, 0, 0)),
                null));

        assertThatThrownBy(() -> service.apply(new SceneApi.ChangeRequest("rn-2", 1,
                List.of(rename("box_a", "box_b")), null)))
                .isInstanceOf(SceneValidationException.class)
                .hasMessageContaining("already exists");
        assertThat(service.currentSnapshot().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("历史快照不可变：版本1内容不随后续提交改变；不存在版本 404")
    void historySnapshotsAreImmutable() {
        initModel();
        service.apply(new SceneApi.ChangeRequest("h-1", 0,
                List.of(upsert("box_a", "world", 1, 0, 0)), null));
        service.apply(new SceneApi.ChangeRequest("h-2", 1,
                List.of(upsert("box_a", "world", 2, 0, 0)), null));
        service.apply(new SceneApi.ChangeRequest("h-3", 2,
                List.of(delete("box_a")), null));

        SceneApi.SnapshotResponse v1 = service.snapshot(1);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.objects()).hasSize(1);
        assertThat(v1.objects().get(0).x()).isEqualTo(1.0);

        SceneApi.SnapshotResponse v2 = service.snapshot(2);
        assertThat(v2.objects().get(0).x()).isEqualTo(2.0);

        SceneApi.SnapshotResponse v3 = service.snapshot(3);
        assertThat(v3.objects()).isEmpty();

        assertThatThrownBy(() -> service.snapshot(99))
                .isInstanceOf(SceneNotFoundException.class);
    }

    @Test
    @DisplayName("并发：同版本并发编辑只有一方成功，另一方得到版本冲突")
    void concurrentSameVersionEditorsOnlyOneWins() throws Exception {
        initModel();
        int rounds = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                final long base = service.currentSnapshot().version();
                final int roundNo = round;
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger commits = new AtomicInteger();
                AtomicInteger conflicts = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();

                for (int client = 0; client < 2; client++) {
                    final int clientId = client;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        SceneApi.ChangeRequest request = new SceneApi.ChangeRequest(
                                "cc-" + roundNo + "-" + clientId, base,
                                List.of(upsert("obj_" + roundNo + "_" + clientId, "world",
                                        clientId, 0, 0)), null);
                        try {
                            service.apply(request);
                            commits.incrementAndGet();
                        } catch (SceneConflictException ex) {
                            if ("VERSION_CONFLICT".equals(ex.getCode())) {
                                conflicts.incrementAndGet();
                            } else {
                                throw new AssertionError("unexpected conflict code " + ex.getCode());
                            }
                        }
                        return null;
                    }));
                }

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
                assertThat(commits).as("round %d commits", round).hasValue(1);
                assertThat(conflicts).as("round %d conflicts", round).hasValue(1);
                assertThat(service.currentSnapshot().version()).isEqualTo(base + 1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.currentSnapshot().version()).isEqualTo(rounds);
    }

    @Test
    @DisplayName("读历史不阻塞写入：并发读取旧快照期间写入持续提交")
    void readingHistoryDoesNotBlockWrites() throws Exception {
        initModel();
        service.apply(new SceneApi.ChangeRequest("seed", 0,
                List.of(upsert("anchor", "world", 0, 0, 0)), null));

        int writeCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch stop = new CountDownLatch(1);
        try {
            Future<?> reader = pool.submit(() -> {
                while (stop.getCount() > 0) {
                    SceneApi.SnapshotResponse v1 = service.snapshot(1);
                    if (v1.objects().size() != 1
                            || !"anchor".equals(v1.objects().get(0).objectId())) {
                        throw new AssertionError("historical snapshot changed during reads");
                    }
                }
                return null;
            });

            Future<?> writer = pool.submit(() -> {
                for (int i = 0; i < writeCount; i++) {
                    service.apply(new SceneApi.ChangeRequest("rw-" + i, 1L + i,
                            List.of(upsert("moving_" + i, "world", i, 0, 0)), null));
                }
                return null;
            });

            writer.get(60, TimeUnit.SECONDS);
            stop.countDown();
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.currentSnapshot().version()).isEqualTo(1L + writeCount);
    }
}
