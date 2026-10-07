package com.example.starter.damage.store;

import com.example.starter.damage.domain.ChangeOrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 修理变更单、全清单快照与复核记录数据访问。
 * 金额为正整数分；时刻均为 UTC（timestamp）。
 * 每案最多一个活动候选由 active_case_id 唯一索引仲裁；单据版本通过行锁 + 条件更新推进。
 */
@Repository
public class ChangeOrderRepository {

    private static final RowMapper<ChangeOrderRow> ORDER_MAPPER = (rs, n) -> new ChangeOrderRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getInt("version"),
            ChangeOrderStatus.valueOf(rs.getString("status")),
            rs.getLong("approval_id"),
            (Long) rs.getObject("opening_id"),
            rs.getLong("supplier_id"),
            rs.getInt("qualification_version"),
            rs.getLong("original_total_cents"),
            rs.getLong("new_total_cents"),
            (Long) rs.getObject("new_estimate_id"),
            (Long) rs.getObject("new_approval_id"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("decided_at")));

    private static final RowMapper<ChangeOrderLineRow> LINE_MAPPER = (rs, n) -> new ChangeOrderLineRow(
            rs.getLong("id"),
            rs.getLong("change_order_id"),
            rs.getLong("damage_item_id"),
            rs.getInt("line_no"),
            rs.getBoolean("changed"),
            rs.getString("before_method"),
            rs.getLong("before_amount_cents"),
            rs.getString("after_method"),
            rs.getLong("after_amount_cents"));

    private static final RowMapper<ChangeReviewRow> REVIEW_MAPPER = (rs, n) -> new ChangeReviewRow(
            rs.getLong("id"),
            rs.getLong("change_order_id"),
            rs.getString("reviewer"),
            rs.getString("decision"),
            rs.getTimestamp("created_at").toInstant());

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public ChangeOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 变更单行；newEstimateId/newApprovalId/decidedAt 未生效或未决时为 null */
    public record ChangeOrderRow(
            long id,
            long caseId,
            int version,
            ChangeOrderStatus status,
            long approvalId,
            Long openingId,
            long supplierId,
            int qualificationVersion,
            long originalTotalCents,
            long newTotalCents,
            Long newEstimateId,
            Long newApprovalId,
            Instant createdAt,
            Instant decidedAt) {
    }

    /** 变更单全清单快照行 */
    public record ChangeOrderLineRow(
            long id,
            long changeOrderId,
            long damageItemId,
            int lineNo,
            boolean changed,
            String beforeMethod,
            long beforeAmountCents,
            String afterMethod,
            long afterAmountCents) {
    }

    /** 复核记录行 */
    public record ChangeReviewRow(
            long id,
            long changeOrderId,
            String reviewer,
            String decision,
            Instant createdAt) {
    }

    // ---- 变更单 ----

    /** 新建候选：version=1、status=PENDING、active_case_id=caseId，返回新 ID */
    public long insertChangeOrder(long caseId, long approvalId, Long openingId, long supplierId,
                                  int qualificationVersion, long originalTotalCents, long newTotalCents,
                                  String requestId, Instant now) {
        jdbc.update("insert into change_order(case_id, version, status, approval_id, opening_id, supplier_id,"
                        + " qualification_version, original_total_cents, new_total_cents,"
                        + " active_case_id, request_id, created_at, updated_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                caseId, 1, ChangeOrderStatus.PENDING.name(), approvalId, openingId, supplierId,
                qualificationVersion, originalTotalCents, newTotalCents,
                caseId, requestId, Timestamp.from(now), Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "select max(id) from change_order where case_id = ? and request_id = ?",
                Long.class, caseId, requestId);
        return id == null ? 0 : id;
    }

    public Optional<ChangeOrderRow> findById(long changeOrderId) {
        return jdbc.query("select * from change_order where id = ?", ORDER_MAPPER, changeOrderId)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询（复核/撤回/完工竞争在此串行） */
    public Optional<ChangeOrderRow> findByIdForUpdate(long changeOrderId) {
        return jdbc.query("select * from change_order where id = ? for update", ORDER_MAPPER, changeOrderId)
                .stream().findFirst();
    }

    /** 本案已存在变更单数量（创建时 expectedVersion 校验用） */
    public int countByCase(long caseId) {
        Integer count = jdbc.queryForObject("select count(*) from change_order where case_id = ?",
                Integer.class, caseId);
        return count == null ? 0 : count;
    }

    /** 本案当前活动（待复核）候选，无则空 */
    public Optional<ChangeOrderRow> findActiveByCase(long caseId) {
        return jdbc.query("select * from change_order where active_case_id = ?", ORDER_MAPPER, caseId)
                .stream().findFirst();
    }

    /** 本案是否存在活动候选（完工入口阻断判定） */
    public boolean hasActiveCandidate(long caseId) {
        Integer count = jdbc.queryForObject("select count(*) from change_order where active_case_id = ?",
                Integer.class, caseId);
        return count != null && count > 0;
    }

    public List<ChangeOrderRow> listByCase(long caseId) {
        return jdbc.query("select * from change_order where case_id = ? order by id", ORDER_MAPPER, caseId);
    }

    /** 仅推进版本（首位复核人同意但尚未集齐两人）；行锁内调用 */
    public int bumpVersion(long changeOrderId, int expectedVersion, int newVersion, Instant now) {
        return jdbc.update("update change_order set version = ?, updated_at = ?"
                        + " where id = ? and version = ? and status = 'PENDING'",
                newVersion, Timestamp.from(now), changeOrderId, expectedVersion);
    }

    /** 终态切换（生效/撤回/驳回）：释放活动候选键并记决策时刻；行锁内调用 */
    public int decide(long changeOrderId, int expectedVersion, ChangeOrderStatus newStatus, int newVersion,
                      Long newEstimateId, Long newApprovalId, Instant decidedAt, Instant now) {
        return jdbc.update("update change_order set status = ?, version = ?, active_case_id = null,"
                        + " new_estimate_id = ?, new_approval_id = ?, decided_at = ?, updated_at = ?"
                        + " where id = ? and version = ? and status = 'PENDING'",
                newStatus.name(), newVersion, newEstimateId, newApprovalId,
                Timestamp.from(decidedAt), Timestamp.from(now), changeOrderId, expectedVersion);
    }

    // ---- 全清单快照 ----

    public void insertLine(long changeOrderId, long damageItemId, int lineNo, boolean changed,
                           String beforeMethod, long beforeAmountCents,
                           String afterMethod, long afterAmountCents) {
        jdbc.update("insert into change_order_line(change_order_id, damage_item_id, line_no, changed,"
                        + " before_method, before_amount_cents, after_method, after_amount_cents)"
                        + " values (?,?,?,?,?,?,?,?)",
                changeOrderId, damageItemId, lineNo, changed,
                beforeMethod, beforeAmountCents, afterMethod, afterAmountCents);
    }

    public List<ChangeOrderLineRow> listLines(long changeOrderId) {
        return jdbc.query("select * from change_order_line where change_order_id = ? order by line_no",
                LINE_MAPPER, changeOrderId);
    }

    // ---- 复核 ----

    public void insertReview(long changeOrderId, String reviewer, String decision, String requestId, Instant now) {
        jdbc.update("insert into change_order_review(change_order_id, reviewer, decision, request_id, created_at)"
                        + " values (?,?,?,?,?)",
                changeOrderId, reviewer, decision, requestId, Timestamp.from(now));
    }

    public List<ChangeReviewRow> listReviews(long changeOrderId) {
        return jdbc.query("select * from change_order_review where change_order_id = ? order by id",
                REVIEW_MAPPER, changeOrderId);
    }
}
