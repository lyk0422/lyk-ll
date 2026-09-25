package com.example.starter.roaming.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 清分相关数据访问：日清分锁、批次、明细、差异挂账与核销留痕。
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 日清分锁行。 */
    public record DayLockRow(LocalDate settlementDay, boolean sealed, Long batchId) {
    }

    /** 清分批行。 */
    public record BatchRow(Long id, String batchNo, LocalDate settlementDay, String status,
                           Integer sessionCount, BigDecimal totalEnergyKwh,
                           BigDecimal totalReceivable, BigDecimal totalPayable, Instant sealedAt) {
    }

    /** 清分明细行。 */
    public record DetailRow(Long id, Long batchId, Long sessionId, String sessionNo,
                            Long homeOperatorId, Long servingOperatorId,
                            BigDecimal energyKwh, BigDecimal receivableAmount, BigDecimal payableAmount) {
    }

    /** 挂账行。 */
    public record SuspenseRow(Long id, String suspenseNo, Long sessionId, String sessionNo,
                              Long batchId, Integer readingSeq, BigDecimal energyDeltaKwh,
                              BigDecimal receivableDelta, BigDecimal payableDelta,
                              String reason, String status, Instant createdAt) {
    }

    /** 核销留痕行。 */
    public record WriteOffRow(Long id, Long suspenseId, String requestId, String operatorName,
                              String remark, Instant writtenOffAt) {
    }

    private static final RowMapper<DayLockRow> DAY_LOCK_MAPPER = (rs, n) -> new DayLockRow(
            rs.getDate("settlement_day").toLocalDate(),
            rs.getInt("sealed") == 1,
            rs.getObject("batch_id", Long.class));

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getDate("settlement_day").toLocalDate(),
            rs.getString("status"),
            rs.getInt("session_count"),
            rs.getBigDecimal("total_energy_kwh"),
            rs.getBigDecimal("total_receivable"),
            rs.getBigDecimal("total_payable"),
            rs.getTimestamp("sealed_at_utc").toInstant());

    private static final RowMapper<DetailRow> DETAIL_MAPPER = (rs, n) -> new DetailRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("session_id"),
            rs.getString("session_no"),
            rs.getLong("home_operator_id"),
            rs.getLong("serving_operator_id"),
            rs.getBigDecimal("energy_kwh"),
            rs.getBigDecimal("receivable_amount"),
            rs.getBigDecimal("payable_amount"));

    private static final RowMapper<SuspenseRow> SUSPENSE_MAPPER = (rs, n) -> new SuspenseRow(
            rs.getLong("id"),
            rs.getString("suspense_no"),
            rs.getLong("session_id"),
            rs.getString("session_no"),
            rs.getObject("batch_id", Long.class),
            rs.getInt("reading_seq"),
            rs.getBigDecimal("energy_delta_kwh"),
            rs.getBigDecimal("receivable_delta"),
            rs.getBigDecimal("payable_delta"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<WriteOffRow> WRITE_OFF_MAPPER = (rs, n) -> new WriteOffRow(
            rs.getLong("id"),
            rs.getLong("suspense_id"),
            rs.getString("request_id"),
            rs.getString("operator_name"),
            rs.getString("remark"),
            rs.getTimestamp("written_off_at_utc").toInstant());

    /**
     * 首次触及某 UTC 日时登记日锁；已存在（含并发事务已插入未提交）时绝不覆盖封存状态。
     * 并发同键插入在唯一约束处阻塞，先提交方胜出，后到方得到重复键异常并忽略。
     */
    public void insertDayLockIfAbsent(LocalDate day) {
        try {
            jdbc.update("INSERT INTO settlement_day_lock (settlement_day, sealed, batch_id) VALUES (?, 0, NULL)",
                    java.sql.Date.valueOf(day));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 日锁已存在，保持既有封存状态不变。
        }
    }

    /**
     * 行级锁定清分日并读取封存状态；会话结束与批次封存经此锁串行，保证会话唯一归属。
     */
    public DayLockRow lockDay(LocalDate day) {
        return jdbc.queryForObject(
                "SELECT settlement_day, sealed, batch_id FROM settlement_day_lock "
                        + "WHERE settlement_day = ? FOR UPDATE",
                DAY_LOCK_MAPPER, java.sql.Date.valueOf(day));
    }

    public void markDaySealed(LocalDate day, Long batchId) {
        jdbc.update("UPDATE settlement_day_lock SET sealed = 1, batch_id = ? WHERE settlement_day = ?",
                batchId, java.sql.Date.valueOf(day));
    }

    public Long insertBatch(BatchRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO settlement_batch (batch_no, settlement_day, status, session_count, "
                            + "total_energy_kwh, total_receivable, total_payable, sealed_at_utc) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchNo());
            ps.setDate(2, java.sql.Date.valueOf(row.settlementDay()));
            ps.setString(3, row.status());
            ps.setInt(4, row.sessionCount());
            ps.setBigDecimal(5, row.totalEnergyKwh());
            ps.setBigDecimal(6, row.totalReceivable());
            ps.setBigDecimal(7, row.totalPayable());
            ps.setTimestamp(8, Timestamp.from(row.sealedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<BatchRow> findBatchById(Long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, batch_no, settlement_day, status, session_count, total_energy_kwh, "
                            + "total_receivable, total_payable, sealed_at_utc FROM settlement_batch WHERE id = ?",
                    BATCH_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<BatchRow> findBatchByDay(LocalDate day) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, batch_no, settlement_day, status, session_count, total_energy_kwh, "
                            + "total_receivable, total_payable, sealed_at_utc FROM settlement_batch WHERE settlement_day = ?",
                    BATCH_MAPPER, java.sql.Date.valueOf(day)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertDetail(DetailRow row) {
        jdbc.update(
                "INSERT INTO settlement_detail (batch_id, session_id, session_no, home_operator_id, "
                        + "serving_operator_id, energy_kwh, receivable_amount, payable_amount) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchId(), row.sessionId(), row.sessionNo(), row.homeOperatorId(),
                row.servingOperatorId(), row.energyKwh(), row.receivableAmount(), row.payableAmount());
    }

    public List<DetailRow> findDetailsByBatch(Long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, session_id, session_no, home_operator_id, serving_operator_id, "
                        + "energy_kwh, receivable_amount, payable_amount FROM settlement_detail WHERE batch_id = ? ORDER BY id",
                DETAIL_MAPPER, batchId);
    }

    public Long insertSuspense(SuspenseRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO suspense_account (suspense_no, session_id, session_no, batch_id, reading_seq, "
                            + "energy_delta_kwh, receivable_delta, payable_delta, reason, status, created_at_utc) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.suspenseNo());
            ps.setLong(2, row.sessionId());
            ps.setString(3, row.sessionNo());
            if (row.batchId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, row.batchId());
            }
            ps.setInt(5, row.readingSeq());
            ps.setBigDecimal(6, row.energyDeltaKwh());
            ps.setBigDecimal(7, row.receivableDelta());
            ps.setBigDecimal(8, row.payableDelta());
            ps.setString(9, row.reason());
            ps.setString(10, row.status());
            ps.setTimestamp(11, Timestamp.from(row.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SuspenseRow> findSuspenseByNo(String suspenseNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, suspense_no, session_id, session_no, batch_id, reading_seq, energy_delta_kwh, "
                            + "receivable_delta, payable_delta, reason, status, created_at_utc "
                            + "FROM suspense_account WHERE suspense_no = ?",
                    SUSPENSE_MAPPER, suspenseNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int markSuspenseWrittenOff(Long suspenseId, Instant now) {
        return jdbc.update("UPDATE suspense_account SET status = 'WRITTEN_OFF' WHERE id = ? AND status = 'PENDING'",
                suspenseId);
    }

    public void insertWriteOff(WriteOffRow row) {
        jdbc.update(
                "INSERT INTO suspense_write_off (suspense_id, request_id, operator_name, remark, written_off_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.suspenseId(), row.requestId(), row.operatorName(), row.remark(),
                Timestamp.from(row.writtenOffAt()));
    }

    public Optional<WriteOffRow> findWriteOffBySuspense(Long suspenseId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, suspense_id, request_id, operator_name, remark, written_off_at_utc "
                            + "FROM suspense_write_off WHERE suspense_id = ?",
                    WRITE_OFF_MAPPER, suspenseId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
