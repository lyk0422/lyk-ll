package com.example.starter.repository;

import com.example.starter.domain.ProcessStatus;
import com.example.starter.model.ReviewProcessRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 批次与审查流程数据访问（vaccine_batch、review_process、enterprise_suspension、late_document_event）。
 */
@Repository
public class BatchReleaseRepository {

    private static final String PROCESS_SELECT =
            "SELECT p.id AS process_id, p.batch_id, b.batch_no, b.enterprise, b.product_name, "
                    + "p.status, p.missing_items, p.correction_deadline, p.evaluation_result, p.fail_reason, p.created_at "
                    + "FROM review_process p JOIN vaccine_batch b ON b.id = p.batch_id ";

    private static final RowMapper<ReviewProcessRow> PROCESS_MAPPER = (rs, rowNum) -> new ReviewProcessRow(
            rs.getLong("process_id"),
            rs.getLong("batch_id"),
            rs.getString("batch_no"),
            rs.getString("enterprise"),
            rs.getString("product_name"),
            ProcessStatus.valueOf(rs.getString("status")),
            rs.getString("missing_items"),
            rs.getDate("correction_deadline") == null ? null : rs.getDate("correction_deadline").toLocalDate(),
            rs.getString("evaluation_result"),
            rs.getString("fail_reason"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public BatchReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建批次；同批号并发插入时由唯一约束保证仅一条成功。
     *
     * @return 批次ID
     * @throws org.springframework.dao.DuplicateKeyException 批号已存在
     */
    public long insertBatch(String batchNo, String enterprise, String productName, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vaccine_batch (batch_no, enterprise, product_name, created_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, enterprise);
            ps.setString(3, productName);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 新建审查流程。
     *
     * @return 流程ID
     */
    public long insertProcess(long batchId, String requestId, String fingerprint, ProcessStatus status,
                              String missingItemsJson, LocalDate correctionDeadline, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_process (batch_id, request_id, fingerprint, status, missing_items, correction_deadline, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, requestId);
            ps.setString(3, fingerprint);
            ps.setString(4, status.name());
            ps.setString(5, missingItemsJson);
            if (correctionDeadline == null) {
                ps.setDate(6, null);
            } else {
                ps.setDate(6, Date.valueOf(correctionDeadline));
            }
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按批号查询审查流程。
     */
    public Optional<ReviewProcessRow> findProcessByBatchNo(String batchNo) {
        return jdbc.query(PROCESS_SELECT + "WHERE b.batch_no = ?", PROCESS_MAPPER, batchNo)
                .stream().findFirst();
    }

    /**
     * 条件更新流程状态：仅当前状态为 expected 时生效，用于并发下形成唯一终态。
     *
     * @return true 更新成功（状态机推进）；false 当前状态已不符合预期
     */
    public boolean transitionStatus(long processId, ProcessStatus expected, ProcessStatus target, Instant now) {
        return jdbc.update("UPDATE review_process SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                target.name(), Timestamp.from(now), processId, expected.name()) == 1;
    }

    /**
     * 补正完成：清除缺项清单与补正时限并推进状态（条件更新）。
     *
     * @return true 更新成功
     */
    public boolean completeCorrection(long processId, ProcessStatus expected, ProcessStatus target, Instant now) {
        return jdbc.update("UPDATE review_process SET status = ?, missing_items = NULL, correction_deadline = NULL, updated_at = ? "
                        + "WHERE id = ? AND status = ?",
                target.name(), Timestamp.from(now), processId, expected.name()) == 1;
    }

    /**
     * 写入综合评定结论并推进状态（条件更新）。
     *
     * @return true 更新成功
     */
    public boolean completeEvaluation(long processId, String result, String failReason,
                                      ProcessStatus expected, ProcessStatus target, Instant now) {
        return jdbc.update("UPDATE review_process SET status = ?, evaluation_result = ?, fail_reason = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?",
                target.name(), result, failReason, Timestamp.from(now), processId, expected.name()) == 1;
    }

    /**
     * 查询同企业同品种最近若干批的评定结论（按流程ID倒序），用于连续不合格判定。
     */
    public List<String> recentEvaluationResults(String enterprise, String productName, int limit) {
        return jdbc.query("SELECT p.evaluation_result FROM review_process p "
                        + "JOIN vaccine_batch b ON b.id = p.batch_id "
                        + "WHERE b.enterprise = ? AND b.product_name = ? AND p.evaluation_result IS NOT NULL "
                        + "ORDER BY p.id DESC LIMIT ?",
                (rs, rowNum) -> rs.getString("evaluation_result"),
                enterprise, productName, limit);
    }

    /**
     * 是否已存在暂停受理记录。
     */
    public boolean suspensionExists(String enterprise, String productName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enterprise_suspension WHERE enterprise = ? AND product_name = ?",
                Integer.class, enterprise, productName);
        return count != null && count > 0;
    }

    /**
     * 写入暂停受理记录（连续3批不合格触发，转核查）。
     */
    public void insertSuspension(String enterprise, String productName, String reason, Instant now) {
        jdbc.update("INSERT INTO enterprise_suspension (enterprise, product_name, reason, status, created_at) VALUES (?,?,?,?,?)",
                enterprise, productName, reason, "SUSPENDED", Timestamp.from(now));
    }

    /**
     * 记录迟到资料事件（归档后迟到资料只记事件，不改变状态）。
     */
    public void insertLateEvent(long batchId, String requestId, String detail, Instant now) {
        jdbc.update("INSERT INTO late_document_event (batch_id, request_id, detail, created_at) VALUES (?,?,?,?)",
                batchId, requestId, detail, Timestamp.from(now));
    }

    /**
     * 查询批次的迟到资料事件数，供测试与复算核对。
     */
    public int countLateEvents(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_document_event WHERE batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }
}
