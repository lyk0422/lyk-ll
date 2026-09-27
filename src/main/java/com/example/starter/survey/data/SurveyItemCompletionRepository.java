package com.example.starter.survey.data;

import com.example.starter.survey.domain.SurveyItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 登轮项目完成证明数据访问。
 * 发证门禁要求：检验类型的必需项目由本表（登轮完成）或有效互认绑定覆盖。
 */
@Repository
public class SurveyItemCompletionRepository {

    private final JdbcTemplate jdbc;

    public SurveyItemCompletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登轮时记录项目完成证明（与登轮同事务）。 */
    public void insert(long surveyId, SurveyItem itemCode, Instant now) {
        jdbc.update("""
                        INSERT INTO survey_item_completion (survey_id, item_code, created_at)
                        VALUES (?, ?, ?)
                        """,
                surveyId, itemCode.name(), Timestamp.from(now));
    }

    /** 检验已完成登轮的项目集合。 */
    public List<SurveyItem> findBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT item_code FROM survey_item_completion
                        WHERE survey_id = ? ORDER BY item_code
                        """,
                (rs, n) -> SurveyItem.valueOf(rs.getString("item_code")), surveyId);
    }
}
