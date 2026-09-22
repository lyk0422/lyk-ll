package com.example.starter.tf2.persistence;

import com.example.starter.tf2.domain.EdgeType;
import com.example.starter.tf2.domain.Quat;
import com.example.starter.tf2.domain.Vec3;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 变换样本数据访问层。静态边与动态样本同表存储，以 edge_type 区分。
 */
@Repository
public class TransformSampleRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransformSampleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS = """
            SELECT id, parent_frame, child_frame, edge_type, stamp_nanos,
                   tx, ty, tz, qx, qy, qz, qw, source, created_at
            FROM tf_transform_sample
            """;

    private static final RowMapper<TransformSample> MAPPER = (rs, n) -> new TransformSample(
            rs.getLong("id"),
            rs.getString("parent_frame"),
            rs.getString("child_frame"),
            EdgeType.valueOf(rs.getString("edge_type")),
            rs.getLong("stamp_nanos"),
            new Vec3(rs.getDouble("tx"), rs.getDouble("ty"), rs.getDouble("tz")),
            new Quat(rs.getDouble("qx"), rs.getDouble("qy"), rs.getDouble("qz"), rs.getDouble("qw")),
            rs.getString("source"),
            rs.getTimestamp("created_at").toInstant());

    /** 插入一条样本，返回生成主键。 */
    public long insert(TransformSample sample) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO tf_transform_sample
                        (parent_frame, child_frame, edge_type, stamp_nanos,
                         tx, ty, tz, qx, qy, qz, qw, source, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, sample.parentFrame());
            ps.setString(2, sample.childFrame());
            ps.setString(3, sample.type().name());
            ps.setLong(4, sample.stampNanos());
            ps.setDouble(5, sample.translation().x());
            ps.setDouble(6, sample.translation().y());
            ps.setDouble(7, sample.translation().z());
            ps.setDouble(8, sample.rotation().x());
            ps.setDouble(9, sample.rotation().y());
            ps.setDouble(10, sample.rotation().z());
            ps.setDouble(11, sample.rotation().w());
            ps.setString(12, sample.source());
            ps.setTimestamp(13, Timestamp.from(sample.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 查询指定父子关系、类型、时间戳、来源的已有样本（幂等/冲突判定）。 */
    public List<TransformSample> findExact(String parent, String child, EdgeType type,
                                           long stampNanos, String source) {
        return jdbcTemplate.query(
                COLUMNS + """
                        WHERE parent_frame = ? AND child_frame = ?
                          AND edge_type = ? AND stamp_nanos = ? AND source = ?
                        """,
                MAPPER, parent, child, type.name(), stampNanos, source);
    }

    /** 查询某条动态边的全部样本（按时间戳升序）。 */
    public List<TransformSample> findDynamicSamples(String parent, String child) {
        return jdbcTemplate.query(
                COLUMNS + """
                        WHERE parent_frame = ? AND child_frame = ? AND edge_type = 'DYNAMIC'
                        ORDER BY stamp_nanos ASC
                        """,
                MAPPER, parent, child);
    }

    /** 查询某父子关系的静态边。 */
    public List<TransformSample> findStatic(String parent, String child) {
        return jdbcTemplate.query(
                COLUMNS + """
                        WHERE parent_frame = ? AND child_frame = ? AND edge_type = 'STATIC'
                        """,
                MAPPER, parent, child);
    }

    /** 全量快照：所有样本（一致性查询用），按父子、时间戳排序。 */
    public List<TransformSample> findAll() {
        return jdbcTemplate.query(
                COLUMNS + " ORDER BY parent_frame, child_frame, edge_type, stamp_nanos",
                MAPPER);
    }

    /** 淘汰指定动态边中时间戳严格小于 cutoffNanos 的样本，返回删除行数。 */
    public int deleteSamplesBefore(String parent, String child, long cutoffNanos) {
        return jdbcTemplate.update(
                """
                DELETE FROM tf_transform_sample
                WHERE parent_frame = ? AND child_frame = ?
                  AND edge_type = 'DYNAMIC' AND stamp_nanos < ?
                """,
                parent, child, cutoffNanos);
    }

    /** 查询某条动态边的最新样本时间戳；无样本时返回 null。 */
    public Long findMaxStamp(String parent, String child) {
        List<Long> values = jdbcTemplate.queryForList(
                """
                SELECT MAX(stamp_nanos) FROM tf_transform_sample
                WHERE parent_frame = ? AND child_frame = ? AND edge_type = 'DYNAMIC'
                """,
                Long.class, parent, child);
        return values.isEmpty() ? null : values.get(0);
    }
}
