package com.example.starter.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 风险抽检数据访问层：风险绑定、冻结清单、逐只结果与变更证据，全部参数化 SQL。
 */
@Repository
public class RiskRepository {

    private final JdbcTemplate jdbc;

    public RiskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 风险绑定行，每批至多一行。 */
    public record RiskBindingRow(long id, long batchId, int version, String level, String ruleVersion,
                                 long currentSequenceNo, String createdAt, String updatedAt) {
    }

    /** 抽检清单冻结行。frozenDeclared/frozenSamples 为字典序 JSON 数组文本。 */
    public record ChecklistRow(long id, long batchId, long sequenceNo, String kind, String level,
                               String ruleVersion, String status, String frozenDeclared,
                               String frozenSamples, String createdAt, String completedAt) {
    }

    /** 抽检逐只结果行。 */
    public record SampleRow(long id, long batchId, long sequenceNo, String headNo, String result,
                            String submittedAt) {
    }

    /** 风险变更证据行。 */
    public record ChangeEventRow(long id, long batchId, String eventType, String fromLevel, String toLevel,
                                 String fromRuleVersion, String toRuleVersion, long sequenceNo,
                                 int fromVersion, int toVersion, String createdAt) {
    }

    private static final RowMapper<RiskBindingRow> BINDING_MAPPER = (rs, n) -> new RiskBindingRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getInt("version"), rs.getString("level"),
            rs.getString("rule_version"), rs.getLong("current_sequence_no"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<ChecklistRow> CHECKLIST_MAPPER = (rs, n) -> new ChecklistRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("sequence_no"), rs.getString("kind"),
            rs.getString("level"), rs.getString("rule_version"), rs.getString("status"),
            rs.getString("frozen_declared"), rs.getString("frozen_samples"),
            rs.getString("created_at"), rs.getString("completed_at"));

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("sequence_no"), rs.getString("head_no"),
            rs.getString("result"), rs.getString("submitted_at"));

    private static final RowMapper<ChangeEventRow> EVENT_MAPPER = (rs, n) -> new ChangeEventRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("event_type"),
            rs.getString("from_level"), rs.getString("to_level"),
            rs.getString("from_rule_version"), rs.getString("to_rule_version"), rs.getLong("sequence_no"),
            rs.getInt("from_version"), rs.getInt("to_version"), rs.getString("created_at"));

    // ---------- 风险绑定 ----------

    public void insertBinding(long batchId, int version, String level, String ruleVersion,
                              long currentSequenceNo, String now) {
        jdbc.update("INSERT INTO risk_binding(batch_id, version, level, rule_version,"
                        + " current_sequence_no, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                batchId, version, level, ruleVersion, currentSequenceNo, now, now);
    }

    public Optional<RiskBindingRow> findBinding(long batchId) {
        List<RiskBindingRow> rows = jdbc.query(
                "SELECT * FROM risk_binding WHERE batch_id = ?", BINDING_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 行锁读取风险绑定（须在事务内调用），用于变更/结果与批次状态互斥。 */
    public Optional<RiskBindingRow> findBindingForUpdate(long batchId) {
        List<RiskBindingRow> rows = jdbc.query(
                "SELECT * FROM risk_binding WHERE batch_id = ? FOR UPDATE", BINDING_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 乐观更新绑定：仅当前版本等于 expectedVersion 时生效，返回受影响行数。 */
    public int updateBinding(long batchId, int expectedVersion, int newVersion, String level,
                             String ruleVersion, long currentSequenceNo, String now) {
        return jdbc.update("UPDATE risk_binding SET version = ?, level = ?, rule_version = ?,"
                        + " current_sequence_no = ?, updated_at = ? WHERE batch_id = ? AND version = ?",
                newVersion, level, ruleVersion, currentSequenceNo, now, batchId, expectedVersion);
    }

    // ---------- 批次行锁与专项隔离 ----------

    /** 非终态→SPECIAL_QUARANTINE 条件流转，返回受影响行数（RELEASED 终态不被覆盖）。 */
    public int markSpecialQuarantine(long batchId) {
        return jdbc.update("UPDATE slaughter_batch SET status = 'SPECIAL_QUARANTINE' WHERE id = ?"
                        + " AND status IN ('PRE_QUARANTINED','INSPECTING','PAUSED','JUDGED')",
                batchId);
    }

    // ---------- 抽检清单 ----------

    public void insertChecklist(long batchId, long sequenceNo, String kind, String level,
                                String ruleVersion, String status, String frozenDeclared,
                                String frozenSamples, String now) {
        jdbc.update("INSERT INTO risk_checklist(batch_id, sequence_no, kind, level, rule_version,"
                        + " status, frozen_declared, frozen_samples, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                batchId, sequenceNo, kind, level, ruleVersion, status, frozenDeclared, frozenSamples, now);
    }

    public List<ChecklistRow> findChecklists(long batchId) {
        return jdbc.query("SELECT * FROM risk_checklist WHERE batch_id = ? ORDER BY sequence_no",
                CHECKLIST_MAPPER, batchId);
    }

    public Optional<ChecklistRow> findChecklist(long batchId, long sequenceNo) {
        List<ChecklistRow> rows = jdbc.query(
                "SELECT * FROM risk_checklist WHERE batch_id = ? AND sequence_no = ?",
                CHECKLIST_MAPPER, batchId, sequenceNo);
        return rows.stream().findFirst();
    }

    /** OPEN→COMPLETED 条件流转，返回受影响行数。 */
    public int completeChecklist(long batchId, long sequenceNo, String completedAt) {
        return jdbc.update("UPDATE risk_checklist SET status = 'COMPLETED', completed_at = ?"
                        + " WHERE batch_id = ? AND sequence_no = ? AND status = 'OPEN'",
                completedAt, batchId, sequenceNo);
    }

    /** 原子扩充：更新当前清单冻结抽检集合与风险规则，清单序号与既有结果保持不变。 */
    public int expandChecklist(long batchId, long sequenceNo, String level, String ruleVersion,
                               String frozenSamples, String now) {
        return jdbc.update("UPDATE risk_checklist SET kind = 'EXPANDED', level = ?, rule_version = ?,"
                        + " frozen_samples = ? WHERE batch_id = ? AND sequence_no = ? AND status = 'OPEN'",
                level, ruleVersion, frozenSamples, batchId, sequenceNo);
    }

    // ---------- 逐只结果 ----------

    public void insertSample(long batchId, long sequenceNo, String headNo, String result,
                             String submittedAt) {
        jdbc.update("INSERT INTO risk_sample(batch_id, sequence_no, head_no, result, submitted_at)"
                        + " VALUES (?,?,?,?,?)",
                batchId, sequenceNo, headNo, result, submittedAt);
    }

    public List<SampleRow> findSamples(long batchId, long sequenceNo) {
        return jdbc.query("SELECT * FROM risk_sample WHERE batch_id = ? AND sequence_no = ?"
                        + " ORDER BY head_no",
                SAMPLE_MAPPER, batchId, sequenceNo);
    }

    public List<SampleRow> findAllSamples(long batchId) {
        return jdbc.query("SELECT * FROM risk_sample WHERE batch_id = ? ORDER BY sequence_no, head_no",
                SAMPLE_MAPPER, batchId);
    }

    // ---------- 变更证据 ----------

    public void insertChangeEvent(long batchId, String eventType, String fromLevel, String toLevel,
                                  String fromRuleVersion, String toRuleVersion, long sequenceNo,
                                  int fromVersion, int toVersion, String now) {
        jdbc.update("INSERT INTO risk_change_event(batch_id, event_type, from_level, to_level,"
                        + " from_rule_version, to_rule_version, sequence_no, from_version, to_version,"
                        + " created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                batchId, eventType, fromLevel, toLevel, fromRuleVersion, toRuleVersion, sequenceNo,
                fromVersion, toVersion, now);
    }

    public List<ChangeEventRow> findChangeEvents(long batchId) {
        return jdbc.query("SELECT * FROM risk_change_event WHERE batch_id = ? ORDER BY id",
                EVENT_MAPPER, batchId);
    }
}
