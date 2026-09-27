package com.example.starter.survey.data;

import com.example.starter.survey.domain.RecognitionFlag;
import com.example.starter.survey.domain.RecognitionItemMapping;
import com.example.starter.survey.domain.RecognitionRuleStatus;
import com.example.starter.survey.domain.RecognitionRuleVersion;
import com.example.starter.survey.domain.SurveyType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 合成互认规则数据访问：规则版本、承认船旗、项目映射。
 * 规则版本一经发布不可变；调整规则须发布新版本（version +1）。
 */
@Repository
public class RecognitionRuleRepository {

    private final JdbcTemplate jdbc;

    public RecognitionRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecognitionRuleVersion> VERSION_MAPPER = (rs, n) ->
            new RecognitionRuleVersion(
                    rs.getLong("id"),
                    rs.getInt("version"),
                    rs.getInt("max_evidence_valid_days"),
                    RecognitionRuleStatus.valueOf(rs.getString("status")),
                    rs.getString("note"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<RecognitionFlag> FLAG_MAPPER = (rs, n) ->
            new RecognitionFlag(rs.getLong("id"), rs.getLong("rule_version_id"),
                    rs.getString("flag_code"));

    private static final RowMapper<RecognitionItemMapping> MAPPING_MAPPER = (rs, n) ->
            new RecognitionItemMapping(rs.getLong("id"), rs.getLong("rule_version_id"),
                    rs.getString("flag_item_code"),
                    SurveyType.valueOf(rs.getString("survey_type")),
                    rs.getString("class_item_code"));

    /** 下一规则版本号（无任何版本时为 1）。 */
    public int nextVersion() {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM recognition_rule_version",
                Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    /** 发布一个新版本并写入船旗清单与项目映射（同一事务）。 */
    @Transactional
    public RecognitionRuleVersion publishVersion(int version, int maxEvidenceValidDays,
                                                 String note, Instant now,
                                                 List<String> flagCodes,
                                                 List<RecognitionItemMapping> mappings) {
        jdbc.update("""
                        INSERT INTO recognition_rule_version
                            (version, max_evidence_valid_days, status, note, created_at)
                        VALUES (?, ?, 'ACTIVE', ?, ?)
                        """,
                version, maxEvidenceValidDays, note, Timestamp.from(now));
        Long ruleId = jdbc.queryForObject(
                "SELECT id FROM recognition_rule_version WHERE version = ?",
                Long.class, version);
        for (String code : flagCodes) {
            jdbc.update("""
                            INSERT INTO recognition_flag (rule_version_id, flag_code)
                            VALUES (?, ?)
                            """, ruleId, code);
        }
        for (RecognitionItemMapping m : mappings) {
            jdbc.update("""
                            INSERT INTO recognition_item_mapping
                                (rule_version_id, flag_item_code, survey_type, class_item_code)
                            VALUES (?, ?, ?, ?)
                            """, ruleId, m.flagItemCode(), m.surveyType().name(),
                    m.classItemCode());
        }
        return findByVersion(version).orElseThrow();
    }

    /** 按版本号查找规则版本。 */
    public Optional<RecognitionRuleVersion> findByVersion(int version) {
        return jdbc.query("SELECT * FROM recognition_rule_version WHERE version = ?",
                        VERSION_MAPPER, version)
                .stream().findFirst();
    }

    /** 按版本号行锁规则版本（发证并发冻结用）。 */
    public Optional<RecognitionRuleVersion> findByVersionForUpdate(int version) {
        return jdbc.query("SELECT * FROM recognition_rule_version WHERE version = ? FOR UPDATE",
                        VERSION_MAPPER, version)
                .stream().findFirst();
    }

    /** 当前可引用的最新 ACTIVE 版本。 */
    public Optional<RecognitionRuleVersion> findLatestActive() {
        return jdbc.query("""
                        SELECT * FROM recognition_rule_version
                        WHERE status = 'ACTIVE' ORDER BY version DESC LIMIT 1
                        """, VERSION_MAPPER)
                .stream().findFirst();
    }

    /** 全部版本（管理/自检用）。 */
    public List<RecognitionRuleVersion> findAllVersions() {
        return jdbc.query(
                "SELECT * FROM recognition_rule_version ORDER BY version", VERSION_MAPPER);
    }

    /** 某版本是否承认指定船旗。 */
    public boolean isFlagRecognized(long ruleVersionId, String flagCode) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_flag WHERE rule_version_id = ? AND flag_code = ?",
                Integer.class, ruleVersionId, flagCode);
        return cnt != null && cnt > 0;
    }

    /** 某版本承认的全部船旗代码（视图组装用）。 */
    public List<String> findFlagCodes(long ruleVersionId) {
        return jdbc.queryForList(
                "SELECT flag_code FROM recognition_flag WHERE rule_version_id = ? ORDER BY flag_code",
                String.class, ruleVersionId);
    }

    /** 按船旗国检查项目代码查映射（版本内唯一）。 */
    public Optional<RecognitionItemMapping> findMapping(long ruleVersionId, String flagItemCode) {
        return jdbc.query("""
                        SELECT * FROM recognition_item_mapping
                        WHERE rule_version_id = ? AND flag_item_code = ?
                        """, MAPPING_MAPPER, ruleVersionId, flagItemCode)
                .stream().findFirst();
    }

    /** 某版本的全部项目映射（自检用）。 */
    public List<RecognitionItemMapping> findMappings(long ruleVersionId) {
        return jdbc.query(
                "SELECT * FROM recognition_item_mapping WHERE rule_version_id = ? ORDER BY id",
                MAPPING_MAPPER, ruleVersionId);
    }
}
