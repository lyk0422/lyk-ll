package com.example.starter.bag.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 隔离消息现场数据访问（只读查询为主）。
 */
@Repository
public class QuarantineRepository {

    /** 隔离记录行。 */
    public record QuarantineRow(
            long id,
            long originalMessageId,
            String topic,
            long timestampNs,
            long seq,
            String typeId,
            String payloadChecksum,
            String batchNo,
            long segmentId,
            String reason) {
    }

    private static final RowMapper<QuarantineRow> MAPPER = (rs, n) -> new QuarantineRow(
            rs.getLong("id"),
            rs.getLong("original_message_id"),
            rs.getString("topic"),
            rs.getLong("timestamp_ns"),
            rs.getLong("seq"),
            rs.getString("type_id"),
            rs.getString("payload_checksum"),
            rs.getString("batch_no"),
            rs.getLong("segment_id"),
            rs.getString("reason"));

    private final NamedParameterJdbcTemplate jdbc;

    public QuarantineRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 列出全部隔离记录，按 Topic、序列号原序。 */
    public List<QuarantineRow> findAll() {
        return jdbc.query(
                "SELECT * FROM bag_quarantine ORDER BY topic, seq",
                new MapSqlParameterSource(), MAPPER);
    }

    /** 统计某段被隔离的消息数。 */
    public long countBySegmentId(long segmentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM bag_quarantine WHERE segment_id = :segmentId",
                new MapSqlParameterSource("segmentId", segmentId), Long.class);
    }
}
