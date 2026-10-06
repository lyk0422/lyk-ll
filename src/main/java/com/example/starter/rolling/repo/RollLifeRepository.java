package com.example.starter.rolling.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轧辊寿命台账访问层：轧辊台账、换辊实绩、迟到事件，以及共享幂等记录表。
 */
@Repository
public class RollLifeRepository {

    private final JdbcTemplate jdbc;

    public RollLifeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 轧辊台账行。status 为 IN_SERVICE/BANNED；totalKm 为累计实绩里程（公里）。 */
    public record RollRow(String rollNo, String status, BigDecimal totalKm, BigDecimal cycleLimitKm,
                          int coilsSinceMount, LocalDateTime mountedAt, LocalDateTime frozenAt,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 换辊实绩行。firstUse 为新辊上机后前 5 卷首用标记。 */
    public record RollActualRow(long id, String rollNo, BigDecimal rollingKm, boolean firstUse,
                                LocalDateTime recordedAt, String requestId, String fingerprint,
                                LocalDateTime createdAt) {
    }

    /** 幂等记录行。 */
    public record IdempotentRow(String requestId, String bizType, String fingerprint,
                                int httpStatus, String responseBody, LocalDateTime createdAt) {
    }

    private static final RowMapper<RollRow> ROLL_MAPPER = (rs, n) -> new RollRow(
            rs.getString("roll_no"), rs.getString("status"), rs.getBigDecimal("total_km"),
            rs.getBigDecimal("cycle_limit_km"), rs.getInt("coils_since_mount"),
            rs.getTimestamp("mounted_at").toLocalDateTime(),
            rs.getTimestamp("frozen_at") == null ? null : rs.getTimestamp("frozen_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<RollActualRow> ACTUAL_MAPPER = (rs, n) -> new RollActualRow(
            rs.getLong("id"), rs.getString("roll_no"), rs.getBigDecimal("rolling_km"),
            rs.getBoolean("first_use"), rs.getTimestamp("recorded_at").toLocalDateTime(),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"), rs.getString("biz_type"), rs.getString("fingerprint"),
            rs.getInt("http_status"), rs.getString("response_body"),
            rs.getTimestamp("created_at").toLocalDateTime());

    // ---------------- 轧辊台账 ----------------

    public void insertRoll(RollRow row) {
        jdbc.update("""
                INSERT INTO roll_life
                  (roll_no, status, total_km, cycle_limit_km, coils_since_mount,
                   mounted_at, frozen_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                row.rollNo(), row.status(), row.totalKm(), row.cycleLimitKm(), row.coilsSinceMount(),
                Timestamp.valueOf(row.mountedAt()),
                row.frozenAt() == null ? null : Timestamp.valueOf(row.frozenAt()),
                Timestamp.valueOf(row.createdAt()), Timestamp.valueOf(row.updatedAt()));
    }

    public RollRow findRoll(String rollNo) {
        List<RollRow> rows = jdbc.query(
                "SELECT * FROM roll_life WHERE roll_no = ?", ROLL_MAPPER, rollNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 重新上机：重置本班卷数并恢复在役状态，累计里程与周期上限保持台账原值。
     */
    public void remount(String rollNo, LocalDateTime mountedAt, LocalDateTime now) {
        jdbc.update("""
                UPDATE roll_life
                SET status = 'IN_SERVICE', coils_since_mount = 0, mounted_at = ?, updated_at = ?
                WHERE roll_no = ?
                """, Timestamp.valueOf(mountedAt), Timestamp.valueOf(now), rollNo);
    }

    /**
     * 原子累计里程：仅当累计后不超周期上限时生效，数据库裁决并发回报。
     *
     * @return true 累计成功；false 表示超限，本次实绩不得入库（事务回滚）
     */
    public boolean addMileageIfWithinLimit(String rollNo, BigDecimal km, LocalDateTime now) {
        int affected = jdbc.update("""
                UPDATE roll_life
                SET total_km = total_km + ?, coils_since_mount = coils_since_mount + 1, updated_at = ?
                WHERE roll_no = ? AND total_km + ? <= cycle_limit_km
                """, km, Timestamp.valueOf(now), rollNo, km);
        return affected == 1;
    }

    /**
     * 累计里程达到周期上限即禁排（恰达上限也禁排）。
     */
    public void banIfReachedLimit(String rollNo, LocalDateTime now) {
        jdbc.update("""
                UPDATE roll_life SET status = 'BANNED', updated_at = ?
                WHERE roll_no = ? AND total_km >= cycle_limit_km AND status <> 'BANNED'
                """, Timestamp.valueOf(now), rollNo);
    }

    public void markFrozen(String rollNo, LocalDateTime now) {
        jdbc.update("""
                UPDATE roll_life SET frozen_at = ?, updated_at = ? WHERE roll_no = ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), rollNo);
    }

    /**
     * 复算回写台账：total_km/coils_since_mount 由实绩记录重算，禁排状态按上限重判。
     */
    public void rewriteLedger(String rollNo, BigDecimal totalKm, int coilsSinceMount,
                              boolean banned, LocalDateTime now) {
        jdbc.update("""
                UPDATE roll_life
                SET total_km = ?, coils_since_mount = ?, status = ?, updated_at = ?
                WHERE roll_no = ?
                """, totalKm, coilsSinceMount, banned ? "BANNED" : "IN_SERVICE",
                Timestamp.valueOf(now), rollNo);
    }

    // ---------------- 换辊实绩 ----------------

    public void insertActual(RollActualRow row) {
        jdbc.update("""
                INSERT INTO roll_change_actual
                  (id, roll_no, rolling_km, first_use, recorded_at, request_id, fingerprint, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                row.id(), row.rollNo(), row.rollingKm(), row.firstUse(),
                Timestamp.valueOf(row.recordedAt()), row.requestId(), row.fingerprint(),
                Timestamp.valueOf(row.createdAt()));
    }

    public RollActualRow findActual(String rollNo, LocalDateTime recordedAt) {
        List<RollActualRow> rows = jdbc.query(
                "SELECT * FROM roll_change_actual WHERE roll_no = ? AND recorded_at = ?",
                ACTUAL_MAPPER, rollNo, Timestamp.valueOf(recordedAt));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按实绩时刻归位后的全部实绩（乱序回传按 recorded_at 排序，同时刻按 id 稳定次序）。
     */
    public List<RollActualRow> findActualsByRoll(String rollNo) {
        return jdbc.query(
                "SELECT * FROM roll_change_actual WHERE roll_no = ? ORDER BY recorded_at, id",
                ACTUAL_MAPPER, rollNo);
    }

    /**
     * 本次上机后、实绩时刻早于 recordedAt 的已入库卷数，用于判定首用烫辊位（前 5 卷）。
     */
    public int countCoilsSinceMountBefore(String rollNo, LocalDateTime mountedAt,
                                          LocalDateTime recordedAt) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM roll_change_actual
                WHERE roll_no = ? AND recorded_at >= ? AND recorded_at < ?
                """, Integer.class, rollNo, Timestamp.valueOf(mountedAt),
                Timestamp.valueOf(recordedAt));
        return count == null ? 0 : count;
    }

    public int countCoilsSinceMount(String rollNo, LocalDateTime mountedAt) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM roll_change_actual
                WHERE roll_no = ? AND recorded_at >= ?
                """, Integer.class, rollNo, Timestamp.valueOf(mountedAt));
        return count == null ? 0 : count;
    }

    public BigDecimal sumActualKm(String rollNo) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(rolling_km), 0) FROM roll_change_actual WHERE roll_no = ?",
                BigDecimal.class, rollNo);
        return total == null ? BigDecimal.ZERO : total;
    }

    public void updateFirstUse(long actualId, boolean firstUse) {
        jdbc.update("UPDATE roll_change_actual SET first_use = ? WHERE id = ?", firstUse, actualId);
    }

    // ---------------- 迟到事件 ----------------

    public void insertLateEvent(long id, String rollNo, String requestId, BigDecimal rollingKm,
                                LocalDateTime recordedAt, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO roll_late_event
                  (id, roll_no, request_id, rolling_km, recorded_at, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                id, rollNo, requestId, rollingKm, Timestamp.valueOf(recordedAt),
                Timestamp.valueOf(now));
    }

    public long countLateEvents(String rollNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = ?", Long.class, rollNo);
        return count == null ? 0L : count;
    }

    // ---------------- 幂等记录（与排产域共享 idempotent_record 表） ----------------

    public IdempotentRow findIdempotent(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT * FROM idempotent_record WHERE request_id = ?", IDEMPOTENT_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * @return true 插入成功；false 表示同 requestId 记录已存在（并发重放）
     */
    public boolean tryInsertIdempotent(String requestId, String bizType, String fingerprint,
                                       int httpStatus, String responseBody, LocalDateTime now) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_record
                      (request_id, biz_type, fingerprint, http_status, response_body, created_at)
                    VALUES (?,?,?,?,?,?)
                    """,
                    requestId, bizType, fingerprint, httpStatus, responseBody,
                    Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
