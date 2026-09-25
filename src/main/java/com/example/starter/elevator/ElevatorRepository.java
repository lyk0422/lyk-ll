package com.example.starter.elevator;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 电梯台账数据访问：时间戳以 UTC 存取，停复梯状态变更须配合行锁（FOR UPDATE）。
 */
@Repository
public class ElevatorRepository {

    private final JdbcTemplate jdbc;

    public ElevatorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Instant → UTC 本地时间，避免 JVM 默认时区干扰。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Elevator map(ResultSet rs, int rowNum) throws SQLException {
        return new Elevator(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("status"),
                rs.getInt("key_supervision") != 0,
                rs.getObject("stop_case_id") == null ? null : rs.getLong("stop_case_id"),
                rs.getObject("last_maintained_on", LocalDate.class),
                rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /**
     * 按编号查询（不加锁）。
     */
    public Optional<Elevator> findByCode(String code) {
        return jdbc.query("SELECT * FROM elevator WHERE code = ?", ElevatorRepository::map, code)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<Elevator> findById(long id) {
        return jdbc.query("SELECT * FROM elevator WHERE id = ?", ElevatorRepository::map, id)
                .stream().findFirst();
    }

    /**
     * 按编号加行锁查询，用于接报/派单/停复梯等串行化状态变更。
     */
    public Optional<Elevator> findByCodeForUpdate(String code) {
        return jdbc.query("SELECT * FROM elevator WHERE code = ? FOR UPDATE", ElevatorRepository::map, code)
                .stream().findFirst();
    }

    /**
     * 新建电梯：初始运行中，最近维保日期取创建日（UTC 日界）。
     */
    public long insert(String code, LocalDate lastMaintainedOn, Instant now) {
        jdbc.update("INSERT INTO elevator (code, status, key_supervision, last_maintained_on, version, created_at)"
                        + " VALUES (?, 'RUNNING', 0, ?, 0, ?)",
                code, lastMaintainedOn, utc(now));
        Long id = jdbc.queryForObject("SELECT id FROM elevator WHERE code = ?", Long.class, code);
        return id;
    }

    /**
     * 更新运行状态与停梯关联案件。
     */
    public void updateStatus(long id, String status, Long stopCaseId) {
        jdbc.update("UPDATE elevator SET status = ?, stop_case_id = ?, version = version + 1 WHERE id = ?",
                status, stopCaseId, id);
    }

    /**
     * 更新重点监察标记。
     */
    public void updateKeySupervision(long id, boolean keySupervision) {
        jdbc.update("UPDATE elevator SET key_supervision = ?, version = version + 1 WHERE id = ?",
                keySupervision ? 1 : 0, id);
    }

    /**
     * 更新最近维保日期（UTC 日界）。
     */
    public void updateLastMaintainedOn(long id, LocalDate date) {
        jdbc.update("UPDATE elevator SET last_maintained_on = ?, version = version + 1 WHERE id = ?",
                date, id);
    }

    /**
     * 追加停复梯链记录。
     *
     * @param action STOP 停梯 / RESUME 复梯
     * @param reason 原因标识
     * @param caseId 关联救援单，可为 null
     */
    public void insertStopLog(long elevatorId, String action, String reason, Long caseId, Instant occurredAt) {
        jdbc.update("INSERT INTO elevator_stop_log (elevator_id, action, reason, case_id, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                elevatorId, action, reason, caseId, utc(occurredAt));
    }

    /**
     * 停复梯链记录。
     */
    public record StopLog(long id, long elevatorId, String action, String reason, Long caseId,
                          Instant occurredAt) {
    }

    /**
     * 查询电梯停复梯链（按发生时间升序，可复算状态流转）。
     */
    public java.util.List<StopLog> listStopLog(long elevatorId) {
        return jdbc.query("SELECT * FROM elevator_stop_log WHERE elevator_id = ? ORDER BY occurred_at, id",
                (rs, rowNum) -> new StopLog(
                        rs.getLong("id"),
                        rs.getLong("elevator_id"),
                        rs.getString("action"),
                        rs.getString("reason"),
                        rs.getObject("case_id") == null ? null : rs.getLong("case_id"),
                        rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                elevatorId);
    }
}
