package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionRisk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 互认风险记录数据访问。
 * 报告更正波及已签发检验的历史互认时登记；历史证据不修改，仅追加风险。
 */
@Repository
public class RecognitionRiskRepository {

    private final JdbcTemplate jdbc;

    public RecognitionRiskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionRisk> MAPPER = (rs, n) -> new RecognitionRisk(
            rs.getLong("id"),
            rs.getLong("binding_id"),
            rs.getLong("survey_id"),
            rs.getString("report_no"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant()
    );

    /** 登记风险（与报告更正同事务）；以自增主键回取本事务插入的行。 */
    public RecognitionRisk insert(long bindingId, long surveyId, String reportNo,
                                  String note, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO recognition_risk (binding_id, survey_id, report_no, note, created_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bindingId);
            ps.setLong(2, surveyId);
            ps.setString(3, reportNo);
            ps.setString(4, note);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return jdbc.query("SELECT * FROM recognition_risk WHERE id = ?", MAPPER, id)
                .stream().findFirst().orElseThrow();
    }

    /** 报告编号关联的全部风险记录（证据链查询用）。 */
    public List<RecognitionRisk> findByReportNo(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM recognition_risk
                        WHERE report_no = ? ORDER BY id
                        """, MAPPER, reportNo);
    }

    /** 检验关联的全部风险记录。 */
    public List<RecognitionRisk> findBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT * FROM recognition_risk
                        WHERE survey_id = ? ORDER BY id
                        """, MAPPER, surveyId);
    }
}
