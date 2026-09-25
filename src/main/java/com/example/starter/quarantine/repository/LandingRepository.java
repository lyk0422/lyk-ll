package com.example.starter.quarantine.repository;

import com.example.starter.quarantine.domain.LandingReportPo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 落地报告事件仓储。
 */
@Repository
public class LandingRepository {

    private static final RowMapper<LandingReportPo> ROW_MAPPER = LandingRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public LandingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(LandingReportPo po) {
        jdbcTemplate.update(
                "INSERT INTO landing_report (request_id, report_id, quantity, reported_at, quarantine_flag,"
                        + " accepted, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.getRequestId(), po.getReportId(), po.getQuantity(), Timestamp.from(po.getReportedAt()),
                po.isQuarantineFlag(), po.isAccepted(), po.getNote(), Timestamp.from(po.getCreatedAt()));
    }

    public LandingReportPo find(String requestId, String reportId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, request_id, report_id, quantity, reported_at, quarantine_flag, accepted, note,"
                            + " created_at FROM landing_report WHERE request_id = ? AND report_id = ?",
                    ROW_MAPPER, requestId, reportId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 已接受（accepted=TRUE）的累计落地数量。 */
    public int sumAccepted(String requestId) {
        Integer sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM landing_report WHERE request_id = ? AND accepted = TRUE",
                Integer.class, requestId);
        return sum == null ? 0 : sum;
    }

    private static LandingReportPo mapRow(ResultSet rs, int n) throws SQLException {
        LandingReportPo po = new LandingReportPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setReportId(rs.getString("report_id"));
        po.setQuantity(rs.getInt("quantity"));
        po.setReportedAt(rs.getTimestamp("reported_at").toInstant());
        po.setQuarantineFlag(rs.getBoolean("quarantine_flag"));
        po.setAccepted(rs.getBoolean("accepted"));
        po.setNote(rs.getString("note"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    }
}
