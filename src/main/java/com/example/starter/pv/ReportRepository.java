package com.example.starter.pv;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 报告提交记录数据访问。request_id 唯一索引保证幂等键并发安全。
 */
@Repository
public class ReportRepository {

    private static final String COLUMNS =
            "id, case_id, request_id, fingerprint, report_type, narrative, is_late, late_reason,"
            + " submitted_at, case_version";

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PvReport> MAPPER = (rs, rowNum) -> map(rs);

    private static PvReport map(ResultSet rs) throws SQLException {
        return new PvReport(
                rs.getLong("id"),
                rs.getLong("case_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                ReportType.valueOf(rs.getString("report_type")),
                rs.getString("narrative"),
                rs.getBoolean("is_late"),
                rs.getString("late_reason"),
                rs.getTimestamp("submitted_at").toInstant(),
                rs.getInt("case_version"));
    }

    /**
     * 插入报告记录。request_id 冲突时抛出 DuplicateKeyException，由上层按幂等重放处理。
     */
    public PvReport insert(PvReport r, java.time.Instant now) {
        String sql = "INSERT INTO pv_report (case_id, request_id, fingerprint, report_type, narrative,"
                + " is_late, late_reason, submitted_at, case_version, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.caseId());
            ps.setString(2, r.requestId());
            ps.setString(3, r.fingerprint());
            ps.setString(4, r.reportType().name());
            ps.setString(5, r.narrative());
            ps.setBoolean(6, r.late());
            if (r.lateReason() == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, r.lateReason());
            }
            ps.setTimestamp(8, Timestamp.from(r.submittedAt()));
            ps.setInt(9, r.caseVersion());
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return new PvReport(key == null ? null : key.longValue(), r.caseId(), r.requestId(), r.fingerprint(),
                r.reportType(), r.narrative(), r.late(), r.lateReason(), r.submittedAt(), r.caseVersion());
    }

    /**
     * 按幂等键查询已有报告。
     */
    public Optional<PvReport> findByRequestId(String requestId) {
        List<PvReport> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_report WHERE request_id = ?", MAPPER, requestId);
        return list.stream().findFirst();
    }

    /**
     * 查询个例全部报告（按提交顺序）。
     */
    public List<PvReport> findByCaseId(long caseId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_report WHERE case_id = ? ORDER BY id", MAPPER, caseId);
    }
}
