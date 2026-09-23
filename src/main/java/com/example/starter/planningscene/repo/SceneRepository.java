package com.example.starter.planningscene.repo;

import com.example.starter.planningscene.domain.JointState;
import com.example.starter.planningscene.domain.ObjectState;
import com.example.starter.planningscene.domain.ObjectType;
import com.example.starter.planningscene.domain.Pose;
import com.example.starter.planningscene.domain.RobotModel;
import com.example.starter.planningscene.domain.SceneSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Planning Scene 数据访问层：版本化快照表与静态机器人模型的读写。
 *
 * <p>每个版本在 {@code scene_robot_state/scene_joint/scene_object} 中保存全量快照行，
 * 历史读取只需按版本号查询，不触碰写入路径。
 */
@Repository
public class SceneRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SceneRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 读取静态机器人模型（Link 坐标系与关节范围）。 */
    public RobotModel loadRobotModel() {
        Set<String> links = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT link_name FROM robot_link", String.class));
        Map<String, double[]> joints = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT joint_name, lower_bound, upper_bound FROM robot_joint",
                rs -> {
                    String name = rs.getString("joint_name");
                    joints.put(name, new double[]{rs.getDouble("lower_bound"), rs.getDouble("upper_bound")});
                });
        return new RobotModel(links, joints);
    }

    /** 读取当前版本号。 */
    public long currentVersion() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT current_version FROM scene_meta WHERE id = 0", Long.class);
        return version == null ? 0L : version;
    }

    /** 读取指定版本快照；版本不存在返回空。 */
    public Optional<SceneSnapshot> loadSnapshot(long version) {
        List<Pose> robotPoses = jdbcTemplate.query(
                "SELECT pos_x, pos_y, pos_z, qx, qy, qz, qw FROM scene_robot_state WHERE version = ?",
                (rs, n) -> new Pose(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7)),
                version);
        if (robotPoses.isEmpty()) {
            return Optional.empty();
        }
        Map<String, JointState> joints = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT joint_name, position, lower_bound, upper_bound FROM scene_joint WHERE version = ?",
                rs -> {
                    String name = rs.getString("joint_name");
                    joints.put(name, new JointState(name, rs.getDouble("position"),
                            rs.getDouble("lower_bound"), rs.getDouble("upper_bound")));
                },
                version);
        Map<String, ObjectState> objects = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT object_id, object_type, frame_id, link_id, geometry_json, pose_json, touch_links_csv "
                        + "FROM scene_object WHERE version = ? ORDER BY object_id",
                new ObjectRowMapper(objectMapper),
                version).forEach(o -> objects.put(o.objectId(), o));
        return Optional.of(new SceneSnapshot(version, robotPoses.get(0), joints, objects));
    }

    /**
     * 在当前事务内原子推进版本：用条件 UPDATE 锁定 scene_meta 单行作为乐观并发闸门。
     *
     * <p>两个携带相同 expectedVersion 的并发事务中，先拿到行锁的一方提交后，
     * 另一方的条件 UPDATE 影响 0 行而失败，不会写入任何快照行。
     *
     * @return 新版本号；当前版本已不等于 expectedVersion 时返回空
     */
    @Transactional
    public Optional<Long> tryAdvance(long expectedVersion, Pose robotPose,
                                     List<JointState> joints, List<ObjectState> objects) {
        long newVersion = expectedVersion + 1;
        int updated = jdbcTemplate.update(
                "UPDATE scene_meta SET current_version = ? WHERE id = 0 AND current_version = ?",
                newVersion, expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        jdbcTemplate.update("INSERT INTO scene_robot_state "
                        + "(version, pos_x, pos_y, pos_z, qx, qy, qz, qw) VALUES (?,?,?,?,?,?,?,?)",
                newVersion, robotPose.x(), robotPose.y(), robotPose.z(),
                robotPose.qx(), robotPose.qy(), robotPose.qz(), robotPose.qw());
        jdbcTemplate.batchUpdate(
                "INSERT INTO scene_joint (version, joint_name, position, lower_bound, upper_bound) "
                        + "VALUES (?,?,?,?,?)",
                joints, joints.size(), (ps, joint) -> {
                    ps.setLong(1, newVersion);
                    ps.setString(2, joint.name());
                    ps.setDouble(3, joint.position());
                    ps.setDouble(4, joint.lowerBound());
                    ps.setDouble(5, joint.upperBound());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO scene_object "
                        + "(version, object_id, object_type, frame_id, link_id, geometry_json, pose_json, "
                        + "touch_links_csv) VALUES (?,?,?,?,?,?,?,?)",
                objects, Math.max(1, objects.size()), (ps, obj) -> {
                    ps.setLong(1, newVersion);
                    ps.setString(2, obj.objectId());
                    ps.setString(3, obj.type().name());
                    if (obj.frameId() == null) {
                        ps.setNull(4, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(4, obj.frameId());
                    }
                    if (obj.linkId() == null) {
                        ps.setNull(5, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(5, obj.linkId());
                    }
                    ps.setString(6, obj.geometry());
                    ps.setString(7, writePose(obj.pose()));
                    String touch = obj.touchLinks().isEmpty() ? null : String.join(",", obj.touchLinks());
                    if (touch == null) {
                        ps.setNull(8, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(8, touch);
                    }
                });
        return Optional.of(newVersion);
    }

    /** 幂等成功记录查询。 */
    public Optional<IdempotentRecord> findRequest(String requestId) {
        List<IdempotentRecord> records = jdbcTemplate.query(
                "SELECT payload_hash, http_status, response_json FROM scene_request WHERE request_id = ?",
                (rs, n) -> new IdempotentRecord(rs.getString("payload_hash"),
                        rs.getInt("http_status"), rs.getString("response_json")),
                requestId);
        return records.stream().findFirst();
    }

    /** 幂等成功记录写入（与版本推进处于同一事务）。 */
    public void saveRequest(String requestId, String payloadHash, int httpStatus,
                            String responseJson, long now) {
        jdbcTemplate.update("INSERT INTO scene_request "
                        + "(request_id, payload_hash, http_status, response_json, created_at) "
                        + "VALUES (?,?,?,?,?)",
                requestId, payloadHash, httpStatus, responseJson, now);
    }

    /** 幂等首次请求记录。 */
    public record IdempotentRecord(String payloadHash, int httpStatus, String responseJson) {
    }

    private String writePose(Pose pose) {
        try {
            return objectMapper.writeValueAsString(new double[]{
                    pose.x(), pose.y(), pose.z(), pose.qx(), pose.qy(), pose.qz(), pose.qw()});
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize pose", e);
        }
    }

    /** scene_object 行映射器。 */
    private static final class ObjectRowMapper implements RowMapper<ObjectState> {
        private final ObjectMapper objectMapper;

        private ObjectRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ObjectState mapRow(ResultSet rs, int rowNum) throws SQLException {
            String touchCsv = rs.getString("touch_links_csv");
            List<String> touchLinks = touchCsv == null || touchCsv.isBlank()
                    ? List.of()
                    : Arrays.asList(touchCsv.split(","));
            String poseJson = rs.getString("pose_json");
            Pose pose;
            if (poseJson == null || poseJson.isBlank()) {
                pose = Pose.origin();
            } else {
                try {
                    double[] v = objectMapper.readValue(poseJson, double[].class);
                    pose = new Pose(v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
                } catch (Exception e) {
                    throw new SQLException("failed to read pose json", e);
                }
            }
            return new ObjectState(rs.getString("object_id"),
                    ObjectType.valueOf(rs.getString("object_type")),
                    rs.getString("frame_id"), rs.getString("link_id"),
                    rs.getString("geometry_json"), pose, touchLinks);
        }
    }
}
