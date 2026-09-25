package com.example.starter.flood.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 复盘归档记录仓储；warning_id 唯一，重复归档返回首次记录（幂等）。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long warningId, Long planId, Long orderId, String note,
                       LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO review_record(warning_id, plan_id, order_id, note, created_at) "
                        + "VALUES (?,?,?,?,?)",
                warningId, planId, orderId, note, Timestamp.valueOf(createdAt));
    }

    public boolean existsByWarningId(Long warningId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM review_record WHERE warning_id = ?",
                Integer.class, warningId);
        return count != null && count > 0;
    }

    /**
     * 取首次复盘记录的备注（幂等重放展示）。
     */
    public String findNoteByWarningId(Long warningId) {
        List<String> notes = jdbcTemplate.queryForList(
                "SELECT note FROM review_record WHERE warning_id = ? ORDER BY id FETCH FIRST 1 ROWS ONLY",
                String.class, warningId);
        return notes.isEmpty() ? null : notes.get(0);
    }
}
