package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionRisk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 互认风险记录数据访问（已签发历史证据被新修订影响时登记）。 */
@Repository
public class RecognitionRiskRepository {

    private final JdbcTemplate jdbc;

    public RecognitionRiskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionRisk> MAPPER = (rs, n) -> new RecognitionRisk(
            rs.getLong("id"),
            rs.getLong("binding_id"),
            rs.getString("report_no"),
            rs.getInt("new_revision"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant()
    );

    /** 登记风险记录。 */
    public void insert(long bindingId, String reportNo, int newRevision, String note, Instant now) {
        jdbc.update("""
                        INSERT INTO recognition_risk (binding_id, report_no, new_revision, note, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                bindingId, reportNo, newRevision, note, Timestamp.from(now));
    }

    /** 按绑定查询风险记录。 */
    public List<RecognitionRisk> findByBinding(long bindingId) {
        return jdbc.query("""
                        SELECT * FROM recognition_risk
                        WHERE binding_id = ? ORDER BY id
                        """, MAPPER, bindingId);
    }

    /** 按检验查询风险记录（证据链查询用）。 */
    public List<RecognitionRisk> findBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT k.* FROM recognition_risk k
                        JOIN recognition_binding b ON b.id = k.binding_id
                        WHERE b.survey_id = ? ORDER BY k.id
                        """, MAPPER, surveyId);
    }
}
