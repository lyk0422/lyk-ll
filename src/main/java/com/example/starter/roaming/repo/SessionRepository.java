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
import org.springframework.stereotype.Repository;

/**
 * 充电会话数据访问。
 */
@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 会话行。 */
    public record SessionRow(
            Long id, String sessionNo, String certFingerprint, String pileNo,
            Long homeOperatorId, Long servingOperatorId, String status,
            Instant startTime, Instant endTime,
            LocalDate settlementDay, BigDecimal totalEnergyKwh,
            Integer accountedSeq, Long batchId,
            Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<SessionRow> MAPPER = (rs, n) -> new SessionRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getString("cert_fingerprint"),
            rs.getString("pile_no"),
            rs.getLong("home_operator_id"),
            rs.getLong("serving_operator_id"),
            rs.getString("status"),
            rs.getTimestamp("start_time_utc").toInstant(),
            timestampToInstant(rs.getTimestamp("end_time_utc")),
            rs.getDate("settlement_day") == null ? null : rs.getDate("settlement_day").toLocalDate(),
            rs.getBigDecimal("total_energy_kwh"),
            rs.getInt("accounted_seq"),
            rs.getObject("batch_id", Long.class),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private static Instant timestampToInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static final String COLUMNS =
            "id, session_no, cert_fingerprint, pile_no, home_operator_id, serving_operator_id, status, "
                    + "start_time_utc, end_time_utc, settlement_day, total_energy_kwh, accounted_seq, batch_id, "
                    + "created_at_utc, updated_at_utc";

    public Optional<SessionRow> findBySessionNo(String sessionNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM charging_session WHERE session_no = ?", MAPPER, sessionNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<SessionRow> findByFingerprintAndTime(String fingerprint, Instant startTime) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM charging_session WHERE cert_fingerprint = ? AND start_time_utc = ?",
                    MAPPER, fingerprint, Timestamp.from(startTime)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 某 UTC 日已结束且尚未归属任何批次的会话（批次封存候选集）。
     */
    public List<SessionRow> findEndedUnassignedByDay(LocalDate day) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM charging_session "
                        + "WHERE settlement_day = ? AND status = 'ENDED' AND batch_id IS NULL "
                        + "ORDER BY id",
                MAPPER, java.sql.Date.valueOf(day));
    }

    /**
     * 新增会话；cert_fingerprint+start_time_utc 唯一约束裁决“同车同刻唯一会话”。
     */
    public void insert(SessionRow row) {
        jdbc.update(
                "INSERT INTO charging_session (session_no, cert_fingerprint, pile_no, home_operator_id, "
                        + "serving_operator_id, status, start_time_utc, end_time_utc, settlement_day, "
                        + "total_energy_kwh, accounted_seq, batch_id, created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, -1, NULL, ?, ?)",
                row.sessionNo(), row.certFingerprint(), row.pileNo(), row.homeOperatorId(),
                row.servingOperatorId(), row.status(), Timestamp.from(row.startTime()),
                row.totalEnergyKwh(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt()));
    }

    /** 授权会话进入充电中。 */
    public void markCharging(Long id, Instant now) {
        jdbc.update("UPDATE charging_session SET status = 'CHARGING', updated_at_utc = ? WHERE id = ?",
                Timestamp.from(now), id);
    }

    /** 会话结束，写入结束时刻与 UTC 归属日。 */
    public void markEnded(Long id, Instant endTime, LocalDate settlementDay, BigDecimal totalEnergy, Instant now) {
        jdbc.update(
                "UPDATE charging_session SET status = 'ENDED', end_time_utc = ?, settlement_day = ?, "
                        + "total_energy_kwh = ?, updated_at_utc = ? WHERE id = ?",
                Timestamp.from(endTime), java.sql.Date.valueOf(settlementDay), totalEnergy,
                Timestamp.from(now), id);
    }

    /** 仅更新归位后的累计电量。 */
    public void updateTotalEnergy(Long id, BigDecimal totalEnergy, Instant now) {
        jdbc.update("UPDATE charging_session SET total_energy_kwh = ?, updated_at_utc = ? WHERE id = ?",
                totalEnergy, Timestamp.from(now), id);
    }

    /**
     * 条件化归属批次：仅当会话尚未归属批次时成功，配合日清分锁保证唯一归属。
     *
     * @return 更新行数；0 表示会话已被其他批次归属
     */
    public int assignBatchIfUnassigned(Long sessionId, Long batchId, Integer accountedSeq,
                                       BigDecimal accountedEnergy, Instant now) {
        return jdbc.update(
                "UPDATE charging_session SET batch_id = ?, accounted_seq = ?, total_energy_kwh = ?, "
                        + "updated_at_utc = ? WHERE id = ? AND batch_id IS NULL",
                batchId, accountedSeq, accountedEnergy, Timestamp.from(now), sessionId);
    }
}
