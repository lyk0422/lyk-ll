package com.example.starter.gas.repo;

import com.example.starter.gas.domain.VoucherRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 交接凭证、确认、争议及迟到差异的数据访问。
 */
@Repository
public class VoucherRepository {

    private static final RowMapper<VoucherRow> ROW_MAPPER = (rs, rowNum) -> new VoucherRow(
            rs.getLong("id"),
            rs.getString("voucher_no"),
            rs.getString("request_id"),
            rs.getString("voucher_type"),
            rs.getString("original_voucher_no"),
            rs.getString("station_code"),
            rs.getDate("gas_date").toLocalDate(),
            rs.getBigDecimal("volume"),
            rs.getBigDecimal("weighted_heating_value"),
            rs.getBigDecimal("energy"),
            rs.getString("status"),
            rs.getString("first_confirm_party"),
            rs.getDate("effective_date") == null ? null : rs.getDate("effective_date").toLocalDate(),
            rs.getTimestamp("effective_at") == null ? null : rs.getTimestamp("effective_at").toInstant(),
            rs.getTimestamp("sealed_at") == null ? null : rs.getTimestamp("sealed_at").toInstant());

    private final JdbcTemplate jdbc;

    public VoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按凭证号查询。 */
    public VoucherRow findByNo(String voucherNo) {
        List<VoucherRow> rows = jdbc.query(
                "SELECT * FROM custody_voucher WHERE voucher_no = ?", ROW_MAPPER, voucherNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按幂等键查询。 */
    public VoucherRow findByRequestId(String requestId) {
        List<VoucherRow> rows = jdbc.query(
                "SELECT * FROM custody_voucher WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询同站同气量日的原始凭证（同日唯一约束）。 */
    public VoucherRow findOriginalByStationAndDate(String stationCode, LocalDate gasDate) {
        List<VoucherRow> rows = jdbc.query(
                "SELECT * FROM custody_voucher WHERE station_code = ? AND gas_date = ? "
                        + "AND voucher_type = 'ORIGINAL' ORDER BY id LIMIT 1",
                ROW_MAPPER, stationCode, java.sql.Date.valueOf(gasDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入原始凭证，返回凭证号。 */
    public String insertOriginal(String requestId, String voucherNo, String stationCode, LocalDate gasDate,
                                 BigDecimal volume, BigDecimal weightedHv, BigDecimal energy) {
        jdbc.update(
                "INSERT INTO custody_voucher (voucher_no, request_id, voucher_type, station_code, gas_date, "
                        + "volume, weighted_heating_value, energy, status) "
                        + "VALUES (?, ?, 'ORIGINAL', ?, ?, ?, ?, ?, 'DRAFT')",
                voucherNo, requestId, stationCode, java.sql.Date.valueOf(gasDate),
                volume, weightedHv, energy);
        return voucherNo;
    }

    /** 插入调整凭证（双方签认，提交即生效）。 */
    public String insertAdjustment(String requestId, String voucherNo, String originalVoucherNo,
                                   String stationCode, LocalDate gasDate,
                                   BigDecimal volume, BigDecimal weightedHv,
                                   BigDecimal energy, Instant now) {
        jdbc.update(
                "INSERT INTO custody_voucher (voucher_no, request_id, voucher_type, original_voucher_no, "
                        + "station_code, gas_date, volume, weighted_heating_value, energy, status, "
                        + "effective_date, effective_at) "
                        + "VALUES (?, ?, 'ADJUSTMENT', ?, ?, ?, ?, ?, ?, 'EFFECTIVE', ?, ?)",
                voucherNo, requestId, originalVoucherNo, stationCode, java.sql.Date.valueOf(gasDate),
                volume, weightedHv, energy, java.sql.Date.valueOf(LocalDate.ofInstant(now, java.time.ZoneOffset.UTC)),
                Timestamp.from(now));
        return voucherNo;
    }

    /**
     * 插入确认记录；唯一约束冲突返回 false，由调用方按重复确认/幂等处理。
     */
    public boolean insertConfirmation(String voucherNo, String party, String requestId, Instant now) {
        try {
            jdbc.update(
                    "INSERT INTO voucher_confirmation (voucher_no, party, request_id, confirmed_at) "
                            + "VALUES (?, ?, ?, ?)",
                    voucherNo, party, requestId, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 某方是否已确认。 */
    public boolean isConfirmedBy(String voucherNo, String party) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirmation WHERE voucher_no = ? AND party = ?",
                Integer.class, voucherNo, party);
        return count != null && count > 0;
    }

    /** 更新凭证状态（单方确认时记录首个确认方）。 */
    public void updateStatus(String voucherNo, String status, String firstConfirmParty) {
        jdbc.update(
                "UPDATE custody_voucher SET status = ?, first_confirm_party = COALESCE(first_confirm_party, ?), "
                        + "updated_at = CURRENT_TIMESTAMP WHERE voucher_no = ?",
                status, firstConfirmParty, voucherNo);
    }

    /** 双方确认齐备，置为生效。 */
    public void markEffective(String voucherNo, LocalDate effectiveDate, Instant effectiveAt) {
        jdbc.update(
                "UPDATE custody_voucher SET status = 'EFFECTIVE', effective_date = ?, effective_at = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE voucher_no = ?",
                java.sql.Date.valueOf(effectiveDate), Timestamp.from(effectiveAt), voucherNo);
    }

    /** 置为争议挂起。 */
    public void markDisputed(String voucherNo) {
        jdbc.update(
                "UPDATE custody_voucher SET status = 'DISPUTED', updated_at = CURRENT_TIMESTAMP WHERE voucher_no = ?",
                voucherNo);
    }

    /**
     * 条件置争议：仅当状态为 EFFECTIVE 时更新为 DISPUTED，返回受影响行数；
     * 用于并发封账/争议的唯一结果裁决。
     */
    public int markDisputedIfEffective(String voucherNo) {
        return jdbc.update(
                "UPDATE custody_voucher SET status = 'DISPUTED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE voucher_no = ? AND status = 'EFFECTIVE'",
                voucherNo);
    }

    /** 封账终态。 */
    public void markSealed(String voucherNo, Instant sealedAt) {
        jdbc.update(
                "UPDATE custody_voucher SET status = 'SEALED', sealed_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE voucher_no = ?",
                Timestamp.from(sealedAt), voucherNo);
    }

    /**
     * 条件封账：仅当状态仍为 EFFECTIVE/DISPUTED 时更新为 SEALED，
     * 返回受影响行数；用于并发封账/争议的唯一结果裁决。
     */
    public int sealIfOpen(String voucherNo, Instant sealedAt) {
        return jdbc.update(
                "UPDATE custody_voucher SET status = 'SEALED', sealed_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE voucher_no = ? AND status IN ('EFFECTIVE', 'DISPUTED')",
                Timestamp.from(sealedAt), voucherNo);
    }

    /** 插入争议；已有争议或键冲突返回 false。 */
    public boolean insertDispute(String voucherNo, String requestId, String party, String reason, Instant now) {
        try {
            jdbc.update(
                    "INSERT INTO voucher_dispute (voucher_no, request_id, party, reason, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    voucherNo, requestId, party, reason, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 是否存在有效争议。 */
    public boolean disputeExists(String voucherNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_dispute WHERE voucher_no = ?", Integer.class, voucherNo);
        return count != null && count > 0;
    }

    /** 插入迟到读数差异记录。 */
    public void insertReadingDiff(String voucherNo, long readingId, String stationCode, LocalDate gasDate,
                                  BigDecimal volume, BigDecimal heatingValue) {
        jdbc.update(
                "INSERT INTO voucher_reading_diff (voucher_no, reading_id, station_code, gas_date, "
                        + "volume, heating_value, reason) VALUES (?, ?, ?, ?, ?, ?, 'LATE')",
                voucherNo, readingId, stationCode, java.sql.Date.valueOf(gasDate), volume, heatingValue);
    }

    /** 查询某原始凭证的调整凭证。 */
    public VoucherRow findAdjustmentOf(String originalVoucherNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM custody_voucher WHERE original_voucher_no = ? "
                            + "AND voucher_type = 'ADJUSTMENT' ORDER BY id LIMIT 1",
                    ROW_MAPPER, originalVoucherNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 占用“站＋气量日”原始凭证名额；并发重复占用抛 {@link DuplicateKeyException}。
     */
    public void occupyStationDay(String stationCode, LocalDate gasDate, String voucherNo) {
        jdbc.update(
                "INSERT INTO station_day_voucher (station_code, gas_date, voucher_no) VALUES (?, ?, ?)",
                stationCode, java.sql.Date.valueOf(gasDate), voucherNo);
    }

    /** 查询占用某站某日的原始凭证号；未占用返回 null。 */
    public String findOccupiedVoucherNo(String stationCode, LocalDate gasDate) {
        List<String> values = jdbc.query(
                "SELECT voucher_no FROM station_day_voucher WHERE station_code = ? AND gas_date = ?",
                (rs, rowNum) -> rs.getString("voucher_no"),
                stationCode, java.sql.Date.valueOf(gasDate));
        return values.isEmpty() ? null : values.get(0);
    }
}
