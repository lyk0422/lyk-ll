package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.RecognitionBindingStatus;
import com.example.starter.survey.domain.SurveyType;
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
 * active_flag='Y' 配合唯一索引 uq_rec_binding_active 保证：
 * 每个检验的每个船级项目同一时刻至多一个有效绑定；报告更正时原子失效。
 */
@Repository
public class RecognitionBindingRepository {

    private final JdbcTemplate jdbc;

    public RecognitionBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionBinding> MAPPER = (rs, n) ->
            new RecognitionBinding(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getLong("survey_id"),
                    rs.getLong("report_id"),
                    rs.getString("report_no"),
                    rs.getInt("revision_no"),
                    rs.getInt("rule_version"),
                    rs.getString("flag_item_code"),
                    SurveyType.valueOf(rs.getString("survey_type")),
                    rs.getString("class_item_code"),
                    RecognitionBindingStatus.valueOf(rs.getString("status")),
                    rs.getString("invalidated_reason"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("invalidated_at") == null
                            ? null : rs.getTimestamp("invalidated_at").toInstant(),
                    rs.getString("fingerprint"));

    /** 每项目有效绑定唯一索引冲突。 */
    public static class ActiveBindingConflict extends RuntimeException {
        public ActiveBindingConflict(Throwable cause) {
            super(cause);
        }
    }

    /** 插入有效绑定。 */
    public RecognitionBinding insert(String requestId, long surveyId, long reportId,
                                     String reportNo, int revisionNo, int ruleVersion,
                                     String flagItemCode, SurveyType surveyType,
                                     String classItemCode, String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO recognition_binding
                                (request_id, survey_id, report_id, report_no, revision_no,
                                 rule_version, flag_item_code, survey_type, class_item_code,
                                 status, invalidated_reason, created_at, invalidated_at,
                                 fingerprint, active_flag)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL, ?, NULL, ?, 'Y')
                            """,
                    requestId, surveyId, reportId, reportNo, revisionNo, ruleVersion,
                    flagItemCode, surveyType.name(), classItemCode,
                    Timestamp.from(now), fingerprint);
        } catch (DuplicateKeyException e) {
            throw new ActiveBindingConflict(e);
        }
        return findByRequestId(requestId).orElseThrow();
    }

    /** 按主键查找。 */
    public Optional<RecognitionBinding> findById(long id) {
        return jdbc.query("SELECT * FROM recognition_binding WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按幂等键查找。 */
    public Optional<RecognitionBinding> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM recognition_binding WHERE request_id = ?",
                        MAPPER, requestId)
                .stream().findFirst();
    }

    /** 某检验某船级项目的当前有效绑定（不加锁）。 */
    public Optional<RecognitionBinding> findActive(long surveyId, String classItemCode) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? AND class_item_code = ? AND active_flag = 'Y'
                        """, MAPPER, surveyId, classItemCode)
                .stream().findFirst();
    }

    /** 行锁某检验某船级项目的当前有效绑定（发证冻结用）。 */
    public Optional<RecognitionBinding> findActiveForUpdate(long surveyId, String classItemCode) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? AND class_item_code = ? AND active_flag = 'Y'
                        FOR UPDATE
                        """, MAPPER, surveyId, classItemCode)
                .stream().findFirst();
    }

    /** 某检验的全部有效绑定。 */
    public List<RecognitionBinding> findActiveBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? AND active_flag = 'Y' ORDER BY id
                        """, MAPPER, surveyId);
    }

    /** 行锁某检验全部有效绑定（发证前统一冻结）。 */
    public List<RecognitionBinding> findActiveBySurveyForUpdate(long surveyId) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE survey_id = ? AND active_flag = 'Y' ORDER BY id FOR UPDATE
                        """, MAPPER, surveyId);
    }

    /** 引用某报告修订行的全部有效绑定（报告更正时原子失效用）。 */
    public List<RecognitionBinding> findActiveByReportForUpdate(long reportId) {
        return jdbc.query("""
                        SELECT * FROM recognition_binding
                        WHERE report_id = ? AND active_flag = 'Y' ORDER BY id FOR UPDATE
                        """, MAPPER, reportId);
    }

    /** 原子失效绑定（释放唯一索引槽位）。 */
    public void invalidate(long bindingId, String reason, Instant now) {
        int updated = jdbc.update("""
                        UPDATE recognition_binding
                        SET status = 'INVALIDATED', active_flag = NULL,
                            invalidated_reason = ?, invalidated_at = ?
                        WHERE id = ? AND active_flag = 'Y'
                        """, reason, Timestamp.from(now), bindingId);
        if (updated != 1) {
            throw new IllegalStateException("绑定失效更新行数异常: " + updated);
        }
    }

    /** 某检验全部绑定（含失效，证据链用）。 */
    public List<RecognitionBinding> findAllBySurvey(long surveyId) {
        return jdbc.query(
                "SELECT * FROM recognition_binding WHERE survey_id = ? ORDER BY id",
                MAPPER, surveyId);
    }

    /** 引用某报告编号的全部绑定（含失效，证据链用）。 */
    public List<RecognitionBinding> findAllByReportNo(String reportNo) {
        return jdbc.query(
                "SELECT * FROM recognition_binding WHERE report_no = ? ORDER BY id",
                MAPPER, reportNo);
    }
}
