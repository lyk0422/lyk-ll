package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.RecognitionStatus;
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
 * 同一检验同一船级项目同一时刻至多一个有效绑定。
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
            rs.getLong("rule_id"),
            rs.getString("flag_item_code"),
            rs.getString("class_item_code"),
            RecognitionStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()
    );

    /** 插入有效绑定（唯一索引兜底：同检验同项目并发仅一个成功）。 */
    public RecognitionBinding insert(String requestId, long surveyId, long reportId, long ruleId,
                                     String flagItemCode, String classItemCode, Instant now) {
        jdbc.update("""
                        INSERT INTO recognition_binding (request_id, survey_id, report_id, rule_id,
                                                         flag_item_code, class_item_code, status,
                                                         active_flag, created_at, invalidated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'Y', ?, NULL)
                        """,
                requestId, surveyId, reportId, ruleId, flagItemCode, classItemCode,
                Timestamp.from(now));
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE request_id = ? AND class_item_code = ?
                        """, MAPPER, requestId, classItemCode)
                .stream().findFirst().orElseThrow();
    }

    /** 按主键查找。 */
    public Optional<RecognitionBinding> findById(long id) {
        return jdbc.query("SELECT * FROM recognition_binding WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 列出检验的全部绑定（含已失效，证据链查询用）。 */
    public List<RecognitionBinding> findBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? ORDER BY id
                        """, MAPPER, surveyId);
    }

    /** 查找检验某船级项目当前有效绑定。 */
    public Optional<RecognitionBinding> findActive(long surveyId, String classItemCode) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? AND class_item_code = ? AND active_flag = 'Y'
                        """, MAPPER, surveyId, classItemCode)
                .stream().findFirst();
    }

    /** 列出引用指定报告任一修订的有效绑定（报告更正失效处理用）。 */
    public List<RecognitionBinding> findActiveByReportNo(String reportNo) {
        return jdbc.query("""
                        SELECT b.* FROM recognition_binding b
                        JOIN flag_inspection_report r ON r.id = b.report_id
                        WHERE r.report_no = ? AND b.active_flag = 'Y'
                        ORDER BY b.id
                        """, MAPPER, reportNo);
    }

    /** 失效绑定：状态置 INVALIDATED，释放唯一索引槽位。 */
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

    /**
     * 是否存在未解决的失效绑定：某船级项目被报告更正失效且没有新的有效绑定替代。
     * 发证门禁据此拒绝（不得以旧互认可用标志先发证）。
     */
    public boolean existsUnresolvedInvalidation(long surveyId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM recognition_binding b
                        WHERE b.survey_id = ? AND b.status = 'INVALIDATED'
                          AND NOT EXISTS (
                              SELECT 1 FROM recognition_binding a
                              WHERE a.survey_id = b.survey_id
                                AND a.class_item_code = b.class_item_code
                                AND a.active_flag = 'Y')
                        """, Integer.class, surveyId);
        return count != null && count > 0;
    }
}
