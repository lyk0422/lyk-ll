package com.example.starter.fishery.db;

import com.example.starter.fishery.db.support.GeneratedKeys;
import com.example.starter.fishery.domain.TransferStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 跨船配额调剂单与在途护栏访问；所有状态变更在调用方事务内完成。
 */
@Repository
public class TransferRepository {

    private static final String COLUMNS = "id, from_vessel, to_vessel, species_code, quota_year, "
            + "amount_kg, status, from_confirmed, to_confirmed, created_at, expires_at, effective_at, version";

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static TransferRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp effectiveAt = rs.getTimestamp("effective_at");
        return new TransferRow(
                rs.getLong("id"),
                rs.getString("from_vessel"),
                rs.getString("to_vessel"),
                rs.getString("species_code"),
                rs.getInt("quota_year"),
                rs.getBigDecimal("amount_kg"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getBoolean("from_confirmed"),
                rs.getBoolean("to_confirmed"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                effectiveAt == null ? null : effectiveAt.toInstant(),
                rs.getInt("version"));
    }

    /** 按 id 查调剂单。 */
    public Optional<TransferRow> findById(Long id) {
        List<TransferRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_transfer WHERE id = ?",
                TransferRepository::mapRow, id);
        return rows.stream().findFirst();
    }

    /** 事务内行锁读取调剂单；双方确认与失效判定串行化。 */
    public Optional<TransferRow> findByIdForUpdate(Long id) {
        List<TransferRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_transfer WHERE id = ? FOR UPDATE",
                TransferRepository::mapRow, id);
        return rows.stream().findFirst();
    }

    /** 新建待确认调剂单并返回自增主键。 */
    public long insert(String fromVessel, String toVessel, String speciesCode, int quotaYear,
                       java.math.BigDecimal amountKg, Instant createdAt, Instant expiresAt) {
        return GeneratedKeys.insertAndReturnKey(
                jdbc,
                "INSERT INTO quota_transfer(from_vessel, to_vessel, species_code, quota_year, amount_kg, "
                        + "status, from_confirmed, to_confirmed, created_at, expires_at, effective_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', FALSE, FALSE, ?, ?, NULL, 0)",
                fromVessel, toVessel, speciesCode, quotaYear, amountKg,
                Timestamp.from(createdAt), Timestamp.from(expiresAt)).longValue();
    }

    /** 记录一方确认；调用方须持有调剂单行锁。 */
    public void markConfirmed(Long id, boolean fromSide, int version) {
        String column = fromSide ? "from_confirmed" : "to_confirmed";
        int updated = jdbc.update(
                "UPDATE quota_transfer SET " + column + " = TRUE, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                id, version);
        if (updated != 1) {
            throw new IllegalStateException("调剂单乐观锁冲突: " + id);
        }
    }

    /** 双方确认后生效；调用方须在同一事务内完成双方额度变更。 */
    public void markEffective(Long id, Instant effectiveAt, int version) {
        int updated = jdbc.update(
                "UPDATE quota_transfer SET status = 'EFFECTIVE', effective_at = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                Timestamp.from(effectiveAt), id, version);
        if (updated != 1) {
            throw new IllegalStateException("调剂单乐观锁冲突: " + id);
        }
    }

    /** 确认时限恰满未确认，标记失效（额度不变）。 */
    public void markExpired(Long id, int version) {
        int updated = jdbc.update(
                "UPDATE quota_transfer SET status = 'EXPIRED', version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                id, version);
        if (updated != 1) {
            throw new IllegalStateException("调剂单乐观锁冲突: " + id);
        }
    }

    /**
     * 占用在途护栏（同船同鱼种同时刻至多一张进行中调剂单）；
     * 返回 false 表示该船该鱼种已存在进行中调剂单。
     */
    public boolean tryOccupyGuard(String vesselCode, String speciesCode, Long transferId) {
        try {
            jdbc.update("INSERT INTO transfer_pending_guard(vessel_code, species_code, transfer_id) "
                    + "VALUES (?, ?, ?)", vesselCode, speciesCode, transferId);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 释放调剂单占用的全部在途护栏（生效或失效时调用）。 */
    public void releaseGuards(Long transferId) {
        jdbc.update("DELETE FROM transfer_pending_guard WHERE transfer_id = ?", transferId);
    }
}
