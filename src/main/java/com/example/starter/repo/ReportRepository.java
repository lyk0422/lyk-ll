package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.ReportRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 执行回报数据访问。机组＋时刻由 uk_report_point 唯一约束去重。
 */
@Repository
public class ReportRepository {

    private static final String COLUMNS = "id, command_id, farm_id, turbine_id, request_id,"
            + " fingerprint, report_time, actual_kw, late, received_at";

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long commandId, long farmId, long turbineId, String requestId,
                       String fingerprint, LocalDateTime reportTime, int actualKw, boolean late,
                       LocalDateTime receivedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO power_report (command_id, farm_id, turbine_id, request_id,"
                            + " fingerprint, report_time, actual_kw, late, received_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, commandId);
            ps.setLong(2, farmId);
            ps.setLong(3, turbineId);
            ps.setString(4, requestId);
            ps.setString(5, fingerprint);
            ps.setTimestamp(6, Timestamp.valueOf(reportTime));
            ps.setInt(7, actualKw);
            ps.setBoolean(8, late);
            ps.setTimestamp(9, Timestamp.valueOf(receivedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ReportRow> findByRequestId(String requestId) {
        List<ReportRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM power_report WHERE request_id = ?",
                this::map, requestId);
        return rows.stream().findFirst();
    }

    public Optional<ReportRow> findByPoint(long commandId, long turbineId, LocalDateTime reportTime) {
        List<ReportRow> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM power_report WHERE command_id = ? AND turbine_id = ? AND report_time = ?",
                this::map, commandId, turbineId, Timestamp.valueOf(reportTime));
        return rows.stream().findFirst();
    }

    /**
     * 执行曲线：按采集时刻升序归位，同时刻按入库顺序。
     */
    public List<ReportRow> findCurve(long commandId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM power_report WHERE command_id = ? ORDER BY report_time, id",
                this::map, commandId);
    }

    private ReportRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ReportRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime(),
                rs.getInt(8), rs.getBoolean(9), rs.getTimestamp(10).toLocalDateTime());
    }
}
