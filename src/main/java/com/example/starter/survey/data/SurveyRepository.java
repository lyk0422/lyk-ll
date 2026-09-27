package com.example.starter.survey.data;

import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.domain.SurveyType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 检验记录数据访问。
 * active_flag='Y' 配合唯一索引 uq_survey_active 保证：
 * 同一船舶同一检验类型同一时刻至多一个进行中检验，并发申请唯一受理。
 */
@Repository
public class SurveyRepository {

    private final JdbcTemplate jdbc;

    public SurveyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Survey> MAPPER = (rs, n) -> new Survey(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("ship_id"),
            SurveyType.valueOf(rs.getString("type")),
            SurveyStatus.valueOf(rs.getString("status")),
            rs.getDate("cycle_anchor").toLocalDate(),
            rs.getDate("window_start").toLocalDate(),
            rs.getDate("window_end").toLocalDate(),
            rs.getObject("extension_until") == null ? null : rs.getDate("extension_until").toLocalDate(),
            rs.getTimestamp("applied_at").toInstant(),
            rs.getObject("boarded_at") == null ? null : rs.getTimestamp("boarded_at").toInstant(),
            rs.getObject("certified_at") == null ? null : rs.getTimestamp("certified_at").toInstant(),
            rs.getString("fingerprint")
    );

    /** 进行中检验唯一索引冲突时抛出，由服务层转为 409。 */
    public static class ActiveSurveyConflict extends RuntimeException {
        public ActiveSurveyConflict(Throwable cause) {
            super(cause);
        }
    }

    /** 申请受理：插入进行中检验。 */
    public Survey insert(String requestId, long shipId, SurveyType type,
                         LocalDate anchor, LocalDate windowStart, LocalDate windowEnd,
                         String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO survey (request_id, ship_id, type, status, cycle_anchor,
                                                window_start, window_end, extension_until,
                                                applied_at, boarded_at, certified_at,
                                                fingerprint, active_flag)
                            VALUES (?, ?, ?, 'APPLIED', ?, ?, ?, NULL, ?, NULL, NULL, ?, 'Y')
                            """,
                    requestId, shipId, type.name(),
                    Date.valueOf(anchor), Date.valueOf(windowStart), Date.valueOf(windowEnd),
                    Timestamp.from(now), fingerprint);
        } catch (DuplicateKeyException e) {
            throw new ActiveSurveyConflict(e);
        }
        return findByRequestId(requestId).orElseThrow();
    }

    /** 按主键查找。 */
    public Optional<Survey> findById(long id) {
        return jdbc.query("SELECT * FROM survey WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按申请幂等键查找。 */
    public Optional<Survey> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM survey WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 事务内行锁检验记录（FOR UPDATE）。
     * 发证与报告更正失效处理据此串行化：冻结一致的报告修订与规则版本后才允许发证。
     */
    public Optional<Survey> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM survey WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /** 查找同船同类型当前进行中（未发证）检验。 */
    public Optional<Survey> findInProgress(long shipId, SurveyType type) {
        return jdbc.query("""
                        SELECT * FROM survey
                        WHERE ship_id = ? AND type = ? AND active_flag = 'Y'
                        """, MAPPER, shipId, type.name())
                .stream().findFirst();
    }

    /** 登轮完成：状态置 BOARDED，记录完成时刻。 */
    public void markBoarded(long surveyId, Instant boardedAt) {
        int updated = jdbc.update("""
                        UPDATE survey SET status = 'BOARDED', boarded_at = ?
                        WHERE id = ? AND active_flag = 'Y'
                        """, Timestamp.from(boardedAt), surveyId);
        if (updated != 1) {
            throw new IllegalStateException("登轮更新行数异常: " + updated);
        }
    }

    /** 展期批准后更新展期末日。 */
    public void updateExtensionUntil(long surveyId, LocalDate extensionUntil) {
        jdbc.update("UPDATE survey SET extension_until = ? WHERE id = ?",
                Date.valueOf(extensionUntil), surveyId);
    }

    /** 发证：终态 CERTIFIED，释放 active_flag（唯一索引槽位）。 */
    public void markCertified(long surveyId, Instant certifiedAt) {
        int updated = jdbc.update("""
                        UPDATE survey SET status = 'CERTIFIED', certified_at = ?, active_flag = NULL
                        WHERE id = ?
                        """, Timestamp.from(certifiedAt), surveyId);
        if (updated != 1) {
            throw new IllegalStateException("发证更新行数异常: " + updated);
        }
    }

    /** 列出船舶全部检验（台账复算用）。 */
    public List<Survey> findByShip(long shipId) {
        return jdbc.query("SELECT * FROM survey WHERE ship_id = ? ORDER BY id", MAPPER, shipId);
    }

    /** 全量检验（窗口台账整体重建用）。 */
    public List<Survey> findAll() {
        return jdbc.query("SELECT * FROM survey ORDER BY id", MAPPER);
    }
}
