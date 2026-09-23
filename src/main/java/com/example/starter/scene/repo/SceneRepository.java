package com.example.starter.scene.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Planning Scene 持久化仓储，基于参数化 SQL 的 JdbcTemplate 实现。
 * 所有写方法须在调用方事务内执行，失败时随事务整体回滚。
 */
@Repository
public class SceneRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<JointRow> JOINT_MAPPER = (rs, n) -> new JointRow(
            rs.getString("name"), rs.getDouble("position"),
            rs.getDouble("min_limit"), rs.getDouble("max_limit"));

    private static final RowMapper<WorldObjectRow> WORLD_MAPPER = (rs, n) -> new WorldObjectRow(
            rs.getString("id"), rs.getString("frame_id"),
            rs.getDouble("px"), rs.getDouble("py"), rs.getDouble("pz"),
            rs.getDouble("qx"), rs.getDouble("qy"), rs.getDouble("qz"), rs.getDouble("qw"),
            rs.getDouble("sx"), rs.getDouble("sy"), rs.getDouble("sz"));

    private static final RowMapper<AttachedObjectRow> ATTACHED_MAPPER = (rs, n) -> new AttachedObjectRow(
            rs.getString("id"), rs.getString("link_name"),
            rs.getDouble("px"), rs.getDouble("py"), rs.getDouble("pz"),
            rs.getDouble("qx"), rs.getDouble("qy"), rs.getDouble("qz"), rs.getDouble("qw"),
            rs.getDouble("sx"), rs.getDouble("sy"), rs.getDouble("sz"));

    public SceneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前场景版本号。
     */
    public long currentVersion() {
        Long version = jdbc.queryForObject("SELECT version FROM scene_meta WHERE id = 0", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 仅当当前版本等于 expected 时将其推进到 expected+1。
     *
     * @return 实际推进时返回 true；版本不匹配返回 false
     */
    public boolean tryAdvanceVersion(long expected) {
        int updated = jdbc.update(
                "UPDATE scene_meta SET version = ? WHERE id = 0 AND version = ?",
                expected + 1L, expected);
        return updated == 1;
    }

    /**
     * 全部关节状态。
     */
    public List<JointRow> findAllJoints() {
        return jdbc.query("SELECT name, position, min_limit, max_limit FROM robot_joint ORDER BY name",
                JOINT_MAPPER);
    }

    /**
     * 更新关节位置。
     */
    public void updateJointPosition(String name, double position) {
        int updated = jdbc.update("UPDATE robot_joint SET position = ? WHERE name = ?", position, name);
        if (updated != 1) {
            throw new IllegalStateException("joint update affected " + updated + " rows: " + name);
        }
    }

    /**
     * 全部世界碰撞对象。
     */
    public List<WorldObjectRow> findAllWorldObjects() {
        return jdbc.query("SELECT id, frame_id, px, py, pz, qx, qy, qz, qw, sx, sy, sz "
                + "FROM world_object ORDER BY id", WORLD_MAPPER);
    }

    /**
     * 全部附着物。
     */
    public List<AttachedObjectRow> findAllAttachedObjects() {
        return jdbc.query("SELECT id, link_name, px, py, pz, qx, qy, qz, qw, sx, sy, sz "
                + "FROM attached_object ORDER BY id", ATTACHED_MAPPER);
    }

    /**
     * 插入世界碰撞对象。
     */
    public void insertWorldObject(WorldObjectRow row) {
        jdbc.update("INSERT INTO world_object "
                        + "(id, frame_id, px, py, pz, qx, qy, qz, qw, sx, sy, sz) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.frameId(),
                row.x(), row.y(), row.z(),
                row.qx(), row.qy(), row.qz(), row.qw(),
                row.sx(), row.sy(), row.sz());
    }

    /**
     * 清空世界碰撞对象表（事务内配合全量重写使用）。
     */
    public void deleteAllWorldObjects() {
        jdbc.update("DELETE FROM world_object");
    }

    /**
     * 插入附着物。
     */
    public void insertAttachedObject(AttachedObjectRow row) {
        jdbc.update("INSERT INTO attached_object "
                        + "(id, link_name, px, py, pz, qx, qy, qz, qw, sx, sy, sz) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.linkName(),
                row.x(), row.y(), row.z(),
                row.qx(), row.qy(), row.qz(), row.qw(),
                row.sx(), row.sy(), row.sz());
    }

    /**
     * 清空附着物表（事务内配合全量重写使用）。
     */
    public void deleteAllAttachedObjects() {
        jdbc.update("DELETE FROM attached_object");
    }

    /**
     * 全部坐标系名称。
     */
    public Set<String> findAllFrames() {
        return jdbc.queryForList("SELECT name FROM scene_frame", String.class).stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 全部机器人 Link 名称。
     */
    public Set<String> findAllLinks() {
        return jdbc.queryForList("SELECT name FROM robot_link", String.class).stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 按幂等键查询已落地请求。
     */
    public Optional<AppliedRequestRow> findAppliedRequest(String requestId) {
        List<AppliedRequestRow> rows = jdbc.query(
                "SELECT request_id, request_hash, scene_version, response_json "
                        + "FROM applied_request WHERE request_id = ?",
                (rs, n) -> new AppliedRequestRow(rs.getString("request_id"),
                        rs.getString("request_hash"),
                        rs.getLong("scene_version"),
                        rs.getString("response_json")),
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * 记录已落地幂等请求。
     */
    public void insertAppliedRequest(AppliedRequestRow row) {
        jdbc.update("INSERT INTO applied_request (request_id, request_hash, scene_version, response_json) "
                        + "VALUES (?, ?, ?, ?)",
                row.requestId(), row.requestHash(), row.sceneVersion(), row.responseJson());
    }

    /**
     * 追加一条变更事件，version 为变更后的新版本号。
     *
     * @param kind OBJECT 或 JOINT
     */
    public void insertEvent(long version, String kind, String targetId) {
        jdbc.update("INSERT INTO scene_event (version, kind, target_id) VALUES (?, ?, ?)",
                version, kind, targetId);
    }

    /**
     * 查询 version 严格大于 since 的变更目标 ID（去重、升序）。
     *
     * @param kind OBJECT 或 JOINT
     */
    public List<String> findChangedTargetsSince(long since, String kind) {
        return jdbc.queryForList(
                "SELECT DISTINCT target_id FROM scene_event WHERE version > ? AND kind = ? ORDER BY target_id",
                String.class, since, kind);
    }

    /**
     * 保存某版本的不可变快照 JSON。
     */
    public void insertSnapshot(long version, String payloadJson) {
        jdbc.update("INSERT INTO scene_snapshot (version, payload) VALUES (?, ?)", version, payloadJson);
    }

    /**
     * 读取某版本快照 JSON，不存在时为空。
     */
    public Optional<String> findSnapshot(long version) {
        List<String> payloads = jdbc.queryForList(
                "SELECT payload FROM scene_snapshot WHERE version = ?", String.class, version);
        return payloads.stream().findFirst();
    }
}
