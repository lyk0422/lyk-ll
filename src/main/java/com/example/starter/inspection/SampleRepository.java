package com.example.starter.inspection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.SamplingMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 抽样记录数据访问。
 */
@Repository
public class SampleRepository {

    private final JdbcClient jdbc;

    public SampleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入抽样记录，（批次＋项目）唯一约束拒绝重复送检；返回生成主键。
     */
    public long insert(String requestId, String fingerprint, long batchId, String item,
                       SamplingMode mode, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO sample (request_id, fingerprint, batch_id, item, mode, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")
                .params(requestId, fingerprint, batchId, item, mode.name(), now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Sample> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM sample WHERE request_id = ?")
                .param(requestId)
                .query(this::map)
                .optional();
    }

    public Optional<Sample> findByBatchAndItem(long batchId, String item) {
        return jdbc.sql("SELECT * FROM sample WHERE batch_id = ? AND item = ?")
                .params(batchId, item)
                .query(this::map)
                .optional();
    }

    public List<Sample> findByBatchId(long batchId) {
        return jdbc.sql("SELECT * FROM sample WHERE batch_id = ? ORDER BY id")
                .param(batchId)
                .query(this::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM sample").query(Long.class).single();
    }

    private Sample map(ResultSet rs, int rowNum) throws SQLException {
        return new Sample(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("batch_id"),
                rs.getString("item"),
                SamplingMode.valueOf(rs.getString("mode")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
