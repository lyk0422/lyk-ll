package com.example.starter.slam.repo;

import com.example.starter.slam.domain.GraphEdge;
import com.example.starter.slam.model.EdgeStatus;
import com.example.starter.slam.model.EdgeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 图边表数据访问（含 VALID 有效边与 REJECTED 回环审计记录）。
 */
@Repository
public class EdgeRepository {

    public static final RowMapper<GraphEdge> ROW_MAPPER = (rs, n) -> new GraphEdge(
            rs.getString("id"),
            EdgeType.valueOf(rs.getString("type")),
            rs.getString("source"),
            rs.getInt("version"),
            rs.getString("from_keyframe_id"),
            rs.getString("to_keyframe_id"),
            rs.getDouble("dx"),
            rs.getDouble("dy"),
            rs.getDouble("dtheta"),
            rs.getString("covariance_json"),
            (Double) rs.getObject("score"),
            (Double) rs.getObject("residual"),
            rs.getString("request_id"),
            rs.getString("content_hash"),
            EdgeStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"),
            rs.getInt("graph_version"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public EdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GraphEdge findById(String id) {
        List<GraphEdge> rows = jdbc.query(
                "SELECT * FROM edges WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public GraphEdge findByRequestId(String requestId) {
        List<GraphEdge> rows = jdbc.query(
                "SELECT * FROM edges WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(GraphEdge edge) {
        jdbc.update("""
                        INSERT INTO edges
                            (id, type, source, version, from_keyframe_id, to_keyframe_id,
                             dx, dy, dtheta, covariance_json, score, residual, request_id,
                             content_hash, status, reject_reason, graph_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                edge.id(),
                edge.type().name(),
                edge.source(),
                edge.version(),
                edge.fromKeyframeId(),
                edge.toKeyframeId(),
                edge.dx(),
                edge.dy(),
                edge.dtheta(),
                edge.covarianceJson(),
                edge.score(),
                edge.residual(),
                edge.requestId(),
                edge.contentHash(),
                edge.status().name(),
                edge.rejectReason(),
                edge.graphVersion(),
                Timestamp.from(edge.createdAt())
        );
    }

    /**
     * 返回给定图版本时刻的全部有效边（不含 REJECTED 审计记录），按写入顺序排列。
     */
    public List<GraphEdge> findValidAsOfVersion(int graphVersion) {
        return jdbc.query("""
                        SELECT * FROM edges
                        WHERE status = 'VALID' AND graph_version <= ?
                        ORDER BY graph_version, id
                        """,
                ROW_MAPPER, graphVersion);
    }
}
