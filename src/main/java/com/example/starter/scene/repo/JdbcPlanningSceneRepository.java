package com.example.starter.scene.repo;

import com.example.starter.scene.model.RobotPose;
import com.example.starter.scene.model.SceneSnapshot;
import com.example.starter.scene.service.CurrentScene;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 基于 {@link NamedParameterJdbcTemplate} 的 H2 仓储实现。
 */
@Repository
public class JdbcPlanningSceneRepository implements PlanningSceneRepository {

    private static final int META_ID = 1;

    private static final String SNAPSHOT_META_SQL = """
            SELECT request_id, created_at,
              pose_x, pose_y, pose_z, pose_qx, pose_qy, pose_qz, pose_qw
            FROM scene_version WHERE version = :version
            """;

    private static final String CURRENT_POSE_SQL = """
            SELECT pose_x, pose_y, pose_z, pose_qx, pose_qy, pose_qz, pose_qw
            FROM scene_meta WHERE id = :id
            """;

    private static final RowMapper<RobotPose> POSE_MAPPER = (rs, n) -> new RobotPose(
            rs.getDouble("pose_x"), rs.getDouble("pose_y"), rs.getDouble("pose_z"),
            rs.getDouble("pose_qx"), rs.getDouble("pose_qy"),
            rs.getDouble("pose_qz"), rs.getDouble("pose_qw"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPlanningSceneRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> findAllLinkNames() {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT link_name FROM ps_link", Map.of(), String.class));
    }

    @Override
    public Map<String, JointLimit> findAllJointLimits() {
        Map<String, JointLimit> limits = new TreeMap<>();
        jdbc.query("SELECT joint_name, lower_limit, upper_limit FROM ps_joint",
                (RowCallbackHandler) rs -> limits.put(rs.getString("joint_name"),
                        new JointLimit(rs.getDouble("lower_limit"),
                                rs.getDouble("upper_limit"))));
        return limits;
    }

    @Override
    public long lockAndGetCurrentVersion() {
        Long version = jdbc.queryForObject(
                "SELECT current_version FROM scene_meta WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", META_ID), Long.class);
        if (version == null) {
            throw new IllegalStateException("scene_meta 单例行不存在");
        }
        return version;
    }

    @Override
    public IdempotentRecord findRequest(String requestId) {
        List<IdempotentRecord> rows = jdbc.query(
                "SELECT request_hash, version FROM request_log WHERE request_id = :requestId",
                new MapSqlParameterSource("requestId", requestId),
                (rs, n) -> new IdempotentRecord(rs.getString("request_hash"),
                        rs.getLong("version")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public CurrentScene loadCurrent() {
        RobotPose pose = jdbc.queryForObject(CURRENT_POSE_SQL,
                new MapSqlParameterSource("id", META_ID), POSE_MAPPER);
        long version = jdbc.queryForObject(
                "SELECT current_version FROM scene_meta WHERE id = :id",
                new MapSqlParameterSource("id", META_ID), Long.class);

        Map<String, Double> joints = new TreeMap<>();
        jdbc.query("SELECT joint_name, position FROM joint_state",
                (RowCallbackHandler) rs ->
                        joints.put(rs.getString("joint_name"), rs.getDouble("position")));

        Map<String, CurrentScene.SceneObject> objects = new TreeMap<>();
        jdbc.query("SELECT object_id, frame_id, payload, updated_version FROM collision_object",
                (RowCallbackHandler) rs ->
                        objects.put(rs.getString("object_id"), new CurrentScene.SceneObject(
                                rs.getString("frame_id"), rs.getString("payload"),
                                rs.getLong("updated_version"))));

        Map<String, String> attachments = new TreeMap<>();
        jdbc.query("SELECT object_id, link_name FROM attachment",
                (RowCallbackHandler) rs ->
                        attachments.put(rs.getString("object_id"), rs.getString("link_name")));

        return new CurrentScene(version, pose, joints, objects, attachments);
    }

    @Override
    public void applyCommit(CommitData data) {
        RobotPose pose = data.pose();

        jdbc.update("""
                        UPDATE scene_meta SET current_version = :version,
                          pose_x = :x, pose_y = :y, pose_z = :z,
                          pose_qx = :qx, pose_qy = :qy, pose_qz = :qz, pose_qw = :qw
                        WHERE id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("version", data.newVersion())
                        .addValue("x", pose.x()).addValue("y", pose.y())
                        .addValue("z", pose.z()).addValue("qx", pose.qx())
                        .addValue("qy", pose.qy()).addValue("qz", pose.qz())
                        .addValue("qw", pose.qw()).addValue("id", META_ID));

        jdbc.update("DELETE FROM joint_state", Map.of());
        for (Map.Entry<String, Double> entry : data.joints().entrySet()) {
            jdbc.update(
                    "INSERT INTO joint_state (joint_name, position) "
                            + "VALUES (:jointName, :position)",
                    new MapSqlParameterSource()
                            .addValue("jointName", entry.getKey())
                            .addValue("position", entry.getValue()));
        }

        jdbc.update("DELETE FROM attachment", Map.of());
        jdbc.update("DELETE FROM collision_object", Map.of());
        for (Map.Entry<String, CurrentScene.SceneObject> entry : data.objects().entrySet()) {
            jdbc.update("""
                            INSERT INTO collision_object (object_id, frame_id, payload, updated_version)
                            VALUES (:objectId, :frameId, :payload, :updatedVersion)
                            """,
                    new MapSqlParameterSource()
                            .addValue("objectId", entry.getKey())
                            .addValue("frameId", entry.getValue().frameId())
                            .addValue("payload", entry.getValue().payload())
                            .addValue("updatedVersion", entry.getValue().updatedVersion()));
        }
        for (Map.Entry<String, String> entry : data.attachments().entrySet()) {
            jdbc.update("""
                            INSERT INTO attachment (object_id, link_name)
                            VALUES (:objectId, :linkName)
                            """,
                    new MapSqlParameterSource()
                            .addValue("objectId", entry.getKey())
                            .addValue("linkName", entry.getValue()));
        }

        jdbc.update("""
                        INSERT INTO scene_version (version, request_id, created_at,
                          pose_x, pose_y, pose_z, pose_qx, pose_qy, pose_qz, pose_qw)
                        VALUES (:version, :requestId, :createdAt,
                          :x, :y, :z, :qx, :qy, :qz, :qw)
                        """,
                new MapSqlParameterSource()
                        .addValue("version", data.newVersion())
                        .addValue("requestId", data.requestId())
                        .addValue("createdAt", data.createdAt())
                        .addValue("x", pose.x()).addValue("y", pose.y())
                        .addValue("z", pose.z()).addValue("qx", pose.qx())
                        .addValue("qy", pose.qy()).addValue("qz", pose.qz())
                        .addValue("qw", pose.qw()));

        for (Map.Entry<String, Double> entry : data.joints().entrySet()) {
            jdbc.update("""
                            INSERT INTO joint_state_history (version, joint_name, position)
                            VALUES (:version, :jointName, :position)
                            """,
                    new MapSqlParameterSource()
                            .addValue("version", data.newVersion())
                            .addValue("jointName", entry.getKey())
                            .addValue("position", entry.getValue()));
        }
        for (Map.Entry<String, CurrentScene.SceneObject> entry : data.objects().entrySet()) {
            jdbc.update("""
                            INSERT INTO object_history (version, object_id, frame_id, payload)
                            VALUES (:version, :objectId, :frameId, :payload)
                            """,
                    new MapSqlParameterSource()
                            .addValue("version", data.newVersion())
                            .addValue("objectId", entry.getKey())
                            .addValue("frameId", entry.getValue().frameId())
                            .addValue("payload", entry.getValue().payload()));
        }
        for (Map.Entry<String, String> entry : data.attachments().entrySet()) {
            jdbc.update("""
                            INSERT INTO attachment_history (version, object_id, link_name)
                            VALUES (:version, :objectId, :linkName)
                            """,
                    new MapSqlParameterSource()
                            .addValue("version", data.newVersion())
                            .addValue("objectId", entry.getKey())
                            .addValue("linkName", entry.getValue()));
        }

        jdbc.update("""
                        INSERT INTO request_log (request_id, request_hash, version, created_at)
                        VALUES (:requestId, :hash, :version, :createdAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("requestId", data.requestId())
                        .addValue("hash", data.requestHash())
                        .addValue("version", data.newVersion())
                        .addValue("createdAt", data.createdAt()));
    }

    @Override
    public SceneSnapshot loadSnapshot(long version) {
        List<SnapshotMeta> headers = jdbc.query(SNAPSHOT_META_SQL,
                new MapSqlParameterSource("version", version),
                (rs, n) -> new SnapshotMeta(rs.getString("request_id"),
                        rs.getLong("created_at"), POSE_MAPPER.mapRow(rs, n)));
        if (headers.isEmpty()) {
            return null;
        }
        SnapshotMeta header = headers.get(0);

        VersionState state = loadVersionState(version);
        List<SceneSnapshot.JointStateView> jointViews = state.joints().entrySet().stream()
                .map(e -> new SceneSnapshot.JointStateView(e.getKey(), e.getValue()))
                .toList();
        List<SceneSnapshot.ObjectView> objectViews = state.objects().entrySet().stream()
                .filter(e -> !state.attachments().containsKey(e.getKey()))
                .map(e -> new SceneSnapshot.ObjectView(e.getKey(),
                        e.getValue().frameId(), e.getValue().payload()))
                .sorted(Comparator.comparing(SceneSnapshot.ObjectView::objectId))
                .toList();
        List<SceneSnapshot.AttachmentView> attachmentViews = state.attachments().entrySet()
                .stream()
                .map(e -> new SceneSnapshot.AttachmentView(e.getKey(), e.getValue()))
                .toList();

        return new SceneSnapshot(version, header.requestId(), header.createdAt(),
                header.pose(), jointViews, objectViews, attachmentViews);
    }

    @Override
    public VersionState loadVersionState(long version) {
        Map<String, Double> joints = new TreeMap<>();
        jdbc.query("""
                        SELECT joint_name, position FROM joint_state_history
                        WHERE version = :version ORDER BY joint_name
                        """,
                new MapSqlParameterSource("version", version),
                (RowCallbackHandler) rs ->
                        joints.put(rs.getString("joint_name"), rs.getDouble("position")));

        Map<String, VersionState.ObjectState> objects = new TreeMap<>();
        jdbc.query("""
                        SELECT object_id, frame_id, payload FROM object_history
                        WHERE version = :version ORDER BY object_id
                        """,
                new MapSqlParameterSource("version", version),
                (RowCallbackHandler) rs -> objects.put(rs.getString("object_id"),
                        new VersionState.ObjectState(
                                rs.getString("frame_id"), rs.getString("payload"))));

        Map<String, String> attachments = new TreeMap<>();
        jdbc.query("""
                        SELECT object_id, link_name FROM attachment_history
                        WHERE version = :version ORDER BY object_id
                        """,
                new MapSqlParameterSource("version", version),
                (RowCallbackHandler) rs ->
                        attachments.put(rs.getString("object_id"), rs.getString("link_name")));

        return new VersionState(joints, objects, attachments);
    }

    private record SnapshotMeta(String requestId, long createdAt, RobotPose pose) {
    }
}
