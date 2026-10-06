package com.example.starter.rolling.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轧辊寿命台账访问层：台账、换辊实绩、迟到事件。
 * 幂等记录复用 {@link RollingScheduleRepository} 的 idempotent_record 表。
 */
@Repository
public class RollLedgerRepository {

    private final JdbcTemplate jdbc;

    public RollLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 轧辊台账行。on_machine/banned 为 0/1；frozen_at 非空表示日冻结时刻。 */
    public record LedgerRow(String rollNo, BigDecimal cycleLimitKm, BigDecimal totalKm,
                            int coilsUsed, boolean onMachine, boolean banned,
                            LocalDateTime frozenAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 换辊实绩行。firstUse 为 0/1，标记新辊上机后前 5 卷。 */
    public record RollActualRow(long id, String rollNo, BigDecimal rollingKm,
                                LocalDateTime recordedAt, boolean firstUse,
                                String requestId, String fingerprint, LocalDateTime createdAt) {
    }

    private static final RowMapper<LedgerRow> LEDGER_MAPPER = (rs, n) -> new LedgerRow(
            rs.getString("roll_no"), rs.getBigDecimal("cycle_limit_km"),
            rs.getBigDecimal("total_km"), rs.getInt("coils_used"),
            rs.getBoolean("on_machine"), rs.getBoolean("banned"),
            rs.getTimestamp("frozen_at") == null ? null : rs.getTimestamp("frozen_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<RollActualRow> ACTUAL_MAPPER = (rs, n) -> new RollActualRow(
            rs.getLong("id"), rs.getString("roll_no"), rs.getBigDecimal("rolling_km"),
            rs.getTimestamp("recorded_at").toLocalDateTime(), rs.getBoolean("first_use"),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toLocalDateTime());

    // ---------------- 台账 ----------------

    public LedgerRow findLedger(String rollNo) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT * FROM roll_ledger WHERE roll_no = ?", LEDGER_MAPPER, rollNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 与实绩入库同一事务内累加里程与卷数；累计达周期上限时同行原子置禁排。
     */
    public void addMileage(String rollNo, BigDecimal km, LocalDateTime now) {
        jdbc.update("""
                UPDATE roll_ledger
                SET total_km = total_km + ?, coils_used = coils_used + 1,
                    banned = CASE WHEN total_km + ? >= cycle_limit_km THEN 1 ELSE banned END,
                    updated_at = ?
                WHERE roll_no = ?
                """, km, km, Timestamp.valueOf(now), rollNo);
    }

    /**
     * 轧辊上机：置在机标记。寿命到期校验由服务层在调用前完成。
     */
    public void mount(String rollNo, LocalDateTime now) {
        jdbc.update("UPDATE roll_ledger SET on_machine = 1, updated_at = ? WHERE roll_no = ?",
                Timestamp.valueOf(now), rollNo);
    }

    /**
     * 日冻结：冻结全部未冻结轧辊，返回本次冻结数量；已冻结的不重复更新。
     */
    public int markAllFrozen(LocalDateTime now) {
        return jdbc.update(
                "UPDATE roll_ledger SET frozen_at = ?, updated_at = ? WHERE frozen_at IS NULL",
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    // ---------------- 换辊实绩 ----------------

    public void insertActual(RollActualRow row) {
        jdbc.update("""
                INSERT INTO roll_change_actual
                  (id, roll_no, rolling_km, recorded_at, first_use, request_id, fingerprint, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                row.id(), row.rollNo(), row.rollingKm(), Timestamp.valueOf(row.recordedAt()),
                row.firstUse(), row.requestId(), row.fingerprint(),
                Timestamp.valueOf(row.createdAt()));
    }

    /**
     * 按“轧辊号＋实绩时刻”查重。
     */
    public RollActualRow findActual(String rollNo, LocalDateTime recordedAt) {
        List<RollActualRow> rows = jdbc.query(
                "SELECT * FROM roll_change_actual WHERE roll_no = ? AND recorded_at = ?",
                ACTUAL_MAPPER, rollNo, Timestamp.valueOf(recordedAt));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 某轧辊全部实绩，按实绩时刻升序归位（乱序回传不影响查询顺序）。
     */
    public List<RollActualRow> findActualsByRoll(String rollNo) {
        return jdbc.query(
                "SELECT * FROM roll_change_actual WHERE roll_no = ? ORDER BY recorded_at, id",
                ACTUAL_MAPPER, rollNo);
    }

    /**
     * 由实绩逐条复算累计里程（公里），用于核对台账可复算。
     */
    public BigDecimal sumActualKm(String rollNo) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(rolling_km), 0) FROM roll_change_actual WHERE roll_no = ?",
                BigDecimal.class, rollNo);
        return total == null ? BigDecimal.ZERO : total;
    }

    // ---------------- 迟到事件 ----------------

    /**
     * 插入日冻结后的迟到实绩事件（不改已冻结里程与禁排结论）。
     */
    public void insertLateEvent(long id, String rollNo, String requestId, BigDecimal rollingKm,
                                LocalDateTime recordedAt, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO roll_late_event
                  (id, roll_no, request_id, rolling_km, recorded_at, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                id, rollNo, requestId, rollingKm,
                Timestamp.valueOf(recordedAt), Timestamp.valueOf(now));
    }

    public long countLateEvents(String rollNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = ?", Long.class, rollNo);
        return count == null ? 0L : count;
    }
}
