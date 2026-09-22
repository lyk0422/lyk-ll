package com.example.starter.tf.persistence;

import com.example.starter.tf.domain.EdgeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 {@link JdbcTemplate} 的 H2（MODE=MySQL）仓库实现，全部为参数化 SQL。
 */
@Repository
public class JdbcTfRepository implements TfRepository {

    private static final RowMapper<EdgeRow> EDGE_MAPPER = (rs, rowNum) -> {
        long first = rs.getLong("first_sample_nanos");
        Long firstBoxed = rs.wasNull() ? null : first;
        long evicted = rs.getLong("evicted_upto_nanos");
        Long evictedBoxed = rs.wasNull() ? null : evicted;
        return new EdgeRow(
                rs.getLong("id"),
                rs.getString("parent_frame"),
                rs.getString("child_frame"),
                EdgeType.valueOf(rs.getString("edge_type")),
                rs.getString("source"),
                rs.getLong("created_at_nanos"),
                firstBoxed,
                evictedBoxed);
    };

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, rowNum) -> new SampleRow(
            rs.getLong("stamp_nanos"),
            rs.getString("source"),
            rs.getDouble("tx"),
            rs.getDouble("ty"),
            rs.getDouble("tz"),
            rs.getDouble("qx"),
            rs.getDouble("qy"),
            rs.getDouble("qz"),
            rs.getDouble("qw"));

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert edgeInsert;
    private final SimpleJdbcInsert sampleInsert;

    public JdbcTfRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.edgeInsert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("tf_edge")
                .usingGeneratedKeyColumns("id");
        this.sampleInsert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("tf_dynamic_sample")
                .usingGeneratedKeyColumns("id");
    }

    @Override
    public boolean insertFrameIfAbsent(String frameName, long nowNanos) {
        int updated = jdbcTemplate.update(
                "INSERT INTO tf_frame (frame_name, created_at_nanos) VALUES (?, ?)",
                frameName, nowNanos);
        return updated > 0;
    }

    @Override
    public boolean frameExists(String frameName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_frame WHERE frame_name = ?",
                Integer.class, frameName);
        return count != null && count > 0;
    }

    @Override
    public List<EdgeRow> findAllEdges() {
        return jdbcTemplate.query(
                "SELECT id, parent_frame, child_frame, edge_type, source, created_at_nanos, "
                        + "first_sample_nanos, evicted_upto_nanos FROM tf_edge ORDER BY id",
                EDGE_MAPPER);
    }

    @Override
    public Optional<EdgeRow> findEdgeByChild(String childFrame) {
        return jdbcTemplate.query(
                "SELECT id, parent_frame, child_frame, edge_type, source, created_at_nanos, "
                        + "first_sample_nanos, evicted_upto_nanos FROM tf_edge WHERE child_frame = ?",
                EDGE_MAPPER, childFrame).stream().findFirst();
    }

    @Override
    public long insertEdge(String parentFrame, String childFrame, EdgeType edgeType,
                           String source, long nowNanos) {
        Number key = edgeInsert.executeAndReturnKey(Map.of(
                "parent_frame", parentFrame,
                "child_frame", childFrame,
                "edge_type", edgeType.name(),
                "source", source,
                "created_at_nanos", nowNanos));
        return key.longValue();
    }

    @Override
    public Optional<SampleRow> findStaticPose(long edgeId) {
        List<SampleRow> rows = jdbcTemplate.query(
                "SELECT 0 AS stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw "
                        + "FROM tf_static_transform WHERE edge_id = ?",
                SAMPLE_MAPPER, edgeId);
        return rows.stream().findFirst();
    }

    @Override
    public void insertStaticPose(long edgeId, String source, SampleRow pose, long nowNanos) {
        jdbcTemplate.update(
                "INSERT INTO tf_static_transform "
                        + "(edge_id, source, tx, ty, tz, qx, qy, qz, qw, created_at_nanos) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                edgeId, source,
                pose.tx(), pose.ty(), pose.tz(),
                pose.qx(), pose.qy(), pose.qz(), pose.qw(), nowNanos);
    }

    @Override
    public Optional<SampleRow> findDynamicSample(long edgeId, long stampNanos) {
        List<SampleRow> rows = jdbcTemplate.query(
                "SELECT stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw "
                        + "FROM tf_dynamic_sample WHERE edge_id = ? AND stamp_nanos = ?",
                SAMPLE_MAPPER, edgeId, stampNanos);
        return rows.stream().findFirst();
    }

    @Override
    public void insertDynamicSample(long edgeId, SampleRow sample, long nowNanos) {
        Map<String, Object> params = new HashMap<>();
        params.put("edge_id", edgeId);
        params.put("stamp_nanos", sample.stampNanos());
        params.put("source", sample.source());
        params.put("tx", sample.tx());
        params.put("ty", sample.ty());
        params.put("tz", sample.tz());
        params.put("qx", sample.qx());
        params.put("qy", sample.qy());
        params.put("qz", sample.qz());
        params.put("qw", sample.qw());
        params.put("created_at_nanos", nowNanos);
        sampleInsert.execute(params);
    }

    @Override
    public void touchFirstSample(long edgeId, long stampNanos) {
        jdbcTemplate.update(
                "UPDATE tf_edge SET first_sample_nanos = ? WHERE id = ? "
                        + "AND (first_sample_nanos IS NULL OR first_sample_nanos > ?)",
                stampNanos, edgeId, stampNanos);
    }

    @Override
    public Optional<SampleRow> findLastSample(long edgeId) {
        return queryOne(
                "SELECT stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw "
                        + "FROM tf_dynamic_sample WHERE edge_id = ? "
                        + "ORDER BY stamp_nanos DESC FETCH FIRST 1 ROWS ONLY",
                edgeId);
    }

    @Override
    public Optional<SampleRow> findSampleAtOrBefore(long edgeId, long stampNanos) {
        return queryOne(
                "SELECT stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw "
                        + "FROM tf_dynamic_sample WHERE edge_id = ? AND stamp_nanos <= ? "
                        + "ORDER BY stamp_nanos DESC FETCH FIRST 1 ROWS ONLY",
                edgeId, stampNanos);
    }

    @Override
    public Optional<SampleRow> findSampleAtOrAfter(long edgeId, long stampNanos) {
        return queryOne(
                "SELECT stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw "
                        + "FROM tf_dynamic_sample WHERE edge_id = ? AND stamp_nanos >= ? "
                        + "ORDER BY stamp_nanos ASC FETCH FIRST 1 ROWS ONLY",
                edgeId, stampNanos);
    }

    @Override
    public Optional<EvictionOutcome> deleteSamplesOlderThan(long edgeId, long cutoffNanos) {
        Long maxDeleted = jdbcTemplate.queryForObject(
                "SELECT MAX(stamp_nanos) FROM tf_dynamic_sample WHERE edge_id = ? AND stamp_nanos < ?",
                Long.class, edgeId, cutoffNanos);
        if (maxDeleted == null) {
            return Optional.empty();
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM tf_dynamic_sample WHERE edge_id = ? AND stamp_nanos < ?",
                edgeId, cutoffNanos);
        return Optional.of(new EvictionOutcome(deleted, maxDeleted));
    }

    @Override
    public void updateEvictedUpto(long edgeId, long evictedUptoNanos) {
        jdbcTemplate.update(
                "UPDATE tf_edge SET evicted_upto_nanos = ? WHERE id = ? AND "
                        + "(evicted_upto_nanos IS NULL OR evicted_upto_nanos < ?)",
                evictedUptoNanos, edgeId, evictedUptoNanos);
    }

    private Optional<SampleRow> queryOne(String sql, Object... args) {
        return jdbcTemplate.query(sql, SAMPLE_MAPPER, args).stream().findFirst();
    }
}
