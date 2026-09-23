package com.example.starter.evidence.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.evidence.domain.EvidenceStatus;

/**
 * 证据登记表数据访问。
 */
@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询证据。
     */
    public Optional<EvidenceRow> findById(String evidenceId) {
        List<EvidenceRow> rows = jdbc.query(
                "SELECT * FROM evidence WHERE evidence_id = ?",
                this::mapRow, evidenceId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键集合加行锁查询（按主键排序，避免并发死锁），供状态变更前串行化。
     */
    public List<EvidenceRow> findAllByIdForUpdate(Collection<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(",");
        evidenceIds.forEach(id -> placeholders.add("?"));
        return jdbc.query(
                "SELECT * FROM evidence WHERE evidence_id IN (" + placeholders + ") ORDER BY evidence_id FOR UPDATE",
                this::mapRow, evidenceIds.toArray());
    }

    /**
     * 登记新证据，初始状态 ACTIVE、版本 1。
     */
    public void insert(String evidenceId, String sha256, Instant collectedAt, Instant expiresAt, Instant now) {
        jdbc.update(
                "INSERT INTO evidence (evidence_id, sha256, collected_at, expires_at, status, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                evidenceId, sha256, Timestamp.from(collectedAt), Timestamp.from(expiresAt),
                EvidenceStatus.ACTIVE.name(), Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 更新证据状态并递增版本。
     */
    public void updateStatus(String evidenceId, EvidenceStatus status, Instant now) {
        jdbc.update(
                "UPDATE evidence SET status = ?, version = version + 1, updated_at = ? WHERE evidence_id = ?",
                status.name(), Timestamp.from(now), evidenceId);
    }

    private EvidenceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceRow(
                rs.getString("evidence_id"),
                rs.getString("sha256"),
                rs.getTimestamp("collected_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                EvidenceStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
