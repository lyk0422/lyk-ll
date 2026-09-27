package com.example.starter.survey.data;

import com.example.starter.survey.domain.FlagRuleItem;
import com.example.starter.survey.domain.FlagRuleVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 船旗互认规则版本数据访问。
 * 规则版本不可变：每次变更插入新版本行，历史版本保留供互认绑定冻结引用。
 */
@Repository
public class FlagRuleRepository {

    private final JdbcTemplate jdbc;

    public FlagRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlagRuleVersion> RULE_MAPPER = (rs, n) -> new FlagRuleVersion(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("flag_code"),
            rs.getInt("version"),
            rs.getInt("max_evidence_age_days"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<FlagRuleItem> ITEM_MAPPER = (rs, n) -> new FlagRuleItem(
            rs.getLong("id"),
            rs.getLong("rule_id"),
            rs.getString("flag_item_code"),
            rs.getString("class_item_code")
    );

    /** 插入新规则版本（版本号由服务层按当前版本+1 计算）。 */
    public FlagRuleVersion insertVersion(String requestId, String flagCode, int version,
                                         int maxEvidenceAgeDays, Instant now) {
        jdbc.update("""
                        INSERT INTO flag_rule_version (request_id, flag_code, version,
                                                       max_evidence_age_days, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                requestId, flagCode, version, maxEvidenceAgeDays, Timestamp.from(now));
        return findByFlagAndVersion(flagCode, version).orElseThrow();
    }

    /** 插入规则版本的项目映射。 */
    public void insertItem(long ruleId, String flagItemCode, String classItemCode) {
        jdbc.update("""
                        INSERT INTO flag_rule_item (rule_id, flag_item_code, class_item_code)
                        VALUES (?, ?, ?)
                        """,
                ruleId, flagItemCode, classItemCode);
    }

    /** 按主键查找规则版本。 */
    public Optional<FlagRuleVersion> findById(long id) {
        return jdbc.query("SELECT * FROM flag_rule_version WHERE id = ?", RULE_MAPPER, id)
                .stream().findFirst();
    }

    /** 查找某船旗当前最新规则版本。 */
    public Optional<FlagRuleVersion> findLatest(String flagCode) {
        return jdbc.query("""
                        SELECT * FROM flag_rule_version
                        WHERE flag_code = ? ORDER BY version DESC LIMIT 1
                        """, RULE_MAPPER, flagCode)
                .stream().findFirst();
    }

    /** 按船旗+版本号查找（互认申请冻结引用）。 */
    public Optional<FlagRuleVersion> findByFlagAndVersion(String flagCode, int version) {
        return jdbc.query("""
                        SELECT * FROM flag_rule_version
                        WHERE flag_code = ? AND version = ?
                        """, RULE_MAPPER, flagCode, version)
                .stream().findFirst();
    }

    /** 列出某船旗全部规则版本（版本链查询）。 */
    public List<FlagRuleVersion> findVersions(String flagCode) {
        return jdbc.query("""
                        SELECT * FROM flag_rule_version
                        WHERE flag_code = ? ORDER BY version
                        """, RULE_MAPPER, flagCode);
    }

    /** 列出规则版本的项目映射。 */
    public List<FlagRuleItem> findItems(long ruleId) {
        return jdbc.query("""
                        SELECT * FROM flag_rule_item
                        WHERE rule_id = ? ORDER BY flag_item_code
                        """, ITEM_MAPPER, ruleId);
    }
}
