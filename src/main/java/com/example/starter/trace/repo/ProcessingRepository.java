package com.example.starter.trace.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 加工批的数据访问。
 */
@Repository
public class ProcessingRepository {

    private final JdbcTemplate jdbc;

    public ProcessingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入加工批，返回自增主键。
     */
    public Long insertProcessingBatch(String processNo, Long harvestBatchId, String requestId,
                                      LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO processing_batch (process_no, harvest_batch_id, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, processNo);
            ps.setLong(2, harvestBatchId);
            ps.setString(3, requestId);
            ps.setTimestamp(4, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
