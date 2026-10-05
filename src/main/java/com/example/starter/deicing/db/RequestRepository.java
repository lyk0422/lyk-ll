package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 除冰排队记录数据访问。
 */
@Repository
public class RequestRepository {

    private static final String COLUMNS = """
            id, request_id, flight_number, stand_code, deicing_type, required_bays,
            duration_minutes, target_takeoff_time, deadline, status, scheduled_start,
            scheduled_end, actual_start, actual_end, hot_minutes, hot_risk_reason,
            earliest_reassign_time, actual_takeoff_time, fingerprint, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public RequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<DeicingRequestPo> MAPPER = (ResultSet rs, int rowNum) -> map(rs);

    static DeicingRequestPo map(ResultSet rs) throws SQLException {
        DeicingRequestPo po = new DeicingRequestPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setSeq(rs.getLong("id"));
        po.setFlightNumber(rs.getString("flight_number"));
        po.setStandCode(rs.getString("stand_code"));
        po.setType(DeicingType.valueOf(rs.getString("deicing_type")));
        po.setRequiredBays(rs.getInt("required_bays"));
        po.setDurationMinutes(rs.getInt("duration_minutes"));
        po.setTargetTakeoffTime(RowMappers.instant(rs, "target_takeoff_time"));
        po.setDeadline(RowMappers.instant(rs, "deadline"));
        po.setStatus(RequestStatus.valueOf(rs.getString("status")));
        po.setScheduledStart(RowMappers.instant(rs, "scheduled_start"));
        po.setScheduledEnd(RowMappers.instant(rs, "scheduled_end"));
        po.setActualStart(RowMappers.instant(rs, "actual_start"));
        po.setActualEnd(RowMappers.instant(rs, "actual_end"));
        int hot = rs.getInt("hot_minutes");
        po.setHotMinutes(rs.wasNull() ? null : hot);
        po.setHotRiskReason(rs.getString("hot_risk_reason"));
        po.setEarliestReassignTime(RowMappers.instant(rs, "earliest_reassign_time"));
        po.setActualTakeoffTime(RowMappers.instant(rs, "actual_takeoff_time"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        po.setUpdatedAt(RowMappers.instant(rs, "updated_at"));
        return po;
    }

    /**
     * 插入排队记录，申请序号取自增主键。
     */
    public void insert(DeicingRequestPo po) {
        jdbcTemplate.update("""
                        INSERT INTO deicing_request (
                            request_id, flight_number, stand_code, deicing_type, required_bays,
                            duration_minutes, target_takeoff_time, deadline, status,
                            hot_minutes, fingerprint, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getFlightNumber(), po.getStandCode(), po.getType().name(),
                po.getRequiredBays(), po.getDurationMinutes(),
                Timestamp.from(po.getTargetTakeoffTime()), Timestamp.from(po.getDeadline()),
                po.getStatus().name(), po.getHotMinutes(), po.getFingerprint(),
                Timestamp.from(po.getCreatedAt()), Timestamp.from(po.getUpdatedAt()));
    }

    public DeicingRequestPo findByRequestId(String requestId) {
        List<DeicingRequestPo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM deicing_request WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<DeicingRequestPo> findAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM deicing_request ORDER BY id", MAPPER);
    }

    /**
     * 统计航班未终结（排队/已排定/作业中/HOT 风险/人工确认）的记录数。
     */
    public int countActiveByFlight(String flightNumber) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM deicing_request
                        WHERE flight_number = ?
                          AND status IN ('QUEUED', 'SCHEDULED', 'IN_PROGRESS', 'HOT_RISK',
                                         'MANUAL_REVIEW')
                        """,
                Integer.class, flightNumber);
        return count == null ? 0 : count;
    }

    /**
     * 排定结果落库：状态、作业窗口与 HOT 风险信息。
     */
    public void updateSchedule(DeicingRequestPo po) {
        jdbcTemplate.update("""
                        UPDATE deicing_request SET
                            status = ?, scheduled_start = ?, scheduled_end = ?,
                            hot_risk_reason = ?, earliest_reassign_time = ?, updated_at = ?
                        WHERE request_id = ?
                        """,
                po.getStatus().name(), ts(po.getScheduledStart()), ts(po.getScheduledEnd()),
                po.getHotRiskReason(), ts(po.getEarliestReassignTime()),
                ts(po.getUpdatedAt()), po.getRequestId());
    }

    public void updateActualStart(String requestId, RequestStatus status, Instant actualStart,
                                  Instant updatedAt) {
        jdbcTemplate.update("""
                        UPDATE deicing_request SET status = ?, actual_start = ?, updated_at = ?
                        WHERE request_id = ?
                        """,
                status.name(), Timestamp.from(actualStart), Timestamp.from(updatedAt), requestId);
    }

    /**
     * 作业完成登记：写入实际完成时刻；库存结算在同一事务内由调用方完成。
     */
    public void updateActualEnd(String requestId, Instant actualEnd, Instant updatedAt) {
        jdbcTemplate.update("""
                        UPDATE deicing_request SET actual_end = ?, updated_at = ?
                        WHERE request_id = ?
                        """,
                Timestamp.from(actualEnd), Timestamp.from(updatedAt), requestId);
    }

    public void updateTakeoffResult(String requestId, RequestStatus status, Instant actualTakeoffTime,
                                    Instant updatedAt) {
        jdbcTemplate.update("""
                        UPDATE deicing_request SET status = ?, actual_takeoff_time = ?, updated_at = ?
                        WHERE request_id = ?
                        """,
                status.name(), Timestamp.from(actualTakeoffTime), Timestamp.from(updatedAt), requestId);
    }

    public void updateStatus(String requestId, RequestStatus status, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE deicing_request SET status = ?, updated_at = ? WHERE request_id = ?",
                status.name(), Timestamp.from(updatedAt), requestId);
    }

    /**
     * HOT 风险航班重排：更新目标起飞时刻并回到排队态，清除旧排定与风险标记。
     */
    public void updateReschedule(String requestId, Instant newTargetTakeoffTime, Instant newDeadline,
                                 Instant updatedAt) {
        jdbcTemplate.update("""
                        UPDATE deicing_request SET
                            target_takeoff_time = ?, deadline = ?, status = 'QUEUED',
                            scheduled_start = NULL, scheduled_end = NULL,
                            hot_risk_reason = NULL, earliest_reassign_time = NULL,
                            updated_at = ?
                        WHERE request_id = ?
                        """,
                Timestamp.from(newTargetTakeoffTime), Timestamp.from(newDeadline),
                Timestamp.from(updatedAt), requestId);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
