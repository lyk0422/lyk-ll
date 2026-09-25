package com.example.starter.repository;

import com.example.starter.model.PublicityRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 公示记录数据访问（publicity_record）。
 */
@Repository
public class PublicityRepository {

    private final JdbcTemplate jdbc;

    public PublicityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建公示记录：开始时间起7个自然日（UTC日界）后期满。
     */
    public void insert(long processId, Instant startAt, Instant endAt, Instant now) {
        jdbc.update("INSERT INTO publicity_record (process_id, start_at, end_at, status, created_at) VALUES (?,?,?,?,?)",
                processId, Timestamp.from(startAt), Timestamp.from(endAt), "IN_PROGRESS", Timestamp.from(now));
    }

    /**
     * 按流程查询公示记录。
     */
    public Optional<PublicityRow> findByProcessId(long processId) {
        return jdbc.query("SELECT process_id, start_at, end_at, status, objection_request_id, objection_reason "
                        + "FROM publicity_record WHERE process_id = ?",
                (rs, rowNum) -> new PublicityRow(
                        rs.getLong("process_id"),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant(),
                        rs.getString("status"),
                        rs.getString("objection_request_id"),
                        rs.getString("objection_reason")),
                processId).stream().findFirst();
    }

    /**
     * 条件更新公示状态：仅当前状态为 expected 时生效，与签发并发时形成唯一终态。
     *
     * @return true 更新成功
     */
    public boolean transitionStatus(long processId, String expected, String target,
                                    String objectionRequestId, String objectionReason) {
        return jdbc.update("UPDATE publicity_record SET status = ?, objection_request_id = ?, objection_reason = ? "
                        + "WHERE process_id = ? AND status = ?",
                target, objectionRequestId, objectionReason, processId, expected) == 1;
    }
}
