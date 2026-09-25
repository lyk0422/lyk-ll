package com.example.starter.pv.data.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import com.example.starter.pv.data.FollowUpPo;
import com.example.starter.pv.data.FollowUpRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * pv_follow_up 的 JDBC 实现。
 */
@Repository
public class JdbcFollowUpRepository implements FollowUpRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcFollowUpRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(FollowUpPo po) {
        jdbcTemplate.update("""
                INSERT INTO pv_follow_up
                    (case_no, version_no, follow_up_date, info_text, reporter_code,
                     reporter_name, source_request_id, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                po.caseNo(), po.versionNo(), po.followUpDate(), po.infoText(),
                po.reporterCode(), po.reporterName(), po.sourceRequestId(),
                Timestamp.from(po.createdAt()));
    }

    @Override
    public List<FollowUpPo> findByCaseNo(String caseNo) {
        return jdbcTemplate.query(
                "SELECT id, case_no, version_no, follow_up_date, info_text, reporter_code,"
                        + " reporter_name, source_request_id, created_at"
                        + " FROM pv_follow_up WHERE case_no = ? ORDER BY version_no ASC",
                new FollowUpRowMapper(), caseNo);
    }

    /**
     * 随访行映射器。
     */
    static class FollowUpRowMapper implements RowMapper<FollowUpPo> {
        @Override
        public FollowUpPo mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new FollowUpPo(
                    rs.getLong("id"),
                    rs.getString("case_no"),
                    rs.getInt("version_no"),
                    rs.getObject("follow_up_date", java.time.LocalDate.class),
                    rs.getString("info_text"),
                    rs.getString("reporter_code"),
                    rs.getString("reporter_name"),
                    rs.getString("source_request_id"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
