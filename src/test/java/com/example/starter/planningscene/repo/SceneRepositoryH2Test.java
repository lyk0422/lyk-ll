package com.example.starter.planningscene.repo;

import com.example.starter.planningscene.domain.JointState;
import com.example.starter.planningscene.domain.ObjectState;
import com.example.starter.planningscene.domain.ObjectType;
import com.example.starter.planningscene.domain.Pose;
import com.example.starter.planningscene.domain.RobotModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2（MODE=MySQL）数据访问与事务边界测试：快照写入、乐观版本闸门、回滚零写入。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:repo_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class SceneRepositoryH2Test {

    @Autowired
    private SceneRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 每个场景前清空版本化数据并恢复版本 0 初始场景，保证用例顺序无关。 */
    @BeforeEach
    void resetScene() {
        jdbcTemplate.execute("DELETE FROM scene_request");
        jdbcTemplate.execute("DELETE FROM scene_object");
        jdbcTemplate.execute("DELETE FROM scene_joint");
        jdbcTemplate.execute("DELETE FROM scene_robot_state");
        jdbcTemplate.execute("DELETE FROM scene_meta");
        jdbcTemplate.update("INSERT INTO scene_meta (id, current_version) VALUES (0, 0)");
        jdbcTemplate.update("INSERT INTO scene_robot_state "
                + "(version, pos_x, pos_y, pos_z, qx, qy, qz, qw) VALUES (0,0,0,0,0,0,0,1)");
        jdbcTemplate.update("INSERT INTO scene_joint (version, joint_name, position, lower_bound, upper_bound) "
                + "SELECT 0, joint_name, 0.0, lower_bound, upper_bound FROM robot_joint");
    }

    @Test
    void seedDataLoadsInitialSceneAndRobotModel() {
        RobotModel model = repository.loadRobotModel();
        assertThat(model.links()).containsExactlyInAnyOrder(
                "world", "base_link", "arm_link", "gripper_link");
        assertThat(model.joints()).containsOnlyKeys("joint_1", "joint_2", "joint_3");
        assertThat(repository.currentVersion()).isZero();

        var snapshot = repository.loadSnapshot(0).orElseThrow();
        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.joints()).containsOnlyKeys("joint_1", "joint_2", "joint_3");
        assertThat(snapshot.objects()).isEmpty();
        assertThat(snapshot.robotPose()).isEqualTo(Pose.origin());
    }

    @Test
    void tryAdvanceWritesFullSnapshotAtNewVersion() {
        long before = repository.currentVersion();
        List<JointState> joints = List.of(
                new JointState("joint_1", 0.5, -3.14159, 3.14159),
                new JointState("joint_2", 0.1, -1.5708, 1.5708),
                new JointState("joint_3", 0.0, 0.0, 0.1));
        List<ObjectState> objects = List.of(new ObjectState(
                "box", ObjectType.WORLD, "base_link", null, "{\"shape\":\"box\"}",
                Pose.origin(), List.of()));

        Long newVersion = new TransactionTemplate(transactionManager).execute(status ->
                repository.tryAdvance(before, new Pose(1, 0, 0, 0, 0, 0, 1), joints, objects)
                        .orElseThrow());

        assertThat(newVersion).isEqualTo(before + 1);
        assertThat(repository.currentVersion()).isEqualTo(newVersion);

        var oldSnapshot = repository.loadSnapshot(before).orElseThrow();
        assertThat(oldSnapshot.objects()).isEmpty();

        var newSnapshot = repository.loadSnapshot(newVersion).orElseThrow();
        assertThat(newSnapshot.objects()).containsOnlyKeys("box");
        assertThat(newSnapshot.joints().get("joint_1").position()).isEqualTo(0.5);
        assertThat(newSnapshot.robotPose().x()).isEqualTo(1.0);
    }

    @Test
    void staleOptimisticAdvanceAffectsNoRowsAndWritesNothing() {
        long current = repository.currentVersion();

        Optional<Long> advanced = new TransactionTemplate(transactionManager).execute(
                status -> repository.tryAdvance(current + 100, Pose.origin(),
                        List.of(), List.of()));

        assertThat(advanced).isEmpty();
        assertThat(repository.currentVersion()).isEqualTo(current);
        assertThat(repository.loadSnapshot(current + 101)).isEmpty();
    }

    @Test
    void failedTransactionRollsBackSnapshotAndVersion() {
        long before = repository.currentVersion();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            repository.tryAdvance(before, Pose.origin(),
                    List.of(new JointState("joint_1", 0.2, -3.14159, 3.14159)),
                    List.of(new ObjectState("tmp", ObjectType.WORLD, "world", null,
                            "{}", Pose.origin(), List.of())));
            throw new IllegalStateException("simulated batch failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.currentVersion()).isEqualTo(before);
        assertThat(repository.loadSnapshot(before + 1)).isEmpty();
        assertThat(repository.loadSnapshot(before).orElseThrow().objects()).isEmpty();
    }

    @Test
    void idempotentRequestRoundTrips() {
        long now = System.currentTimeMillis();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                repository.saveRequest("req-1", "hash-1", 200,
                        "{\"committed\":true,\"version\":1,\"replayed\":false}", now));

        var record = repository.findRequest("req-1").orElseThrow();
        assertThat(record.payloadHash()).isEqualTo("hash-1");
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.responseJson()).contains("\"version\":1");
        assertThat(repository.findRequest("missing")).isEmpty();
    }
}
