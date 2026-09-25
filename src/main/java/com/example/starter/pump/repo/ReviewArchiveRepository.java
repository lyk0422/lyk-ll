package com.example.starter.pump.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 复盘归档仓储：pumpdown_id 唯一保证一个预降事件仅一次归档终态。
 */
@Repository
public class ReviewArchiveRepository {

    private final JdbcTemplate jdbc;

    public ReviewArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 归档摘要记录。 */
    public record ArchiveRecord(Long id, Long pumpdownId, String requestId, String summary,
                                LocalDateTime archivedAt) {
    }

    /** 插入归档记录。 */
    public void insert(Long pumpdownId, String requestId, String summary, LocalDateTime at) {
        jdbc.update(
                """
                INSERT INTO review_archive (pumpdown_id, request_id, summary, archived_at)
                VALUES (?, ?, ?, ?)
                """,
                pumpdownId, requestId, summary, at);
    }

    /** 按预降事件ID查既有归档（重复归档幂等返回）。 */
    public Optional<ArchiveRecord> findByPumpdownId(Long pumpdownId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, pumpdown_id, request_id, summary, archived_at
                    FROM review_archive WHERE pumpdown_id = ?
                    """,
                    (rs, n) -> new ArchiveRecord(
                            rs.getLong("id"),
                            rs.getLong("pumpdown_id"),
                            rs.getString("request_id"),
                            rs.getString("summary"),
                            rs.getObject("archived_at", LocalDateTime.class)),
                    pumpdownId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
