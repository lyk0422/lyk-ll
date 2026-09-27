package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionRisk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 互认风险记录数据访问（已签发历史证据受报告更正影响时另记，不改历史）。 */
@Repository
public class RecognitionRiskRepository {

    private final JdbcTemplate jdbc;

    public RecognitionRiskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionRisk> MAPPER = (rs, n) -> new RecognitionRisk(
            rs.getLong("id"),
            rs.getString("report_no"),
            rs.getInt("old_revision_no"),
            rs.getInt("new_revision_no"),
            rs.getObject("survey_id") == null ? null : rs.getLong("survey_id"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant());

    /** 登记一条风险。 */
    public void insert(String reportNo, int oldRevisionNo, int newRevisionNo,
                       Long surveyId, String reason, Instant now) {
        jdbc.update("""
                        INSERT INTO recognition_risk
                            (report_no, old_revision_no, new_revision_no, survey_id,
                             reason, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                reportNo, oldRevisionNo, newRevisionNo, surveyId, reason,
                Timestamp.from(now));
    }

    /** 某报告的全部风险记录。 */
    public List<RecognitionRisk> findByReportNo(String reportNo) {
        return jdbc.query(
                "SELECT * FROM recognition_risk WHERE report_no = ? ORDER BY id",
                MAPPER, reportNo);
    }

    /** 某检验相关的风险记录。 */
    public List<RecognitionRisk> findBySurvey(long surveyId) {
        return jdbc.query(
                "SELECT * FROM recognition_risk WHERE survey_id = ? ORDER BY id",
                MAPPER, surveyId);
    }
}
