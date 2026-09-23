package com.example.starter.scene.db;

import com.example.starter.scene.domain.SceneState;
import com.example.starter.scene.exception.SceneConflictException;
import com.example.starter.scene.model.SceneApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * 基于 H2（MODE=MySQL）的 Planning Scene 数据访问实现。
 *
 * <p>提交时通过 {@code SELECT ... FROM scene_meta WHERE id=1 FOR UPDATE} 悲观行锁串行化所有写事务：
 * 版本复核、幂等复核、当前态覆盖与新版本快照写入在同一事务同一行锁内完成，保证
 * 同版本并发提交只有一方成功、失败批次零写入。历史快照只追加不修改，读取快照不触碰
 * scene_meta 行锁，因此构建快照不会长期占用写锁、写入可继续提交。</p>
 */
@Repository
public class JdbcPlanningSceneRepository implements PlanningSceneRepository {

    public static final int META_ROW_ID = 1;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcPlanningSceneRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbc = jdbcTemplate;
        this.clock = clock;
    }

    private static final RowMapper<SceneApi.ObjectSnapshot> OBJECT_MAPPER = (rs, n) -> new SceneApi.ObjectSnapshot(
            rs.getString("object_id"),
            rs.getString("frame_id"),
            rs.getString("shape"),
            rs.getDouble("size_x"),
            rs.getDouble("size_y"),
            rs.getDouble("size_z"),
            rs.getDouble("pos_x"),
            rs.getDouble("pos_y"),
            rs.getDouble("pos_z"),
            rs.getDouble("qx"),
            rs.getDouble("qy"),
            rs.getDouble("qz"),
            rs.getDouble("qw"),
            rs.getString("attached_link"));

    private static final RowMapper<SceneApi.JointSnapshot> JOINT_MAPPER = (rs, n) ->
            new SceneApi.JointSnapshot(rs.getString("joint_name"), rs.getDouble("val"));

    @Override
    @Transactional(readOnly = true)
    public boolean isInitialized() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_meta WHERE id = ? AND initialized = TRUE",
                Integer.class, META_ROW_ID);
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public long initializeRobotModel(SceneApi.RobotModelRequest request) {
        // 锁定唯一 meta 行，串行化并发初始化
        Boolean initialized = jdbc.queryForObject(
                "SELECT initialized FROM scene_meta WHERE id = ? FOR UPDATE",
                Boolean.class, META_ROW_ID);
        if (Boolean.TRUE.equals(initialized)) {
            throw new SceneConflictException("ROBOT_MODEL_EXISTS",
                    "robot model has already been initialized", 0, 0,
                    List.of(), List.of(), false);
        }
        SceneApi.PoseData pose = request.initialPose();
        jdbc.update("""
                        UPDATE scene_meta SET initialized = TRUE,
                            rp_frame = ?, rp_x = ?, rp_y = ?, rp_z = ?,
                            rp_qx = ?, rp_qy = ?, rp_qz = ?, rp_qw = ?
                        WHERE id = ? AND initialized = FALSE
                        """,
                poseField(pose, SceneApi.PoseData::frameId),
                poseValue(pose, SceneApi.PoseData::x), poseValue(pose, SceneApi.PoseData::y),
                poseValue(pose, SceneApi.PoseData::z), poseValue(pose, SceneApi.PoseData::qx),
                poseValue(pose, SceneApi.PoseData::qy), poseValue(pose, SceneApi.PoseData::qz),
                poseValue(pose, SceneApi.PoseData::qw),
                META_ROW_ID);

        for (String link : request.links()) {
            jdbc.update("INSERT INTO scene_link (link_name) VALUES (?)", link);
        }
        for (SceneApi.JointSpec joint : request.joints()) {
            jdbc.update("INSERT INTO scene_joint_def (joint_name, min_val, max_val) VALUES (?, ?, ?)",
                    joint.name(), joint.min(), joint.max());
            jdbc.update("INSERT INTO scene_joint_current (joint_name, val) VALUES (?, ?)",
                    joint.name(), joint.initialValue());
            jdbc.update("INSERT INTO scene_joint_snapshot (version, joint_name, val) VALUES (0, ?, ?)",
                    joint.name(), joint.initialValue());
        }
        jdbc.update("""
                        INSERT INTO scene_version (version, request_id, created_ts,
                            rp_frame, rp_x, rp_y, rp_z, rp_qx, rp_qy, rp_qz, rp_qw)
                        VALUES (0, 'bootstrap', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                Timestamp.from(clock.instant()), poseField(pose, SceneApi.PoseData::frameId),
                poseValue(pose, SceneApi.PoseData::x), poseValue(pose, SceneApi.PoseData::y),
                poseValue(pose, SceneApi.PoseData::z), poseValue(pose, SceneApi.PoseData::qx),
                poseValue(pose, SceneApi.PoseData::qy), poseValue(pose, SceneApi.PoseData::qz),
                poseValue(pose, SceneApi.PoseData::qw));
        return 0;
    }

    @Override
    @Transactional(readOnly = true)
    public long currentVersion() {
        List<Long> versions = jdbc.query(
                "SELECT scene_version FROM scene_meta WHERE id = ?",
                (rs, n) -> rs.getLong(1), META_ROW_ID);
        return versions.isEmpty() ? -1L : versions.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public SceneState loadCurrent() {
        List<Map<String, Object>> metaRows = jdbc.queryForList(
                "SELECT * FROM scene_meta WHERE id = ?", META_ROW_ID);
        if (metaRows.isEmpty()) {
            throw new SceneConflictException("ROBOT_MODEL_MISSING",
                    "robot model has not been initialized", 0, -1, List.of(), List.of(), false);
        }
        Map<String, Object> meta = metaRows.get(0);
        long version = ((Number) meta.get("scene_version")).longValue();

        Map<String, Double> joints = new LinkedHashMap<>();
        for (SceneApi.JointSnapshot joint : jdbc.query(
                "SELECT joint_name, val FROM scene_joint_current ORDER BY joint_name", JOINT_MAPPER)) {
            joints.put(joint.name(), joint.value());
        }

        Map<String, SceneApi.ObjectSnapshot> objects = new LinkedHashMap<>();
        for (SceneApi.ObjectSnapshot object : jdbc.query(
                "SELECT * FROM scene_object_current ORDER BY object_id", OBJECT_MAPPER)) {
            objects.put(object.objectId(), object);
        }
        return new SceneState(version, readPose(meta), joints, objects);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> loadLinkNames() {
        return jdbc.query("SELECT link_name FROM scene_link ORDER BY link_name",
                (rs, n) -> rs.getString(1));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, SceneApi.JointSpec> loadJointDefs() {
        Map<String, SceneApi.JointSpec> defs = new LinkedHashMap<>();
        jdbc.query("SELECT joint_name, min_val, max_val FROM scene_joint_def ORDER BY joint_name",
                (rs) -> {
                    String name = rs.getString("joint_name");
                    defs.put(name, new SceneApi.JointSpec(name,
                            rs.getDouble("min_val"), rs.getDouble("max_val"), 0d));
                });
        return defs;
    }

    @Override
    @Transactional(readOnly = true)
    public IdempotentRecord findIdempotent(String requestId) {
        List<IdempotentRecord> records = jdbc.query(
                "SELECT request_id, version, payload_hash FROM scene_idempotent_request WHERE request_id = ?",
                (rs, n) -> new IdempotentRecord(rs.getString(1), rs.getLong(2), rs.getString(3)),
                requestId);
        return records.isEmpty() ? null : records.get(0);
    }

    @Override
    @Transactional
    public WriteLock acquireWriteLock(long expectedVersion, String requestId, String payloadHash) {
        Long lockedVersion = jdbc.queryForObject(
                "SELECT scene_version FROM scene_meta WHERE id = ? FOR UPDATE",
                Long.class, META_ROW_ID);
        long current = lockedVersion == null ? -1L : lockedVersion;

        IdempotentRecord prior = findIdempotent(requestId);
        if (prior != null) {
            if (prior.payloadHash().equals(payloadHash)) {
                return new WriteLock(WriteLock.Status.REPLAY, prior.version());
            }
            throw new SceneConflictException("IDEMPOTENT_PARAM_MISMATCH",
                    "requestId '" + requestId + "' was already used with different parameters",
                    expectedVersion, current, List.of(), List.of(), false);
        }

        if (current != expectedVersion) {
            throw new SceneConflictException("VERSION_CONFLICT",
                    "expected version " + expectedVersion + " but current version is " + current,
                    expectedVersion, current,
                    changedObjectIds(expectedVersion, current),
                    changedJointNames(expectedVersion, current),
                    robotPoseChanged(expectedVersion, current));
        }
        return new WriteLock(WriteLock.Status.OK, current);
    }

    @Override
    @Transactional
    public long persistNewVersion(String requestId, String payloadHash, SceneState newState) {
        long newVersion = newState.version();
        overwriteCurrent(newState);
        writeVersionRow(newVersion, requestId, newState);
        writeSnapshots(newVersion, newState);
        jdbc.update("INSERT INTO scene_idempotent_request (request_id, version, payload_hash, created_ts) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, newVersion, payloadHash, Timestamp.from(clock.instant()));
        return newVersion;
    }

    private void overwriteCurrent(SceneState state) {
        jdbc.update("UPDATE scene_meta SET scene_version = ?, rp_frame = ?, rp_x = ?, rp_y = ?, rp_z = ?, "
                        + "rp_qx = ?, rp_qy = ?, rp_qz = ?, rp_qw = ? WHERE id = ?",
                state.version(),
                poseField(state.robotPose(), SceneApi.PoseData::frameId),
                poseValue(state.robotPose(), SceneApi.PoseData::x),
                poseValue(state.robotPose(), SceneApi.PoseData::y),
                poseValue(state.robotPose(), SceneApi.PoseData::z),
                poseValue(state.robotPose(), SceneApi.PoseData::qx),
                poseValue(state.robotPose(), SceneApi.PoseData::qy),
                poseValue(state.robotPose(), SceneApi.PoseData::qz),
                poseValue(state.robotPose(), SceneApi.PoseData::qw),
                META_ROW_ID);

        jdbc.update("DELETE FROM scene_object_current");
        for (SceneApi.ObjectSnapshot object : state.objects().values()) {
            insertObjectCurrent(object);
        }
        jdbc.update("DELETE FROM scene_joint_current");
        for (Map.Entry<String, Double> joint : state.joints().entrySet()) {
            jdbc.update("INSERT INTO scene_joint_current (joint_name, val) VALUES (?, ?)",
                    joint.getKey(), joint.getValue());
        }
    }

    private void writeVersionRow(long version, String requestId, SceneState state) {
        SceneApi.PoseData pose = state.robotPose();
        jdbc.update("""
                        INSERT INTO scene_version (version, request_id, created_ts,
                            rp_frame, rp_x, rp_y, rp_z, rp_qx, rp_qy, rp_qz, rp_qw)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                version, requestId, Timestamp.from(clock.instant()),
                poseField(pose, SceneApi.PoseData::frameId),
                poseValue(pose, SceneApi.PoseData::x), poseValue(pose, SceneApi.PoseData::y),
                poseValue(pose, SceneApi.PoseData::z), poseValue(pose, SceneApi.PoseData::qx),
                poseValue(pose, SceneApi.PoseData::qy), poseValue(pose, SceneApi.PoseData::qz),
                poseValue(pose, SceneApi.PoseData::qw));
    }

    private void writeSnapshots(long version, SceneState state) {
        for (SceneApi.ObjectSnapshot object : state.objects().values()) {
            insertObjectSnapshot(version, object);
        }
        for (Map.Entry<String, Double> joint : state.joints().entrySet()) {
            jdbc.update("INSERT INTO scene_joint_snapshot (version, joint_name, val) VALUES (?, ?, ?)",
                    version, joint.getKey(), joint.getValue());
        }
    }

    private void insertObjectCurrent(SceneApi.ObjectSnapshot o) {
        jdbc.update("""
                        INSERT INTO scene_object_current (object_id, frame_id, shape,
                            size_x, size_y, size_z, pos_x, pos_y, pos_z, qx, qy, qz, qw, attached_link)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                o.objectId(), o.frameId(), o.shape(), o.sizeX(), o.sizeY(), o.sizeZ(),
                o.x(), o.y(), o.z(), o.qx(), o.qy(), o.qz(), o.qw(), o.attachedLink());
    }

    private void insertObjectSnapshot(long version, SceneApi.ObjectSnapshot o) {
        jdbc.update("""
                        INSERT INTO scene_object_snapshot (version, object_id, frame_id, shape,
                            size_x, size_y, size_z, pos_x, pos_y, pos_z, qx, qy, qz, qw, attached_link)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                version, o.objectId(), o.frameId(), o.shape(), o.sizeX(), o.sizeY(), o.sizeZ(),
                o.x(), o.y(), o.z(), o.qx(), o.qy(), o.qz(), o.qw(), o.attachedLink());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean snapshotExists(long version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scene_version WHERE version = ?", Integer.class, version);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public SceneApi.SnapshotResponse loadSnapshot(long version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM scene_version WHERE version = ?", version);
        if (rows.isEmpty()) {
            return null;
        }
        SceneApi.PoseData pose = readPose(rows.get(0));
        List<SceneApi.JointSnapshot> joints = jdbc.query(
                "SELECT joint_name, val FROM scene_joint_snapshot WHERE version = ? ORDER BY joint_name",
                JOINT_MAPPER, version);
        List<SceneApi.ObjectSnapshot> objects = jdbc.query(
                "SELECT * FROM scene_object_snapshot WHERE version = ? ORDER BY object_id",
                OBJECT_MAPPER, version);
        return new SceneApi.SnapshotResponse(version, pose, joints, objects);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneApi.ObjectSnapshot> loadObjectSnapshot(long version) {
        return jdbc.query("SELECT * FROM scene_object_snapshot WHERE version = ? ORDER BY object_id",
                OBJECT_MAPPER, version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneApi.JointSnapshot> loadJointSnapshot(long version) {
        return jdbc.query("SELECT joint_name, val FROM scene_joint_snapshot WHERE version = ? "
                + "ORDER BY joint_name", JOINT_MAPPER, version);
    }

    @Override
    @Transactional(readOnly = true)
    public SceneApi.PoseData loadRobotPose(long version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rp_frame, rp_x, rp_y, rp_z, rp_qx, rp_qy, rp_qz, rp_qw "
                        + "FROM scene_version WHERE version = ?", version);
        return rows.isEmpty() ? null : readPose(rows.get(0));
    }

    /** 计算 expectedVersion -> currentVersion 之间发生变化（增/删/改，含附着切换）的对象 ID。 */
    private List<String> changedObjectIds(long expectedVersion, long currentVersion) {
        Map<String, SceneApi.ObjectSnapshot> before = indexObjects(loadObjectSnapshot(expectedVersion));
        Map<String, SceneApi.ObjectSnapshot> after = indexObjects(loadObjectSnapshot(currentVersion));
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, SceneApi.ObjectSnapshot> entry : after.entrySet()) {
            SceneApi.ObjectSnapshot old = before.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String id : before.keySet()) {
            if (!after.containsKey(id)) {
                changed.add(id);
            }
        }
        return changed;
    }

    /** 计算两个版本之间角度发生变化的关节名（模型固定，关节集合不会增删）。 */
    private List<String> changedJointNames(long expectedVersion, long currentVersion) {
        Map<String, Double> before = new LinkedHashMap<>();
        for (SceneApi.JointSnapshot j : loadJointSnapshot(expectedVersion)) {
            before.put(j.name(), j.value());
        }
        List<String> changed = new ArrayList<>();
        for (SceneApi.JointSnapshot j : loadJointSnapshot(currentVersion)) {
            Double oldValue = before.get(j.name());
            if (oldValue == null || Double.compare(oldValue, j.value()) != 0) {
                changed.add(j.name());
            }
        }
        return changed;
    }

    private boolean robotPoseChanged(long expectedVersion, long currentVersion) {
        SceneApi.PoseData before = loadRobotPose(expectedVersion);
        SceneApi.PoseData after = loadRobotPose(currentVersion);
        if (before == null) {
            return after != null;
        }
        return !before.equals(after);
    }

    private Map<String, SceneApi.ObjectSnapshot> indexObjects(List<SceneApi.ObjectSnapshot> objects) {
        Map<String, SceneApi.ObjectSnapshot> map = new LinkedHashMap<>();
        for (SceneApi.ObjectSnapshot object : objects) {
            map.put(object.objectId(), object);
        }
        return map;
    }

    private SceneApi.PoseData readPose(Map<String, Object> row) {
        Object frame = row.get("rp_frame");
        if (frame == null) {
            return null;
        }
        return new SceneApi.PoseData((String) frame,
                number(row, "rp_x"), number(row, "rp_y"), number(row, "rp_z"),
                number(row, "rp_qx"), number(row, "rp_qy"), number(row, "rp_qz"),
                number(row, "rp_qw"));
    }

    private static double number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }

    private static String poseField(SceneApi.PoseData pose, Function<SceneApi.PoseData, String> getter) {
        return pose == null ? null : getter.apply(pose);
    }

    private static Double poseValue(SceneApi.PoseData pose, ToDoubleFunction<SceneApi.PoseData> getter) {
        return pose == null ? null : getter.applyAsDouble(pose);
    }
}
