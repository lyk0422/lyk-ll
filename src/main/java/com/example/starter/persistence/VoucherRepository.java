package com.example.starter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 凭证数据访问。
 */
@Repository
public class VoucherRepository {

    private final JdbcTemplate jdbc;

    public VoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<VoucherPO> ROW_MAPPER = (rs, n) -> new VoucherPO(
            rs.getLong("id"),
            rs.getString("voucher_no"),
            rs.getString("request_id"),
            rs.getString("station_code"),
            rs.getDate("gas_day").toLocalDate(),
            rs.getString("voucher_type"),
            rs.getBigDecimal("volume"),
            rs.getBigDecimal("avg_heating_value"),
            rs.getBigDecimal("energy_mj"),
            rs.getString("status"),
            rs.getBoolean("party_a_confirmed"),
            rs.getBoolean("party_b_confirmed"),
            rs.getString("party_a_name"),
            rs.getString("party_b_name"),
            rs.getTimestamp("party_a_at") == null ? null : rs.getTimestamp("party_a_at").toInstant(),
            rs.getTimestamp("party_b_at") == null ? null : rs.getTimestamp("party_b_at").toInstant(),
            rs.getTimestamp("effective_at") == null ? null
                    : rs.getTimestamp("effective_at").toInstant(),
            rs.getDate("dispute_deadline") == null ? null
                    : rs.getDate("dispute_deadline").toLocalDate(),
            rs.getTimestamp("sealed_at") == null ? null
                    : rs.getTimestamp("sealed_at").toInstant(),
            rs.getString("original_voucher_no"),
            rs.getString("reason"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    /** 插入凭证；唯一键冲突抛 DataIntegrityViolationException，由服务层转 409/重放。 */
    public void insert(VoucherPO po) {
        jdbc.update("""
                INSERT INTO voucher (
                    voucher_no, request_id, station_code, gas_day, voucher_type,
                    volume, avg_heating_value, energy_mj, status,
                    party_a_confirmed, party_b_confirmed,
                    party_a_name, party_b_name, party_a_at, party_b_at,
                    effective_at, dispute_deadline, sealed_at,
                    original_voucher_no, reason, fingerprint, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                po.voucherNo(), po.requestId(), po.stationCode(),
                java.sql.Date.valueOf(po.gasDay()), po.voucherType(),
                po.volume(), po.avgHeatingValue(), po.energyMj(), po.status(),
                po.partyAConfirmed(), po.partyBConfirmed(),
                po.partyAName(), po.partyBName(), ts(po.partyAAt()), ts(po.partyBAt()),
                ts(po.effectiveAt()),
                po.disputeDeadline() == null ? null : java.sql.Date.valueOf(po.disputeDeadline()),
                ts(po.sealedAt()),
                po.originalVoucherNo(), po.reason(), po.fingerprint(),
                ts(po.createdAt()), ts(po.updatedAt()));
    }

    /** 按凭证号查询，行锁（FOR UPDATE），须在事务内调用。 */
    public Optional<VoucherPO> findByNoForUpdate(String voucherNo) {
        return jdbc.query("SELECT * FROM voucher WHERE voucher_no = ? FOR UPDATE",
                ROW_MAPPER, voucherNo).stream().findFirst();
    }

    /** 按凭证号查询。 */
    public Optional<VoucherPO> findByNo(String voucherNo) {
        return jdbc.query("SELECT * FROM voucher WHERE voucher_no = ?", ROW_MAPPER, voucherNo)
                .stream().findFirst();
    }

    /** 按 requestId 查询。 */
    public Optional<VoucherPO> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM voucher WHERE request_id = ?", ROW_MAPPER, requestId)
                .stream().findFirst();
    }

    /** 查询某站某气量日的正常凭证。 */
    public Optional<VoucherPO> findNormal(String stationCode, LocalDate gasDay) {
        return jdbc.query("""
                SELECT * FROM voucher
                WHERE station_code = ? AND gas_day = ? AND voucher_type = 'NORMAL'
                """, ROW_MAPPER, stationCode, java.sql.Date.valueOf(gasDay)).stream().findFirst();
    }

    /** 查询某站某气量日全部凭证（含调整凭证），按创建时刻升序，结果稳定。 */
    public List<VoucherPO> findByStationAndDay(String stationCode, LocalDate gasDay) {
        return jdbc.query("""
                SELECT * FROM voucher
                WHERE station_code = ? AND gas_day = ?
                ORDER BY created_at ASC, id ASC
                """, ROW_MAPPER, stationCode, java.sql.Date.valueOf(gasDay));
    }

    /** 查询某站气量日区间内全部凭证，按气量日与创建时刻升序。 */
    public List<VoucherPO> findByStationAndDayRange(String stationCode, LocalDate from,
            LocalDate toInclusive) {
        return jdbc.query("""
                SELECT * FROM voucher
                WHERE station_code = ? AND gas_day BETWEEN ? AND ?
                ORDER BY gas_day ASC, created_at ASC, id ASC
                """, ROW_MAPPER, stationCode, java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(toInclusive));
    }

    /** 条件更新状态，返回受影响行数，用于并发封账/争议的唯一结果竞争。 */
    public int compareAndUpdateStatus(String voucherNo, String expectedStatus, String newStatus,
            Instant now) {
        return jdbc.update("""
                UPDATE voucher SET status = ?, updated_at = ?
                WHERE voucher_no = ? AND status = ?
                """, newStatus, Timestamp.from(now), voucherNo, expectedStatus);
    }

    /** 记录一方确认，返回受影响行数（已被该方确认时为 0，实现确认幂等）。 */
    public int updateConfirmation(String voucherNo, String party, String confirmer, Instant now) {
        if ("A".equals(party)) {
            return jdbc.update("""
                    UPDATE voucher SET party_a_confirmed = TRUE, party_a_name = ?,
                        party_a_at = ?, updated_at = ?
                    WHERE voucher_no = ? AND party_a_confirmed = FALSE
                    """, confirmer, Timestamp.from(now), Timestamp.from(now), voucherNo);
        }
        return jdbc.update("""
                UPDATE voucher SET party_b_confirmed = TRUE, party_b_name = ?,
                    party_b_at = ?, updated_at = ?
                WHERE voucher_no = ? AND party_b_confirmed = FALSE
                """, confirmer, Timestamp.from(now), Timestamp.from(now), voucherNo);
    }

    /** 置生效：双方确认齐全，写入生效时刻与争议期截止日。 */
    public int markEffective(String voucherNo, Instant effectiveAt, LocalDate disputeDeadline,
            Instant now) {
        return jdbc.update("""
                UPDATE voucher SET status = 'EFFECTIVE', effective_at = ?,
                    dispute_deadline = ?, updated_at = ?
                WHERE voucher_no = ? AND status = 'DRAFT'
                  AND party_a_confirmed = TRUE AND party_b_confirmed = TRUE
                """, Timestamp.from(effectiveAt), java.sql.Date.valueOf(disputeDeadline),
                Timestamp.from(now), voucherNo);
    }

    /**
     * 封账：仅 EFFECTIVE 且未封账的凭证可封，返回受影响行数。
     * 与争议的 EFFECTIVE→DISPUTED 条件更新互斥，并发时只有一方成功，形成唯一结果。
     */
    public int seal(String voucherNo, Instant sealedAt, Instant now) {
        return jdbc.update("""
                UPDATE voucher SET status = 'SEALED', sealed_at = ?, updated_at = ?
                WHERE voucher_no = ? AND sealed_at IS NULL AND status = 'EFFECTIVE'
                """, Timestamp.from(sealedAt), Timestamp.from(now), voucherNo);
    }

    /**
     * 原凭证标记为已调整（终态）。允许由 SEALED（封账后差错）或 DISPUTED（争议签认闭环）转入。
     */
    public int markAdjusted(String voucherNo, Instant now) {
        return jdbc.update("""
                UPDATE voucher SET status = 'ADJUSTED', updated_at = ?
                WHERE voucher_no = ? AND status IN ('SEALED', 'DISPUTED')
                """, Timestamp.from(now), voucherNo);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
