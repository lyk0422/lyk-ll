package com.example.starter.metro.repo;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 清分数据访问：清分明细、日结批次与对账差异。
 */
@Repository
public class ClearingRepository {

    /** 清分明细行。 */
    public record Detail(long id, LocalDate batchDate, long tripId, String txNo, String lineCode,
            int distanceKm, BigDecimal ratio, long amountFen, String detailType) {
    }

    /** 日结批次行。 */
    public record Batch(LocalDate batchDate, String requestId, String status, long totalFen,
            LocalDateTime sealedAt) {
    }

    /** 对账差异行。 */
    public record Diff(long id, LocalDate batchDate, LocalDate postedBatchDate, String txNo,
            Long tripId, String reason) {
    }

    public static final String DETAIL_SETTLE = "SETTLE";
    public static final String DETAIL_PROVISIONAL = "PROVISIONAL";
    public static final String DETAIL_REVERSAL = "REVERSAL";

    public static final String BATCH_SEALED = "SEALED";

    public static final String REASON_LATE_AFTER_SEAL = "LATE_AFTER_SEAL";

    private final JdbcTemplate jdbcTemplate;

    public ClearingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertDetail(LocalDate batchDate, long tripId, String txNo, String lineCode,
            int distanceKm, BigDecimal ratio, long amountFen, String detailType,
            LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO clearing_detail(batch_date, trip_id, tx_no, line_code, distance_km,"
                        + " ratio, amount_fen, detail_type, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf(batchDate), tripId, txNo, lineCode, distanceKm, ratio, amountFen,
                detailType, Timestamp.valueOf(now));
    }

    public List<Detail> detailsForBatch(LocalDate batchDate) {
        return jdbcTemplate.query(
                "SELECT * FROM clearing_detail WHERE batch_date = ? ORDER BY id",
                detailMapper(), Date.valueOf(batchDate));
    }

    public List<Detail> detailsForTrip(long tripId) {
        return jdbcTemplate.query(
                "SELECT * FROM clearing_detail WHERE trip_id = ? ORDER BY id",
                detailMapper(), tripId);
    }

    public long sumForBatch(LocalDate batchDate) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_fen),0) FROM clearing_detail WHERE batch_date = ?",
                Long.class, Date.valueOf(batchDate));
        return sum == null ? 0L : sum;
    }

    public Optional<Batch> findBatch(LocalDate batchDate) {
        List<Batch> rows = jdbcTemplate.query(
                "SELECT * FROM settlement_batch WHERE batch_date = ?",
                (rs, rowNum) -> new Batch(
                        rs.getDate("batch_date").toLocalDate(),
                        rs.getString("request_id"),
                        rs.getString("status"),
                        rs.getLong("total_fen"),
                        rs.getTimestamp("sealed_at") == null ? null
                                : rs.getTimestamp("sealed_at").toLocalDateTime()),
                Date.valueOf(batchDate));
        return rows.stream().findFirst();
    }

    /**
     * 封账写入批次（终态），返回是否写入成功；已存在（含并发封账竞态）则返回 false。
     */
    public boolean insertSealedBatch(LocalDate batchDate, String requestId, long totalFen,
            LocalDateTime sealedAt) {
        int updated = jdbcTemplate.update(
                "INSERT INTO settlement_batch(batch_date, request_id, status, total_fen, sealed_at)"
                        + " SELECT ?,?,?,?,? WHERE NOT EXISTS"
                        + " (SELECT 1 FROM settlement_batch WHERE batch_date = ?)",
                Date.valueOf(batchDate), requestId, BATCH_SEALED, totalFen,
                Timestamp.valueOf(sealedAt), Date.valueOf(batchDate));
        return updated > 0;
    }

    public void insertDiff(LocalDate batchDate, LocalDate postedBatchDate, String txNo,
            Long tripId, String reason, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO diff_record(batch_date, posted_batch_date, tx_no, trip_id, reason,"
                        + " created_at) VALUES (?,?,?,?,?,?)",
                Date.valueOf(batchDate), Date.valueOf(postedBatchDate), txNo, tripId, reason,
                Timestamp.valueOf(now));
    }

    public List<Diff> diffsFor(LocalDate batchDate) {
        return jdbcTemplate.query(
                "SELECT * FROM diff_record WHERE batch_date = ? ORDER BY id",
                (rs, rowNum) -> new Diff(
                        rs.getLong("id"),
                        rs.getDate("batch_date").toLocalDate(),
                        rs.getDate("posted_batch_date").toLocalDate(),
                        rs.getString("tx_no"),
                        rs.getObject("trip_id") == null ? null : rs.getLong("trip_id"),
                        rs.getString("reason")),
                Date.valueOf(batchDate));
    }

    private org.springframework.jdbc.core.RowMapper<Detail> detailMapper() {
        return (rs, rowNum) -> new Detail(
                rs.getLong("id"),
                rs.getDate("batch_date").toLocalDate(),
                rs.getLong("trip_id"),
                rs.getString("tx_no"),
                rs.getString("line_code"),
                rs.getInt("distance_km"),
                rs.getBigDecimal("ratio"),
                rs.getLong("amount_fen"),
                rs.getString("detail_type"));
    }
}
