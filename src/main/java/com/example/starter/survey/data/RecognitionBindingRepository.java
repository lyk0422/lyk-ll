package com.example.starter.survey.data;

import com.example.starter.survey.domain.BindingStatus;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.SurveyItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 互认绑定数据访问。
 * active_flag='Y' 配合唯一索引 uq_binding_active 保证：
 * 同一检验同一项目同一时刻至多一个有效绑定，并发申请唯一受理。
 */
@Repository
public class RecognitionBindingRepository {

    private final JdbcTemplate jdbc;

    public RecognitionBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionBinding> MAPPER = (rs, n) -> new RecognitionBinding(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("survey_id"),
            rs.getLong("report_id"),
            rs.getString("report_no"),
            rs.getInt("report_revision"),
            rs.getLong("rule_id"),
            rs.getInt("rule_version"),
            SurveyItem.valueOf(rs.getString("item_code")),
            BindingStatus.valueOf(rs.getString("status")),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()
    );

    /** 每项目唯一有效绑定冲突时抛出，由服务层转为 409。 */
    public static class ActiveBindingConflict extends RuntimeException {
        public ActiveBindingConflict(Throwable cause) {
            super(cause);
        }
    }

    /** 插入有效绑定。 */
    public RecognitionBinding insert(String requestId, long surveyId, long reportId,
                                     String reportNo, int reportRevision,
                                     long ruleId, int ruleVersion, SurveyItem itemCode,
                                     String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO recognition_binding
                                (request_id, survey_id, report_id, report_no, report_revision,
                                 rule_id, rule_version, item_code, status,
                                 fingerprint, created_at, invalidated_at, active_flag)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL, 'Y')
                            """,
                    requestId, surveyId, reportId, reportNo, reportRevision,
                    ruleId, ruleVersion, itemCode.name(),
                    fingerprint, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw new ActiveBindingConflict(e);
        }
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE request_id = ? AND item_code = ?
                        """, MAPPER, requestId, itemCode.name())
                .stream().findFirst().orElseThrow();
    }

    /** 检验的全部绑定（含已失效，按主键升序）。 */
    public List<RecognitionBinding> findBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? ORDER BY id
                        """, MAPPER, surveyId);
    }

    /** 报告修订的全部有效绑定（更正波及面计算用）。 */
    public List<RecognitionBinding> findActiveByReport(long reportId) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE report_id = ? AND active_flag = 'Y' ORDER BY id
                        """, MAPPER, reportId);
    }

    /** 报告编号关联的全部绑定（证据链查询用，按主键升序）。 */
    public List<RecognitionBinding> findByReportNo(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE report_no = ? ORDER BY id
                        """, MAPPER, reportNo);
    }

    /** 检验某项目的有效绑定。 */
    public Optional<RecognitionBinding> findActive(long surveyId, SurveyItem itemCode) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? AND item_code = ? AND active_flag = 'Y'
                        """, MAPPER, surveyId, itemCode.name())
                .stream().findFirst();
    }

    /** 报告更正：未签发检验的旧互认失效（与更正同事务）。 */
    public void invalidate(long bindingId, Instant now) {
        int updated = jdbc.update("""
                        UPDATE recognition_binding
                        SET status = 'INVALIDATED', active_flag = NULL, invalidated_at = ?
                        WHERE id = ? AND active_flag = 'Y'
                        """, Timestamp.from(now), bindingId);
        if (updated != 1) {
            throw new IllegalStateException("互认失效更新行数异常: " + updated);
        }
    }
}
