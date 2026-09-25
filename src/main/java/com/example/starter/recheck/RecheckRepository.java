package com.example.starter.recheck;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.RecheckResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 定量复检数据访问。
 */
@Repository
public class RecheckRepository {

    private final JdbcClient jdbc;

    public RecheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入复检记录，request_id 唯一约束保证幂等；返回生成主键。
     */
    public long insert(String requestId, String fingerprint, long batchId,
                       RecheckResult result, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO recheck (request_id, fingerprint, batch_id, result, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(requestId, fingerprint, batchId, result.name(), now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Recheck> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM recheck WHERE request_id = ?")
                .param(requestId)
                .query(this::map)
                .optional();
    }

    public List<Recheck> findByBatchId(long batchId) {
        return jdbc.sql("SELECT * FROM recheck WHERE batch_id = ? ORDER BY id")
                .param(batchId)
                .query(this::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM recheck").query(Long.class).single();
    }

    private Recheck map(ResultSet rs, int rowNum) throws SQLException {
        return new Recheck(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("batch_id"),
                RecheckResult.valueOf(rs.getString("result")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
