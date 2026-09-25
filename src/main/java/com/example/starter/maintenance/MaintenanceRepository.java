package com.example.starter.maintenance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 维保台账数据访问：周期 15 日，恰第 15 日完成合规。
 */
@Repository
public class MaintenanceRepository {

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 维保记录。
     *
     * @param id          主键
     * @param requestId   登记幂等键
     * @param fingerprint 登记指纹
     * @param elevatorId  电梯ID
     * @param staff1Id    维保人员1（持证）
     * @param staff2Id    维保人员2（持证，可空）
     * @param caseId      关联救援单（可空）
     * @param periodStart 周期起始（UTC 日界）
     * @param dueDate     应完成日期 = 周期起始 + 15 日
     * @param completedAt 完成时间（UTC）
     * @param result      COMPLIANT/OVERDUE/LATE_EVENT
     * @param createdAt   登记时间（UTC）
     */
    public record MaintenanceRecord(long id, String requestId, String fingerprint, long elevatorId,
                                    long staff1Id, Long staff2Id, Long caseId,
                                    LocalDate periodStart, LocalDate dueDate, Instant completedAt,
                                    String result, Instant createdAt) {
    }

    /** 维保周期天数：恰第 15 日完成合规。 */
    public static final long CYCLE_DAYS = 15;

    private static MaintenanceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MaintenanceRecord(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("elevator_id"),
                rs.getLong("staff1_id"),
                rs.getObject("staff2_id") == null ? null : rs.getLong("staff2_id"),
                rs.getObject("case_id") == null ? null : rs.getLong("case_id"),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                rs.getObject("completed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getString("result"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /**
     * 按幂等键查询。
     */
    public Optional<MaintenanceRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM maintenance_record WHERE request_id = ?", MaintenanceRepository::map,
                        requestId)
                .stream().findFirst();
    }

    /**
     * 新增维保记录。
     */
    public long insert(MaintenanceRecord r) {
        jdbc.update("INSERT INTO maintenance_record (request_id, fingerprint, elevator_id, staff1_id, staff2_id,"
                        + " case_id, period_start, due_date, completed_at, result, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.requestId(), r.fingerprint(), r.elevatorId(), r.staff1Id(), r.staff2Id(), r.caseId(),
                r.periodStart(), r.dueDate(), LocalDateTime.ofInstant(r.completedAt(), ZoneOffset.UTC),
                r.result(), LocalDateTime.ofInstant(r.createdAt(), ZoneOffset.UTC));
        return jdbc.queryForObject("SELECT id FROM maintenance_record WHERE request_id = ?", Long.class,
                r.requestId());
    }

    /**
     * 查询电梯维保台账（按完成时间升序，可复算）。
     */
    public List<MaintenanceRecord> listByElevator(long elevatorId) {
        return jdbc.query("SELECT * FROM maintenance_record WHERE elevator_id = ? ORDER BY completed_at, id",
                MaintenanceRepository::map, elevatorId);
    }
}
