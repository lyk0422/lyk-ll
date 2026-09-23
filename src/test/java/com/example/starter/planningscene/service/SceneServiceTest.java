package com.example.starter.planningscene.service;

import com.example.starter.planningscene.dto.CommitResponse;
import com.example.starter.planningscene.dto.SceneUpdateRequest;
import com.example.starter.planningscene.dto.SceneViewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.example.starter.planningscene.SceneTestFixtures.addAttached;
import static com.example.starter.planningscene.SceneTestFixtures.addWorld;
import static com.example.starter.planningscene.SceneTestFixtures.attach;
import static com.example.starter.planningscene.SceneTestFixtures.delete;
import static com.example.starter.planningscene.SceneTestFixtures.detach;
import static com.example.starter.planningscene.SceneTestFixtures.joint;
import static com.example.starter.planningscene.SceneTestFixtures.pose;
import static com.example.starter.planningscene.SceneTestFixtures.rename;
import static com.example.starter.planningscene.SceneTestFixtures.request;
import static com.example.starter.planningscene.SceneTestFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 场景服务业务规则测试：批量提交、关节/姿态校验、对象集合互斥、引用维护、attach/detach 与历史快照。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class SceneServiceTest {

    @Autowired
    private SceneService sceneService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void batchCommitCreatesVersionWithWorldObjectJointAndRobotPose() {
        SceneUpdateRequest req = request("req-create", 0, pose(1, 2, 0),
                List.of(joint("joint_1", 0.7), joint("joint_3", 0.05)),
                List.of(addWorld("table", "world", "{\"shape\":\"box\"}", pose(1, 0, 0.3))));

        CommitResponse response = sceneService.commit(req);

        assertThat(response.committed()).isTrue();
        assertThat(response.replayed()).isFalse();
        assertThat(response.version()).isEqualTo(1);

        SceneViewResponse view = sceneService.currentView();
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.robotPose().x()).isEqualTo(1.0);
        assertThat(view.robotPose().y()).isEqualTo(2.0);
        assertThat(view.joints()).filteredOn(j -> j.name().equals("joint_1"))
                .singleElement().satisfies(j -> {
                    assertThat(j.position()).isEqualTo(0.7);
                    assertThat(j.lowerBound()).isEqualTo(-3.14159);
                });
        assertThat(view.joints()).filteredOn(j -> j.name().equals("joint_3"))
                .singleElement().extracting("position").isEqualTo(0.05);
        SceneViewResponse.ObjectView table = view.objects().get(0);
        assertThat(table.objectId()).isEqualTo("table");
        assertThat(table.type()).isEqualTo("WORLD");
        assertThat(table.frameId()).isEqualTo("world");
        assertThat(table.linkId()).isNull();
    }

    @Test
    void invalidJointAndNonFiniteValuesRejectWholeBatchWithNoWrite() {
        SceneUpdateRequest badJoint = request("req-bad-joint", 0, null,
                List.of(joint("missing_joint", 0.1)),
                List.of(addWorld("table", "world", "{}", pose(0, 0, 0))));
        assertThatThrownBy(() -> sceneService.commit(badJoint))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("joint does not exist");

        SceneUpdateRequest outOfBounds = request("req-bad-bounds", 0, null,
                List.of(joint("joint_2", 2.0)), List.of());
        assertThatThrownBy(() -> sceneService.commit(outOfBounds))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("out of bounds");

        SceneUpdateRequest nonFinite = request("req-bad-pose", 0,
                pose(Double.NaN, 0, 0), List.of(), List.of());
        assertThatThrownBy(() -> sceneService.commit(nonFinite))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("finite");

        SceneViewResponse view = sceneService.currentView();
        assertThat(view.version()).isZero();
        assertThat(view.objects()).isEmpty();
        assertThat(view.joints()).allSatisfy(j -> assertThat(j.position()).isZero());
    }

    @Test
    void unknownFrameAndUnknownLinkAreRejected() {
        assertThatThrownBy(() -> sceneService.commit(request("req-bad-frame", 0, null, List.of(),
                List.of(addWorld("o", "no_such_frame", "{}", pose(0, 0, 0))))))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("missing frame");

        assertThatThrownBy(() -> sceneService.commit(request("req-bad-link", 0, null, List.of(),
                List.of(addAttached("o", "no_such_link", "{}", pose(0, 0, 0), List.of())))))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("missing robot link");

        assertThat(sceneService.currentView().version()).isZero();
    }

    @Test
    void objectCannotBeBothWorldAndAttachedAndDuplicateIdRejected() {
        SceneUpdateRequest both = request("req-both", 0, null, List.of(),
                List.of(new com.example.starter.planningscene.dto.ObjectOperationDto(
                        com.example.starter.planningscene.domain.ObjectOperationType.ADD,
                        "o", null, "world", "base_link", "{}", pose(0, 0, 0), null)));
        assertThatThrownBy(() -> sceneService.commit(both))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("exactly one");

        sceneService.commit(request("req-first", 0, null, List.of(),
                List.of(addWorld("dup", "world", "{}", pose(0, 0, 0)))));
        assertThatThrownBy(() -> sceneService.commit(request("req-dup", 1, null, List.of(),
                List.of(addWorld("dup", "world", "{}", pose(0, 0, 0))))))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void attachDetachMovesObjectBetweenSets() {
        sceneService.commit(request("req-add", 0, null, List.of(),
                List.of(addWorld("cup", "world", "{\"shape\":\"cylinder\"}", pose(0, 0, 0.4)))));

        sceneService.commit(request("req-attach", 1, null, List.of(),
                List.of(attach("cup", "gripper_link", List.of("gripper_link", "arm_link")))));
        SceneViewResponse attached = sceneService.currentView();
        SceneViewResponse.ObjectView cup = attached.objects().get(0);
        assertThat(cup.type()).isEqualTo("ATTACHED");
        assertThat(cup.linkId()).isEqualTo("gripper_link");
        assertThat(cup.frameId()).isNull();
        assertThat(cup.touchLinks()).containsExactly("gripper_link", "arm_link");

        // 重复附着被拒绝
        assertThatThrownBy(() -> sceneService.commit(request("req-attach-again", 2, null, List.of(),
                List.of(attach("cup", "gripper_link", List.of())))))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("already attached");

        sceneService.commit(request("req-detach", 2, null, List.of(),
                List.of(detach("cup", "world"))));
        SceneViewResponse detached = sceneService.currentView();
        SceneViewResponse.ObjectView cupAfter = detached.objects().get(0);
        assertThat(cupAfter.type()).isEqualTo("WORLD");
        assertThat(cupAfter.frameId()).isEqualTo("world");
        assertThat(cupAfter.linkId()).isNull();
        assertThat(cupAfter.touchLinks()).isEmpty();
        assertThat(detached.version()).isEqualTo(3);
    }

    @Test
    void deleteReferencedObjectCascadesToReferrersAndRenameRewritesFrameRefs() {
        // shelf 引用 world；can 引用 shelf
        sceneService.commit(request("req-chain", 0, null, List.of(), List.of(
                addWorld("shelf", "world", "{}", pose(0, 0, 0.5)),
                addWorld("can", "shelf", "{}", pose(0.1, 0, 0.7)))));

        sceneService.commit(request("req-rename", 1, null, List.of(),
                List.of(rename("shelf", "shelf2"))));
        SceneViewResponse renamed = sceneService.currentView();
        assertThat(renamed.objects()).extracting(SceneViewResponse.ObjectView::objectId)
                .containsExactlyInAnyOrder("shelf2", "can");
        assertThat(renamed.objects()).filteredOn(o -> o.objectId().equals("can"))
                .singleElement().extracting(SceneViewResponse.ObjectView::frameId).isEqualTo("shelf2");

        // 删除 shelf2 必须级联删除引用它的 can；之后版本中两者都不存在
        sceneService.commit(request("req-delete", 2, null, List.of(),
                List.of(delete("shelf2"))));
        assertThat(sceneService.currentView().objects()).isEmpty();
        assertThat(sceneService.currentView().version()).isEqualTo(3);
    }

    @Test
    void updateToMissingFrameIsRejectedWithRollback() {
        sceneService.commit(request("req-add", 0, null, List.of(),
                List.of(addWorld("box", "world", "{}", pose(0, 0, 0)))));
        long before = sceneService.currentView().version();

        assertThatThrownBy(() -> sceneService.commit(request("req-bad-update", 1, null,
                List.of(joint("joint_1", 0.9)),
                List.of(update("box", "ghost_frame", null, null, null)))))
                .isInstanceOf(SceneException.InvalidBatch.class)
                .hasMessageContaining("missing frame");

        assertThat(sceneService.currentView().version()).isEqualTo(before);
        assertThat(sceneService.currentView().joints())
                .filteredOn(j -> j.name().equals("joint_1"))
                .singleElement().extracting("position").isEqualTo(0.0);
    }

    @Test
    void historySnapshotsAreImmutableAndReadableAtAnyVersion() {
        sceneService.commit(request("req-v1", 0, null, List.of(),
                List.of(addWorld("a", "world", "{}", pose(0, 0, 0)))));
        sceneService.commit(request("req-v2", 1, null, List.of(joint("joint_1", 0.3)),
                List.of(addWorld("b", "base_link", "{}", pose(0, 0, 1)))));
        sceneService.commit(request("req-v3", 2, null, List.of(),
                List.of(delete("a"))));

        SceneViewResponse v0 = sceneService.snapshot(0);
        SceneViewResponse v1 = sceneService.snapshot(1);
        SceneViewResponse v2 = sceneService.snapshot(2);
        SceneViewResponse v3 = sceneService.snapshot(3);

        assertThat(v0.objects()).isEmpty();
        assertThat(v1.objects()).extracting(SceneViewResponse.ObjectView::objectId).containsExactly("a");
        assertThat(v2.objects()).extracting(SceneViewResponse.ObjectView::objectId)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(v2.joints()).filteredOn(j -> j.name().equals("joint_1"))
                .singleElement().extracting("position").isEqualTo(0.3);
        assertThat(v3.objects()).extracting(SceneViewResponse.ObjectView::objectId).containsExactly("b");

        assertThatThrownBy(() -> sceneService.snapshot(99))
                .isInstanceOf(SceneException.NotFound.class);
    }
}
