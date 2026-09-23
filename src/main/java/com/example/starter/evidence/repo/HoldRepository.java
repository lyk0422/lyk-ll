package com.example.starter.evidence.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.evidence.domain.HoldStatus;

/**
 * 法律保全单及其关联证据的数据访问。
 */
@Repository
public class HoldRepository {

    private final JdbcTemplate jdbc;

    public HoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按创建请求幂等键查询保全单。
     */
    public Optional<HoldRow> findByRequestId(String requestId) {
        List<HoldRow> rows = jdbc.query(
                "SELECT * FROM legal_hold WHERE request_id = ?",
                this::mapRow, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询保全单。
     */
    public Optional<HoldRow> findById(String holdId) {
        List<HoldRow> rows = jdbc.query(
                "SELECT * FROM legal_hold WHERE hold_id = ?",
                this::mapRow, holdId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键加行锁查询保全单，供解除时串行化。
     */
    public Optional<HoldRow> findByIdForUpdate(String holdId) {
        List<HoldRow> rows = jdbc.query(
                "SELECT * FROM legal_hold WHERE hold_id = ? FOR UPDATE",
                this::mapRow, holdId);
        return rows.stream().findFirst();
    }

    /**
     * 创建保全单，初始状态 ACTIVE、版本 1。
     */
    public void insert(String holdId, String requestId, String fingerprint, String caseId, Instant now) {
        jdbc.update(
                "INSERT INTO legal_hold (hold_id, request_id, fingerprint, case_id, status, version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?)",
                holdId, requestId, fingerprint, caseId, HoldStatus.ACTIVE.name(), Timestamp.from(now));
    }

    /**
     * 解除保全单，状态置为 RELEASED 并递增版本。
     */
    public void release(String holdId, String releaseRequestId, Instant now) {
        jdbc.update(
                "UPDATE legal_hold SET status = ?, version = version + 1, released_at = ?, release_request_id = ?"
                        + " WHERE hold_id = ?",
                HoldStatus.RELEASED.name(), Timestamp.from(now), releaseRequestId, holdId);
    }

    /**
     * 写入保全单与证据的关联。
     */
    public void insertItem(String holdId, String evidenceId) {
        jdbc.update("INSERT INTO legal_hold_item (hold_id, evidence_id) VALUES (?, ?)", holdId, evidenceId);
    }

    /**
     * 查询保全单覆盖的证据标识，按标识排序返回。
     */
    public List<String> findEvidenceIds(String holdId) {
        return jdbc.queryForList(
                "SELECT evidence_id FROM legal_hold_item WHERE hold_id = ? ORDER BY evidence_id",
                String.class, holdId);
    }

    /**
     * 查询覆盖指定证据集合的生效保全单数量，按证据分组，用于清除资格与确认复核。
     */
    public Set<String> findEvidenceIdsWithActiveHold(Collection<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return Set.of();
        }
        StringJoiner placeholders = new StringJoiner(",");
        evidenceIds.forEach(id -> placeholders.add("?"));
        List<String> rows = jdbc.queryForList(
                "SELECT DISTINCT i.evidence_id FROM legal_hold_item i"
                        + " JOIN legal_hold h ON h.hold_id = i.hold_id"
                        + " WHERE h.status = ? AND i.evidence_id IN (" + placeholders + ")",
                String.class, activeHoldArgs(evidenceIds));
        return Set.copyOf(rows);
    }

    private Object[] activeHoldArgs(Collection<String> evidenceIds) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(HoldStatus.ACTIVE.name()), evidenceIds.stream()).toArray();
    }

    private HoldRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new HoldRow(
                rs.getString("hold_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("case_id"),
                HoldStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                releasedAt == null ? null : releasedAt.toInstant(),
                rs.getString("release_request_id"));
    }
}
