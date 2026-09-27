package com.example.starter.survey.data;

import com.example.starter.survey.domain.FlagRecognition;
import com.example.starter.survey.domain.RecognitionStatus;
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
 * active_flag='Y' 配合唯一索引 uq_recognition_active 保证：
 * 同一检验同一项目同一时刻至多一个有效绑定，并发申请唯一受理。
 */
@Repository
public class FlagRecognitionRepository {

    private final JdbcTemplate jdbc;

    public FlagRecognitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlagRecognition> MAPPER = (rs, n) -> new FlagRecognition(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("survey_id"),
            rs.getLong("report_id"),
            rs.getInt("revision"),
            rs.getInt("rule_version"),
            rs.getString("item_code"),
            RecognitionStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
            rs.getString("fingerprint")
    );

    /** 有效绑定唯一索引冲突时抛出，由服务层转为 409。 */
    public static class ActiveRecognitionConflict extends RuntimeException {
        public ActiveRecognitionConflict(Throwable cause) {
            super(cause);
        }
    }

    /** 申请受理：插入有效绑定（冻结报告修订与规则版本）。 */
    public FlagRecognition insert(String requestId, long surveyId, long reportId,
                                  int revision, int ruleVersion, String itemCode,
                                  String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO flag_recognition
                                (request_id, survey_id, report_id, revision, rule_version,
                                 item_code, status, active_flag, created_at, invalidated_at, fingerprint)
                            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'Y', ?, NULL, ?)
                            """,
                    requestId, surveyId, reportId, revision, ruleVersion, itemCode,
                    Timestamp.from(now), fingerprint);
        } catch (DuplicateKeyException e) {
            throw new ActiveRecognitionConflict(e);
        }
        return findByRequestId(requestId).orElseThrow();
    }

    /** 失效绑定：状态置 INVALIDATED，释放 active_flag 槽位。 */
    public void invalidate(long recognitionId, Instant now) {
        int updated = jdbc.update("""
                        UPDATE flag_recognition
                        SET status = 'INVALIDATED', active_flag = NULL, invalidated_at = ?
                        WHERE id = ? AND active_flag = 'Y'
                        """,
                Timestamp.from(now), recognitionId);
        if (updated != 1) {
            throw new IllegalStateException("互认失效更新行数异常: " + updated);
        }
    }

    /** 按申请幂等键查找。 */
    public Optional<FlagRecognition> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM flag_recognition WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 查找检验某项目当前有效绑定。 */
    public Optional<FlagRecognition> findActive(long surveyId, String itemCode) {
        return jdbc.query("""
                        SELECT * FROM flag_recognition
                        WHERE survey_id = ? AND item_code = ? AND active_flag = 'Y'
                        """, MAPPER, surveyId, itemCode)
                .stream().findFirst();
    }

    /** 列出检验当前全部有效绑定（发证一致性校验用）。 */
    public List<FlagRecognition> findActiveBySurvey(long surveyId) {
        return jdbc.query("""
                        SELECT * FROM flag_recognition
                        WHERE survey_id = ? AND active_flag = 'Y' ORDER BY id
                        """, MAPPER, surveyId);
    }

    /** 列出报告当前全部有效绑定（更正原子失效用）。 */
    public List<FlagRecognition> findActiveByReport(long reportId) {
        return jdbc.query("""
                        SELECT * FROM flag_recognition
                        WHERE report_id = ? AND active_flag = 'Y' ORDER BY id
                        """, MAPPER, reportId);
    }

    /** 列出检验全部绑定（含已失效，证据链查询用）。 */
    public List<FlagRecognition> findBySurvey(long surveyId) {
        return jdbc.query("SELECT * FROM flag_recognition WHERE survey_id = ? ORDER BY id",
                MAPPER, surveyId);
    }
}
