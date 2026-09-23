package com.example.starter.repo;

import com.example.starter.domain.HoldStatus;
import com.example.starter.domain.LegalHold;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 法律保全数据访问，含保全全局版本序列（seq_holder 单行）。
 */
@Repository
public class HoldRepository {

    private final JdbcTemplate jdbc;

    public HoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LegalHold> HOLD_MAPPER = (rs, n) -> new LegalHold(
            rs.getLong("id"),
            rs.getString("case_id"),
            HoldStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            null,
            rs.getString("create_request_id"),
            rs.getString("release_request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant());

    public long currentVersion() {
        Long v = jdbc.queryForObject("SELECT hold_version FROM seq_holder WHERE id = 0", Long.class);
        return v == null ? 0L : v;
    }

    /**
     * 行锁锁定版本序列并原子递增，返回递增后的版本。所有保全创建/解除经此串行化。
     */
    public long lockAndNextVersion() {
        jdbc.update("UPDATE seq_holder SET hold_version = hold_version + 1 WHERE id = 0");
        Long v = jdbc.queryForObject("SELECT hold_version FROM seq_holder WHERE id = 0", Long.class);
        return v == null ? 0L : v;
    }

    /**
     * 行锁锁定版本序列行（不递增），用于清除最终确认与保全创建互斥。
     */
    public void lockVersionRow() {
        jdbc.queryForObject("SELECT hold_version FROM seq_holder WHERE id = 0 FOR UPDATE", Long.class);
    }

    public long insertHold(String caseId, long version, String createRequestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO legal_hold (case_id, status, version, created_at, create_request_id) "
                            + "VALUES (?, 'ACTIVE', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, caseId);
            ps.setLong(2, version);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setString(4, createRequestId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertItem(long holdId, String evidenceId) {
        jdbc.update("INSERT INTO legal_hold_item (hold_id, evidence_id) VALUES (?, ?)", holdId, evidenceId);
    }

    public Optional<LegalHold> findById(long holdId) {
        List<LegalHold> rows = jdbc.query(
                "SELECT id, case_id, status, version, created_at, released_at, "
                        + "create_request_id, release_request_id FROM legal_hold WHERE id = ?",
                HOLD_MAPPER, holdId);
        return rows.stream().findFirst().map(this::withItems);
    }

    /**
     * 乐观解除：仅当保全为 ACTIVE 且版本匹配时更新，返回影响行数。
     */
    public int release(long holdId, long expectedVersion, String releaseRequestId, Instant now) {
        return jdbc.update(
                "UPDATE legal_hold SET status = 'RELEASED', version = version + 1, "
                        + "released_at = ?, release_request_id = ? "
                        + "WHERE id = ? AND status = 'ACTIVE' AND version = ?",
                Timestamp.from(now), releaseRequestId, holdId, expectedVersion);
    }

    private LegalHold withItems(LegalHold hold) {
        List<String> items = jdbc.queryForList(
                "SELECT evidence_id FROM legal_hold_item WHERE hold_id = ? ORDER BY evidence_id",
                String.class, hold.holdId());
        return new LegalHold(hold.holdId(), hold.caseId(), hold.status(), hold.version(), items,
                hold.createRequestId(), hold.releaseRequestId(), hold.createdAt(), hold.releasedAt());
    }
}
