package com.example.starter.survey.data;

import com.example.starter.survey.domain.FlagRiskNote;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 风险记录数据访问：报告更正时已签发检验的历史互认不改动，另记风险备查。
 */
@Repository
public class FlagRiskNoteRepository {

    private final JdbcTemplate jdbc;

    public FlagRiskNoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlagRiskNote> MAPPER = (rs, n) -> new FlagRiskNote(
            rs.getLong("id"),
            rs.getLong("recognition_id"),
            rs.getLong("survey_id"),
            rs.getLong("report_id"),
            rs.getInt("old_revision"),
            rs.getInt("new_revision"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant()
    );

    /** 追加风险记录。 */
    public void insert(long recognitionId, long surveyId, long reportId,
                       int oldRevision, int newRevision, String note, Instant now) {
        jdbc.update("""
                        INSERT INTO flag_risk_note
                            (recognition_id, survey_id, report_id, old_revision, new_revision, note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                recognitionId, surveyId, reportId, oldRevision, newRevision, note,
                Timestamp.from(now));
    }

    /** 列出检验的风险记录（证据链查询用）。 */
    public List<FlagRiskNote> findBySurvey(long surveyId) {
        return jdbc.query("SELECT * FROM flag_risk_note WHERE survey_id = ? ORDER BY id",
                MAPPER, surveyId);
    }
}
