package com.example.starter.slam.store;

import com.example.starter.slam.domain.EdgeStatus;
import com.example.starter.slam.domain.EdgeType;
import com.example.starter.slam.optim.EdgeSnapshot;
import com.example.starter.slam.optim.FrameSnapshot;
import com.example.starter.slam.po.EdgePO;
import com.example.starter.slam.po.KeyFramePO;
import com.example.starter.slam.po.PoseGraphPO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 位姿图（图元数据、关键帧、边）数据访问。
 */
@Repository
public class GraphStore {

    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public GraphStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取图；不存在则以版本 0 创建。并发首次创建时后者复用已存在行。
     */
    public PoseGraphPO getOrCreateGraph(String graphId) {
        PoseGraphPO existing = findGraph(graphId);
        if (existing != null) {
            return existing;
        }
        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
                    "INSERT INTO pose_graph (graph_id, current_version, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    graphId, 0L, Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 并发创建，读取已存在行
        }
        return findGraph(graphId);
    }

    /**
     * 读取当前有效图版本；图不存在返回 0。
     */
    public long currentVersion(String graphId) {
        PoseGraphPO graph = findGraph(graphId);
        return graph == null ? 0L : graph.currentVersion();
    }

    /**
     * 在事务内锁定图行（SELECT ... FOR UPDATE），串行化同一图上的优化提交与合并判定。
     * 图不存在时返回 null。
     */
    public PoseGraphPO lockGraph(String graphId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT graph_id, current_version, created_at, updated_at FROM pose_graph "
                            + "WHERE graph_id = ? FOR UPDATE", GRAPH_MAPPER, graphId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public KeyFramePO findFrame(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, graph_id, source, version, pose_json, content_hash, created_at "
                            + "FROM key_frame WHERE id = ?", FRAME_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void insertFrame(KeyFramePO frame) {
        jdbcTemplate.update(
                "INSERT INTO key_frame (id, graph_id, source, version, pose_json, content_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                frame.id(), frame.graphId(), frame.source(), frame.version(),
                frame.poseJson(), frame.contentHash(), Timestamp.from(frame.createdAt()));
    }

    public EdgePO findEdgeById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, request_id, graph_id, source, version, type, from_frame_id, to_frame_id, "
                            + "measurement_json, information_json, score, residual, status, reject_reason, "
                            + "content_hash, created_at FROM edge WHERE id = ?", EDGE_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public EdgePO findEdgeByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, request_id, graph_id, source, version, type, from_frame_id, to_frame_id, "
                            + "measurement_json, information_json, score, residual, status, reject_reason, "
                            + "content_hash, created_at FROM edge WHERE request_id = ?", EDGE_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void insertEdge(EdgePO edge) {
        jdbcTemplate.update(
                "INSERT INTO edge (id, request_id, graph_id, source, version, type, from_frame_id, to_frame_id, "
                        + "measurement_json, information_json, score, residual, status, reject_reason, "
                        + "content_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                edge.id(), edge.requestId(), edge.graphId(), edge.source(), edge.version(),
                edge.type().name(), edge.fromFrameId(), edge.toFrameId(),
                edge.measurementJson(), edge.informationJson(),
                edge.score(), edge.residual(), edge.status().name(), edge.rejectReason(),
                edge.contentHash(), Timestamp.from(edge.createdAt()));
    }

    /**
     * 有效图版本递增 1 并返回新版本。REJECTED 回环不得调用本方法。
     */
    public long incrementVersion(String graphId) {
        jdbcTemplate.update(
                "UPDATE pose_graph SET current_version = current_version + 1, updated_at = ? WHERE graph_id = ?",
                Timestamp.from(Instant.now()), graphId);
        return currentVersion(graphId);
    }

    /**
     * 加载图上全部关键帧快照（按 ID 排序）。
     */
    public List<FrameSnapshot> loadFrames(String graphId) {
        return jdbcTemplate.query(
                "SELECT id, source, version, pose_json FROM key_frame WHERE graph_id = ? ORDER BY id",
                (rs, rowNum) -> new FrameSnapshot(
                        rs.getString("id"),
                        rs.getString("source"),
                        rs.getLong("version"),
                        readDoubleList(rs.getString("pose_json"))),
                graphId);
    }

    /**
     * 加载图上全部 VALID 边快照（按 ID 排序，排除 REJECTED 审计记录）。
     */
    public List<EdgeSnapshot> loadValidEdges(String graphId) {
        return jdbcTemplate.query(
                "SELECT id, source, version, type, from_frame_id, to_frame_id, measurement_json, information_json "
                        + "FROM edge WHERE graph_id = ? AND status = 'VALID' ORDER BY id",
                (rs, rowNum) -> new EdgeSnapshot(
                        rs.getString("id"),
                        rs.getString("source"),
                        rs.getLong("version"),
                        EdgeType.valueOf(rs.getString("type")),
                        rs.getString("from_frame_id"),
                        rs.getString("to_frame_id"),
                        readDoubleList(rs.getString("measurement_json")),
                        readDoubleList(rs.getString("information_json"))),
                graphId);
    }

    /**
     * 统计有效边数量，供结果版本保护判断"基于更多有效边的新版本"。
     */
    public int countValidEdges(String graphId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edge WHERE graph_id = ? AND status = 'VALID'", Integer.class, graphId);
        return count == null ? 0 : count;
    }

    private PoseGraphPO findGraph(String graphId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT graph_id, current_version, created_at, updated_at FROM pose_graph WHERE graph_id = ?",
                    GRAPH_MAPPER, graphId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private List<Double> readDoubleList(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, DOUBLE_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse stored json: " + json, ex);
        }
    }

    private static final RowMapper<PoseGraphPO> GRAPH_MAPPER = (rs, rowNum) -> new PoseGraphPO(
            rs.getString("graph_id"),
            rs.getLong("current_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<KeyFramePO> FRAME_MAPPER = (rs, rowNum) -> new KeyFramePO(
            rs.getString("id"),
            rs.getString("graph_id"),
            rs.getString("source"),
            rs.getLong("version"),
            rs.getString("pose_json"),
            rs.getString("content_hash"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<EdgePO> EDGE_MAPPER = (rs, rowNum) -> new EdgePO(
            rs.getString("id"),
            rs.getString("request_id"),
            rs.getString("graph_id"),
            rs.getString("source"),
            rs.getLong("version"),
            EdgeType.valueOf(rs.getString("type")),
            rs.getString("from_frame_id"),
            rs.getString("to_frame_id"),
            rs.getString("measurement_json"),
            rs.getString("information_json"),
            (Double) rs.getObject("score"),
            (Double) rs.getObject("residual"),
            EdgeStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"),
            rs.getString("content_hash"),
            rs.getTimestamp("created_at").toInstant());
}
