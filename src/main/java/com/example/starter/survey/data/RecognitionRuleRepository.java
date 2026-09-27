package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionRule;
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
 * 互认规则版本数据访问。
 * rule_version 全局唯一；规则创建后不可变，项目映射存于 recognition_rule_item。
 */
@Repository
public class RecognitionRuleRepository {

    private final JdbcTemplate jdbc;

    public RecognitionRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionRule> MAPPER = (rs, n) -> new RecognitionRule(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getInt("rule_version"),
            rs.getString("flag_code"),
            rs.getInt("max_evidence_days"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant()
    );

    /** 规则版本号冲突时抛出，由服务层转为 409。 */
    public static class RuleVersionConflict extends RuntimeException {
        public RuleVersionConflict(Throwable cause) {
            super(cause);
        }
    }

    /** 创建规则版本及其项目映射（同事务由调用方保证）。 */
    public RecognitionRule insert(String requestId, int ruleVersion, String flagCode,
                                  int maxEvidenceDays, List<SurveyItem> items,
                                  String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO recognition_rule
                                (request_id, rule_version, flag_code, max_evidence_days,
                                 fingerprint, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    requestId, ruleVersion, flagCode, maxEvidenceDays,
                    fingerprint, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw new RuleVersionConflict(e);
        }
        RecognitionRule rule = findByVersion(ruleVersion).orElseThrow();
        for (SurveyItem item : items) {
            jdbc.update("INSERT INTO recognition_rule_item (rule_id, item_code) VALUES (?, ?)",
                    rule.id(), item.name());
        }
        return rule;
    }

    /** 按版本号查找。 */
    public Optional<RecognitionRule> findByVersion(int ruleVersion) {
        return jdbc.query("SELECT * FROM recognition_rule WHERE rule_version = ?",
                        MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /** 规则版本映射的项目集合。 */
    public List<SurveyItem> findItems(long ruleId) {
        return jdbc.query("""
                        SELECT item_code FROM recognition_rule_item
                        WHERE rule_id = ? ORDER BY item_code
                        """,
                (rs, n) -> SurveyItem.valueOf(rs.getString("item_code")), ruleId);
    }

    /** 全部规则版本（按版本号升序）。 */
    public List<RecognitionRule> findAll() {
        return jdbc.query("SELECT * FROM recognition_rule ORDER BY rule_version", MAPPER);
    }
}
