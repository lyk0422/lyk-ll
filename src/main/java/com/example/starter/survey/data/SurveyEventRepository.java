package com.example.starter.survey.data;

import com.example.starter.survey.domain.SurveyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 检验过程事件数据访问（如签发后迟到登轮记录）。 */
@Repository
public class SurveyEventRepository {

    private final JdbcTemplate jdbc;

    public SurveyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SurveyEvent> MAPPER = (rs, n) -> new SurveyEvent(
            rs.getLong("id"),
            rs.getLong("survey_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant()
    );

    /** 登记事件。 */
    public void insert(long surveyId, String eventType, String payload, Instant now) {
        jdbc.update("""
                        INSERT INTO survey_event (survey_id, event_type, payload, occurred_at)
                        VALUES (?, ?, ?, ?)
                        """,
                surveyId, eventType, payload, Timestamp.from(now));
    }

    /** 检验下的全部事件。 */
    public List<SurveyEvent> findBySurvey(long surveyId) {
        return jdbc.query("SELECT * FROM survey_event WHERE survey_id = ? ORDER BY id",
                MAPPER, surveyId);
    }
}
