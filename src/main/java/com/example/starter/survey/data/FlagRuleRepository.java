package com.example.starter.survey.data;

import com.example.starter.survey.domain.FlagRule;
import com.example.starter.survey.domain.FlagRuleItem;
import com.example.starter.survey.domain.SurveyType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 互认规则版本与项目映射数据访问。
 * 规则版本只增不改：初始化写入后按版本号查询。
 */
@Repository
public class FlagRuleRepository {

    private final JdbcTemplate jdbc;

    public FlagRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlagRule> RULE_MAPPER = (rs, n) -> new FlagRule(
            rs.getLong("id"),
            rs.getInt("version"),
            rs.getString("flag_code"),
            rs.getInt("max_evidence_age_days"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<FlagRuleItem> ITEM_MAPPER = (rs, n) -> new FlagRuleItem(
            rs.getLong("id"),
            rs.getLong("rule_id"),
            rs.getString("item_code"),
            SurveyType.valueOf(rs.getString("survey_type"))
    );

    /** 插入规则版本（初始化用）。 */
    public FlagRule insertRule(int version, String flagCode, int maxEvidenceAgeDays, Instant now) {
        jdbc.update("""
                        INSERT INTO flag_rule (version, flag_code, max_evidence_age_days, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                version, flagCode, maxEvidenceAgeDays, Timestamp.from(now));
        return findByVersion(version).orElseThrow();
    }

    /** 插入规则版本的项目映射（初始化用）。 */
    public void insertItem(long ruleId, String itemCode, SurveyType surveyType) {
        jdbc.update("""
                        INSERT INTO flag_rule_item (rule_id, item_code, survey_type)
                        VALUES (?, ?, ?)
                        """,
                ruleId, itemCode, surveyType.name());
    }

    /** 按版本号查找规则。 */
    public Optional<FlagRule> findByVersion(int version) {
        return jdbc.query("SELECT * FROM flag_rule WHERE version = ?", RULE_MAPPER, version)
                .stream().findFirst();
    }

    /** 列出全部规则版本（按版本号升序）。 */
    public List<FlagRule> findAll() {
        return jdbc.query("SELECT * FROM flag_rule ORDER BY version", RULE_MAPPER);
    }

    /** 列出规则版本的项目映射。 */
    public List<FlagRuleItem> findItems(long ruleId) {
        return jdbc.query("SELECT * FROM flag_rule_item WHERE rule_id = ? ORDER BY item_code",
                ITEM_MAPPER, ruleId);
    }

    /** 在规则版本内按项目代码查找映射。 */
    public Optional<FlagRuleItem> findItem(long ruleId, String itemCode) {
        return jdbc.query("SELECT * FROM flag_rule_item WHERE rule_id = ? AND item_code = ?",
                        ITEM_MAPPER, ruleId, itemCode)
                .stream().findFirst();
    }
}
