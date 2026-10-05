package com.example.starter.fishery.db;

import com.example.starter.fishery.db.support.GeneratedKeys;
import com.example.starter.fishery.domain.TransferStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 跨船配额调剂单与在途护栏访问；状态流转与额度变更均在调用方事务内完成。
 */
@Repository
public class TransferRepository {

    private static final String COLUMNS = "id, request_id, from_vessel_code, to_vessel_code, "
            + "species_code, quota_year, amount_kg, status, created_at, expires_at, confirmed_at";

    private static final RowMapper<TransferRow> MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("from_vessel_code"),
            rs.getString("to_vessel_code"),
            rs.getString("species_code"),
            rs.getInt("quota_year"),
            rs.getBigDecimal("amount_kg"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant());

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建待确认调剂单，返回自增主键。 */
    public long insert(String requestId, String fromVessel, String toVessel, String speciesCode,
                       int quotaYear, java.math.BigDecimal amountKg, Instant createdAt, Instant expiresAt) {
        return GeneratedKeys.insertAndReturnKey(jdbc,
                "INSERT INTO quota_transfer(request_id, from_vessel_code, to_vessel_code, species_code, "
                        + "quota_year, amount_kg, status, created_at, expires_at, confirmed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, NULL)",
                requestId, fromVessel, toVessel, speciesCode, quotaYear, amountKg,
                Timestamp.from(createdAt), Timestamp.from(expiresAt)).longValue();
    }

    /** 按主键查询。 */
    public Optional<TransferRow> findById(long id) {
        List<TransferRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_transfer WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 事务内行锁读取调剂单；确认/失效流转时串行化并发操作。 */
    public Optional<TransferRow> findByIdForUpdate(long id) {
        List<TransferRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_transfer WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 标记双方确认生效。 */
    public void markEffective(long id, Instant confirmedAt) {
        jdbc.update("UPDATE quota_transfer SET status = 'EFFECTIVE', confirmed_at = ? WHERE id = ?",
                Timestamp.from(confirmedAt), id);
    }

    /** 标记超时失效（额度不变）。 */
    public void markExpired(long id) {
        jdbc.update("UPDATE quota_transfer SET status = 'EXPIRED' WHERE id = ?", id);
    }

    /** 查询涉及指定渔船（任一方）且已到期待失效的进行中调剂单。 */
    public List<TransferRow> findOverduePendingForVessels(String vesselA, String vesselB,
                                                          String speciesCode, int quotaYear, Instant now) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_transfer "
                        + "WHERE status = 'PENDING' AND species_code = ? AND quota_year = ? "
                        + "AND expires_at <= ? AND (from_vessel_code IN (?, ?) OR to_vessel_code IN (?, ?))",
                MAPPER, speciesCode, quotaYear, Timestamp.from(now),
                vesselA, vesselB, vesselA, vesselB);
    }

    /**
     * 占用在途护栏（同船同鱼种同年度唯一）；已被占用返回 false。
     * 依赖主键唯一约束：并发发起在数据库层串行化。
     */
    public boolean tryOccupyGuard(String vesselCode, String speciesCode, int quotaYear, long transferId) {
        try {
            jdbc.update("INSERT INTO transfer_active_guard(vessel_code, species_code, quota_year, transfer_id) "
                    + "VALUES (?, ?, ?, ?)", vesselCode, speciesCode, quotaYear, transferId);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 释放调剂单占用的全部在途护栏（生效或失效时调用）。 */
    public void releaseGuards(long transferId) {
        jdbc.update("DELETE FROM transfer_active_guard WHERE transfer_id = ?", transferId);
    }
}
