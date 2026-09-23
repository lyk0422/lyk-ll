package com.example.starter.slam.repo;

import com.example.starter.slam.domain.Keyframe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 关键帧表数据访问。
 */
@Repository
public class KeyframeRepository {

    public static final RowMapper<Keyframe> ROW_MAPPER = (rs, n) -> new Keyframe(
            rs.getString("id"),
            rs.getString("source"),
            rs.getInt("version"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("theta"),
            rs.getString("measurement_json"),
            rs.getString("request_id"),
            rs.getString("content_hash"),
            rs.getInt("graph_version"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public KeyframeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Keyframe findById(String id) {
        List<Keyframe> rows = jdbc.query(
                "SELECT * FROM keyframes WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Keyframe findByRequestId(String requestId) {
        List<Keyframe> rows = jdbc.query(
                "SELECT * FROM keyframes WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(Keyframe keyframe) {
        jdbc.update("""
                        INSERT INTO keyframes
                            (id, source, version, x, y, theta, measurement_json,
                             request_id, content_hash, graph_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                keyframe.id(),
                keyframe.source(),
                keyframe.version(),
                keyframe.x(),
                keyframe.y(),
                keyframe.theta(),
                keyframe.measurementJson(),
                keyframe.requestId(),
                keyframe.contentHash(),
                keyframe.graphVersion(),
                Timestamp.from(keyframe.createdAt())
        );
    }

    /**
     * 返回给定图版本时刻已存在的全部关键帧（graph_version &lt;= graphVersion），按写入顺序排列。
     */
    public List<Keyframe> findAsOfVersion(int graphVersion) {
        return jdbc.query(
                "SELECT * FROM keyframes WHERE graph_version <= ? ORDER BY graph_version, id",
                ROW_MAPPER, graphVersion);
    }
}
