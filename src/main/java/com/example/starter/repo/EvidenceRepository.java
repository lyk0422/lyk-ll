package com.example.starter.repo;

import com.example.starter.domain.CustodyEvent;
import com.example.starter.domain.CustodyEventType;
import com.example.starter.domain.Evidence;
import com.example.starter.domain.EvidenceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证据与保管事件数据访问（H2 MODE=MySQL）。
 */
@Repository
public class EvidenceRepository {

    private static final RowMapper<Evidence> EVIDENCE_MAPPER = (rs, n) -> new Evidence(
            rs.getString("evidence_id"),
            rs.getString("sha256"),
            rs.getTimestamp("collected_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            EvidenceStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CustodyEvent> EVENT_MAPPER = (rs, n) -> new CustodyEvent(
            rs.getLong("id"),
            rs.getString("evidence_id"),
            CustodyEventType.valueOf(rs.getString("event_type")),
            rs.getString("detail"),
            rs.getTimestamp("event_time").toInstant());

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Evidence> findById(String evidenceId) {
        List<Evidence> rows = jdbc.query(
                "SELECT evidence_id, sha256, collected_at, expires_at, status, created_at "
                        + "FROM evidence WHERE evidence_id = ?",
                EVIDENCE_MAPPER, evidenceId);
        return rows.stream().findFirst();
    }

    /**
     * 幂等插入登记记录；主键已存在时返回 false（由上层比对摘要区分重放与冲突）。
     */
    public boolean insert(Evidence evidence) {
        int affected = jdbc.update(
                "INSERT IGNORE INTO evidence (evidence_id, sha256, collected_at, expires_at, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                evidence.evidenceId(),
                evidence.sha256(),
                Timestamp.from(evidence.collectedAt()),
                Timestamp.from(evidence.expiresAt()),
                evidence.status().name(),
                Timestamp.from(evidence.createdAt()));
        return affected > 0;
    }

    public void appendEvent(String evidenceId, CustodyEventType type, String detail, Instant eventTime) {
        jdbc.update(
                "INSERT INTO custody_event (evidence_id, event_type, detail, event_time) VALUES (?, ?, ?, ?)",
                evidenceId, type.name(), detail, Timestamp.from(eventTime));
    }

    public List<CustodyEvent> listEvents(String evidenceId) {
        return jdbc.query(
                "SELECT id, evidence_id, event_type, detail, event_time FROM custody_event "
                        + "WHERE evidence_id = ? ORDER BY id",
                EVENT_MAPPER, evidenceId);
    }

    /**
     * 行锁锁定所有 expires_at 不晚于截止时间且仍处于 RETAINED 的候选证据（FOR UPDATE）。
     * 仅在事务内调用，锁定在事务提交前持续有效，阻止并发保全修改其覆盖关系。
     */
    public List<Evidence> lockExpiredRetained(Instant cutoff) {
        return jdbc.query(
                "SELECT evidence_id, sha256, collected_at, expires_at, status, created_at FROM evidence "
                        + "WHERE expires_at <= ? AND status = 'RETAINED' FOR UPDATE",
                EVIDENCE_MAPPER, Timestamp.from(cutoff));
    }

    /**
     * 行锁锁定指定证据（含已清除证据，供创建保全时判定 409）。
     */
    public List<Evidence> lockByIds(Collection<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = placeholders(evidenceIds.size());
        return jdbc.query(
                "SELECT evidence_id, sha256, collected_at, expires_at, status, created_at FROM evidence "
                        + "WHERE evidence_id IN (" + placeholders + ") FOR UPDATE",
                EVIDENCE_MAPPER, evidenceIds.toArray());
    }

    public int markIfRetained(String evidenceId) {
        return jdbc.update(
                "UPDATE evidence SET status = 'MARKED' WHERE evidence_id = ? AND status = 'RETAINED'",
                evidenceId);
    }

    public int rescueIfMarked(String evidenceId) {
        return jdbc.update(
                "UPDATE evidence SET status = 'RETAINED' WHERE evidence_id = ? AND status = 'MARKED'",
                evidenceId);
    }

    public int purgeIfMarked(String evidenceId) {
        return jdbc.update(
                "UPDATE evidence SET status = 'PURGED' WHERE evidence_id = ? AND status = 'MARKED'",
                evidenceId);
    }

    /**
     * 返回入参集合中被任意 ACTIVE 保全覆盖的证据 ID。
     */
    public Set<String> filterActiveHeld(Collection<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = placeholders(evidenceIds.size());
        List<String> held = jdbc.queryForList(
                "SELECT DISTINCT i.evidence_id FROM legal_hold_item i "
                        + "JOIN legal_hold h ON h.id = i.hold_id "
                        + "WHERE h.status = 'ACTIVE' AND i.evidence_id IN (" + placeholders + ")",
                String.class, evidenceIds.toArray());
        return Set.copyOf(held);
    }

    public Map<String, String> findShaByIds(Collection<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = placeholders(evidenceIds.size());
        return jdbc.query(
                "SELECT evidence_id, sha256 FROM evidence WHERE evidence_id IN (" + placeholders + ")",
                rs -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("evidence_id"), rs.getString("sha256"));
                    }
                    return result;
                },
                evidenceIds.toArray());
    }

    private static String placeholders(int count) {
        return java.util.Collections.nCopies(count, "?").stream().collect(Collectors.joining(","));
    }
}
