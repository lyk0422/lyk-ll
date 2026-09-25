package com.example.starter.hazmat.repo;

import com.example.starter.hazmat.domain.CheckinResult;
import com.example.starter.hazmat.model.CheckinRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 押运打卡记录仓储；uk(waybill_id, generation, waypoint_seq) 与 uk(request_id)
 * 由数据库唯一约束保证。
 */
@Repository
public class CheckinRepository {

    private static final RowMapper<CheckinRecord> MAPPER = CheckinRepository::map;

    private final JdbcTemplate jdbc;

    public CheckinRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static CheckinRecord map(ResultSet rs, int i) throws SQLException {
        return new CheckinRecord(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("waybill_id"),
                rs.getInt("generation"),
                rs.getInt("waypoint_seq"),
                rs.getTimestamp("checkin_time").toLocalDateTime(),
                CheckinResult.valueOf(rs.getString("result")),
                rs.getString("message"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    public void insert(String requestId, String fingerprint, long waybillId, int generation,
                       int waypointSeq, LocalDateTime checkinTime, CheckinResult result,
                       String message, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO checkin_record (request_id, fingerprint, waybill_id, generation,"
                        + " waypoint_seq, checkin_time, result, message, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, fingerprint, waybillId, generation, waypointSeq,
                Timestamp.valueOf(checkinTime), result.name(), message, Timestamp.valueOf(now));
    }

    public Optional<CheckinRecord> findByRequestId(String requestId) {
        List<CheckinRecord> rows = jdbc.query(
                "SELECT * FROM checkin_record WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<CheckinRecord> findByWaybillGenSeq(long waybillId, int generation,
                                                       int waypointSeq) {
        List<CheckinRecord> rows = jdbc.query(
                "SELECT * FROM checkin_record WHERE waybill_id = ? AND generation = ? AND waypoint_seq = ?",
                MAPPER, waybillId, generation, waypointSeq);
        return rows.stream().findFirst();
    }
}
