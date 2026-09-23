package com.example.starter.token;

import com.example.starter.token.api.SecurityEventResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌数据访问层；只持久化散列、关系、状态与时间，不接触明文令牌。
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 令牌族 ----------

    public void insertFamily(TokenFamily family) {
        jdbc.update("""
                INSERT INTO token_family (id, user_id, status, created_at, revoked_at, revoke_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                family.id(),
                family.userId(),
                family.status().name(),
                Timestamp.from(family.createdAt()),
                family.revokedAt() == null ? null : Timestamp.from(family.revokedAt()),
                family.revokeReason());
    }

    public Optional<TokenFamily> findFamily(String familyId) {
        List<TokenFamily> rows = jdbc.query(
                "SELECT id, user_id, status, created_at, revoked_at, revoke_reason FROM token_family WHERE id = ?",
                FAMILY_MAPPER, familyId);
        return rows.stream().findFirst();
    }

    /**
     * 锁定令牌族行（SELECT ... FOR UPDATE），用于串行化同族内的轮换消费。
     */
    public TokenFamily lockFamily(String familyId) {
        List<TokenFamily> rows = jdbc.query(
                "SELECT id, user_id, status, created_at, revoked_at, revoke_reason "
                        + "FROM token_family WHERE id = ? FOR UPDATE",
                FAMILY_MAPPER, familyId);
        return rows.stream().findFirst().orElse(null);
    }

    /**
     * 将仍处于 ACTIVE 的族置为 REVOKED 终态；返回是否发生了状态变化。
     */
    public boolean markFamilyRevoked(String familyId, String reason, Instant now) {
        int updated = jdbc.update("""
                UPDATE token_family
                SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), reason, familyId);
        return updated > 0;
    }

    public int countTokens(String familyId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Integer.class, familyId);
        return count == null ? 0 : count;
    }

    // ---------- 刷新令牌 ----------

    public void insertToken(RefreshToken token) {
        jdbc.update("""
                INSERT INTO refresh_token
                    (token_hash, family_id, parent_hash, seq, status, client_id, issued_at, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                token.tokenHash(),
                token.familyId(),
                token.parentHash(),
                token.seq(),
                token.status().name(),
                token.clientId(),
                Timestamp.from(token.issuedAt()),
                Timestamp.from(token.expiresAt()),
                token.consumedAt() == null ? null : Timestamp.from(token.consumedAt()));
    }

    public Optional<RefreshToken> findToken(String tokenHash) {
        List<RefreshToken> rows = jdbc.query(
                "SELECT token_hash, family_id, parent_hash, seq, status, client_id, issued_at, expires_at, consumed_at "
                        + "FROM refresh_token WHERE token_hash = ?",
                TOKEN_MAPPER, tokenHash);
        return rows.stream().findFirst();
    }

    /**
     * 原子条件消费：仅当令牌仍为 ACTIVE 时置为 CONSUMED。
     * 并发消费同一令牌时至多一个事务影响 1 行。
     */
    public boolean consumeIfActive(String tokenHash, Instant consumedAt) {
        int updated = jdbc.update("""
                UPDATE refresh_token
                SET status = 'CONSUMED', consumed_at = ?
                WHERE token_hash = ? AND status = 'ACTIVE'
                """, Timestamp.from(consumedAt), tokenHash);
        return updated > 0;
    }

    /**
     * 将到期且仍 ACTIVE 的令牌置为 EXPIRED；返回是否发生了状态变化。
     */
    public boolean markExpiredIfDue(String tokenHash, Instant now) {
        int updated = jdbc.update("""
                UPDATE refresh_token
                SET status = 'EXPIRED'
                WHERE token_hash = ? AND status = 'ACTIVE' AND expires_at <= ?
                """, tokenHash, Timestamp.from(now));
        return updated > 0;
    }

    /**
     * 撤销族内所有仍 ACTIVE 的令牌（含全部后代），已 CONSUMED/EXPIRED 的保持原态。
     */
    public int revokeActiveTokensInFamily(String familyId) {
        return jdbc.update(
                "UPDATE refresh_token SET status = 'REVOKED' WHERE family_id = ? AND status = 'ACTIVE'",
                familyId);
    }

    public List<RefreshToken> findTokensInFamily(String familyId) {
        return jdbc.query(
                "SELECT token_hash, family_id, parent_hash, seq, status, client_id, issued_at, expires_at, consumed_at "
                        + "FROM refresh_token WHERE family_id = ? ORDER BY seq",
                TOKEN_MAPPER, familyId);
    }

    // ---------- 轮换请求指纹 ----------

    /**
     * 写入轮换指纹；requestId 或 childHash 冲突时返回 false 由调用方判定。
     */
    public boolean insertRotationRecord(RotationRecord record) {
        try {
            jdbc.update("""
                    INSERT INTO rotation_record
                        (request_id, family_id, parent_hash, client_id, child_hash, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    record.requestId(),
                    record.familyId(),
                    record.parentHash(),
                    record.clientId(),
                    record.childHash(),
                    Timestamp.from(record.createdAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<RotationRecord> findRotationRecord(String requestId) {
        List<RotationRecord> rows = jdbc.query(
                "SELECT request_id, family_id, parent_hash, client_id, child_hash, created_at "
                        + "FROM rotation_record WHERE request_id = ?",
                (rs, n) -> new RotationRecord(
                        rs.getString("request_id"),
                        rs.getString("family_id"),
                        rs.getString("parent_hash"),
                        rs.getString("client_id"),
                        rs.getString("child_hash"),
                        rs.getTimestamp("created_at").toInstant()),
                requestId);
        return rows.stream().findFirst();
    }

    // ---------- 安全事件 ----------

    /**
     * 写入安全事件；(familyId, eventType) 唯一约束保证终态事件幂等，重复写入静默忽略。
     */
    @Transactional
    public void insertSecurityEventIfAbsent(SecurityEventType type, String familyId,
                                            String tokenHash, String requestId, String detail, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO security_event (event_type, family_id, token_hash, request_id, detail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, type.name());
                ps.setString(2, familyId);
                if (tokenHash == null) {
                    ps.setNull(3, Types.CHAR);
                } else {
                    ps.setString(3, tokenHash);
                }
                if (requestId == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, requestId);
                }
                ps.setString(5, detail);
                ps.setTimestamp(6, Timestamp.from(now));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ignored) {
            // 每族每类事件只允许一条，重复触达不新增事件。
        }
    }

    public List<SecurityEventResponse> findEvents(String familyId) {
        return jdbc.query("""
                SELECT id, event_type, family_id, token_hash, request_id, detail, created_at
                FROM security_event WHERE family_id = ? ORDER BY id
                """,
                (rs, n) -> new SecurityEventResponse(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("family_id"),
                        rs.getString("request_id"),
                        rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                familyId);
    }

    // ---------- RowMapper ----------

    private static final RowMapper<TokenFamily> FAMILY_MAPPER = new RowMapper<>() {
        @Override
        public TokenFamily mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            return new TokenFamily(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    FamilyStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    revokedAt == null ? null : revokedAt.toInstant(),
                    rs.getString("revoke_reason"));
        }
    };

    private static final RowMapper<RefreshToken> TOKEN_MAPPER = new RowMapper<>() {
        @Override
        public RefreshToken mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp consumedAt = rs.getTimestamp("consumed_at");
            return new RefreshToken(
                    rs.getString("token_hash"),
                    rs.getString("family_id"),
                    rs.getString("parent_hash"),
                    rs.getInt("seq"),
                    TokenStatus.valueOf(rs.getString("status")),
                    rs.getString("client_id"),
                    rs.getTimestamp("issued_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    consumedAt == null ? null : consumedAt.toInstant());
        }
    };
}
