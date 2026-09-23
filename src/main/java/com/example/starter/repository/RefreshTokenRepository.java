package com.example.starter.repository;

import com.example.starter.domain.FamilyStatus;
import com.example.starter.domain.RefreshTokenEntity;
import com.example.starter.domain.RotationRequestEntity;
import com.example.starter.domain.SecurityEventEntity;
import com.example.starter.domain.SecurityEventType;
import com.example.starter.domain.TokenFamily;
import com.example.starter.domain.TokenStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 刷新令牌族、令牌、轮换请求与安全事件的数据访问层。
 *
 * <p>所有写操作使用参数化 SQL；时间以 epoch 毫秒（BIGINT）持久化，语义为 UTC。
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TokenFamily insertFamily(String familyId, String userId, String clientId, Instant now) {
        long millis = now.toEpochMilli();
        jdbcTemplate.update("""
                INSERT INTO token_family
                    (family_id, user_id, client_id, status, created_at, updated_at, revoked_at, revoke_reason)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
                """, familyId, userId, clientId, FamilyStatus.ACTIVE.name(), millis, millis);
        return findFamily(familyId);
    }

    public TokenFamily findFamily(String familyId) {
        List<TokenFamily> rows = jdbcTemplate.query(
                "SELECT * FROM token_family WHERE family_id = ?", FAMILY_MAPPER, familyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取族行并加行锁（SELECT ... FOR UPDATE），用于同族轮换/登出串行化。
     * 必须在事务内调用。
     */
    public TokenFamily findFamilyForUpdate(String familyId) {
        List<TokenFamily> rows = jdbcTemplate.query(
                "SELECT * FROM token_family WHERE family_id = ? FOR UPDATE",
                FAMILY_MAPPER, familyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertToken(RefreshTokenEntity token) {
        jdbcTemplate.update("""
                INSERT INTO refresh_token
                    (family_id, token_hash, parent_hash, sequence_no, status,
                     client_id, expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                token.familyId(), token.tokenHash(), token.parentHash(), token.sequenceNo(),
                token.status().name(), token.clientId(),
                token.expiresAt().toEpochMilli(),
                token.consumedAt() == null ? null : token.consumedAt().toEpochMilli(),
                token.createdAt().toEpochMilli());
    }

    public RefreshTokenEntity findTokenByHash(String tokenHash) {
        List<RefreshTokenEntity> rows = jdbcTemplate.query(
                "SELECT * FROM refresh_token WHERE token_hash = ?", TOKEN_MAPPER, tokenHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按哈希读取令牌行并加行锁（FOR UPDATE），必须在已锁定族行的事务内调用，
     * 以读到并发轮换提交后的最新状态。
     */
    public RefreshTokenEntity findTokenByHashForUpdate(String tokenHash) {
        List<RefreshTokenEntity> rows = jdbcTemplate.query(
                "SELECT * FROM refresh_token WHERE token_hash = ? FOR UPDATE",
                TOKEN_MAPPER, tokenHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RefreshTokenEntity> findTokensByFamily(String familyId) {
        return jdbcTemplate.query(
                "SELECT * FROM refresh_token WHERE family_id = ? ORDER BY sequence_no",
                TOKEN_MAPPER, familyId);
    }

    /**
     * 条件消费：仅当令牌当前为 ACTIVE 时原子置为 CONSUMED，避免并发双消费。
     *
     * @return 受影响行数；为 0 表示已被其他事务消费或状态已变化
     */
    public int consumeTokenIfActive(String tokenHash, Instant consumedAt) {
        return jdbcTemplate.update("""
                UPDATE refresh_token
                   SET status = ?, consumed_at = ?
                 WHERE token_hash = ? AND status = ?
                """, TokenStatus.CONSUMED.name(), consumedAt.toEpochMilli(),
                tokenHash, TokenStatus.ACTIVE.name());
    }

    /**
     * 计算族内下一个令牌序号（当前最大序号 + 1；空族为 0）。
     */
    public int nextSequenceNo(String familyId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), -1) FROM refresh_token WHERE family_id = ?",
                Integer.class, familyId);
        return (max == null ? -1 : max) + 1;
    }

    /**
     * 将到期且仍为 ACTIVE 的令牌标记为 EXPIRED；其他状态不受影响。
     *
     * @return 受影响行数
     */
    public int markTokenExpiredIfActive(String tokenHash) {
        return jdbcTemplate.update("""
                UPDATE refresh_token
                   SET status = ?
                 WHERE token_hash = ? AND status = ?
                """, TokenStatus.EXPIRED.name(), tokenHash, TokenStatus.ACTIVE.name());
    }

    public RotationRequestEntity findRequest(String familyId, String requestId) {
        List<RotationRequestEntity> rows = jdbcTemplate.query(
                "SELECT * FROM rotation_request WHERE family_id = ? AND request_id = ?",
                REQUEST_MAPPER, familyId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertRequest(RotationRequestEntity request) {
        jdbcTemplate.update("""
                INSERT INTO rotation_request
                    (family_id, request_id, client_id, presented_token_hash,
                     request_fingerprint, child_token_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                request.familyId(), request.requestId(), request.clientId(),
                request.presentedTokenHash(), request.requestFingerprint(),
                request.childTokenHash(), request.createdAt().toEpochMilli());
    }

    /**
     * 撤销令牌族；仅对当前 ACTIVE 的族生效，保证重复登出无副作用。
     *
     * @return 受影响行数
     */
    public int revokeFamily(String familyId, String reason, Instant now) {
        long millis = now.toEpochMilli();
        return jdbcTemplate.update("""
                UPDATE token_family
                   SET status = ?, revoked_at = ?, revoke_reason = ?, updated_at = ?
                 WHERE family_id = ? AND status = ?
                """, FamilyStatus.REVOKED.name(), millis, reason, millis,
                familyId, FamilyStatus.ACTIVE.name());
    }

    /**
     * 将族内所有 ACTIVE 令牌置为 REVOKED；已 CONSUMED/EXPIRED 的历史令牌保持原状态。
     */
    public void revokeAllTokens(String familyId, Instant now) {
        jdbcTemplate.update("""
                UPDATE refresh_token
                   SET status = ?
                 WHERE family_id = ? AND status = ?
                """, TokenStatus.REVOKED.name(), familyId, TokenStatus.ACTIVE.name());
    }

    public int countTokens(String familyId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?",
                Integer.class, familyId);
        return count == null ? 0 : count;
    }

    public int countActiveTokens(String familyId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE family_id = ? AND status = ?",
                Integer.class, familyId, TokenStatus.ACTIVE.name());
        return count == null ? 0 : count;
    }

    /**
     * 插入安全事件；族+事件类型唯一约束保证每族至多一条 REPLAY_DETECTED。
     *
     * @return 受影响行数；重复插入返回 0
     */
    public int insertSecurityEventIfAbsent(SecurityEventEntity event) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO security_event
                        (family_id, event_type, request_id, token_hash,
                         request_fingerprint, detail, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.familyId(), event.eventType().name(), event.requestId(),
                    event.tokenHash(), event.requestFingerprint(), event.detail(),
                    event.createdAt().toEpochMilli());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return 0;
        }
    }

    public List<SecurityEventEntity> findEventsByFamily(String familyId) {
        return jdbcTemplate.query(
                "SELECT * FROM security_event WHERE family_id = ? ORDER BY id",
                EVENT_MAPPER, familyId);
    }

    private static Instant toInstant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    private static final RowMapper<TokenFamily> FAMILY_MAPPER = new RowMapper<>() {
        @Override
        public TokenFamily mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TokenFamily(
                    rs.getLong("id"),
                    rs.getString("family_id"),
                    rs.getString("user_id"),
                    rs.getString("client_id"),
                    FamilyStatus.valueOf(rs.getString("status")),
                    toInstant(rs.getLong("created_at")),
                    toInstant(rs.getLong("updated_at")),
                    toInstant((Long) rs.getObject("revoked_at")),
                    rs.getString("revoke_reason"));
        }
    };

    private static final RowMapper<RefreshTokenEntity> TOKEN_MAPPER = new RowMapper<>() {
        @Override
        public RefreshTokenEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long consumedMillis = (Long) rs.getObject("consumed_at");
            return new RefreshTokenEntity(
                    rs.getLong("id"),
                    rs.getString("family_id"),
                    rs.getString("token_hash"),
                    rs.getString("parent_hash"),
                    rs.getInt("sequence_no"),
                    TokenStatus.valueOf(rs.getString("status")),
                    rs.getString("client_id"),
                    toInstant(rs.getLong("expires_at")),
                    toInstant(consumedMillis),
                    toInstant(rs.getLong("created_at")));
        }
    };

    private static final RowMapper<RotationRequestEntity> REQUEST_MAPPER = new RowMapper<>() {
        @Override
        public RotationRequestEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RotationRequestEntity(
                    rs.getLong("id"),
                    rs.getString("family_id"),
                    rs.getString("request_id"),
                    rs.getString("client_id"),
                    rs.getString("presented_token_hash"),
                    rs.getString("request_fingerprint"),
                    rs.getString("child_token_hash"),
                    toInstant(rs.getLong("created_at")));
        }
    };

    private static final RowMapper<SecurityEventEntity> EVENT_MAPPER = new RowMapper<>() {
        @Override
        public SecurityEventEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SecurityEventEntity(
                    rs.getLong("id"),
                    rs.getString("family_id"),
                    SecurityEventType.valueOf(rs.getString("event_type")),
                    rs.getString("request_id"),
                    rs.getString("token_hash"),
                    rs.getString("request_fingerprint"),
                    rs.getString("detail"),
                    toInstant(rs.getLong("created_at")));
        }
    };
}
