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
 * 修理变更单数据访问：候选、全清单冻结行与复核记录。
 * 每案最多一个活动候选由 pending_key 唯一索引仲裁；
 * 单据乐观锁通过“条件更新受影响行数”仲裁并发修改。
 */
@Repository
public class ChangeOrderRepository {

    private static final RowMapper<ChangeOrderRow> ORDER_MAPPER = (rs, n) -> new ChangeOrderRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getInt("seq"),
            rs.getInt("version"),
            ChangeOrderStatus.valueOf(rs.getString("status")),
            rs.getLong("approval_id"),
            rs.getLong("estimate_id"),
            rs.getLong("opening_id"),
            rs.getLong("supplier_id"),
            rs.getInt("qualification_version"),
            rs.getLong("old_total_cents"),
            rs.getLong("new_total_cents"),
            (Long) rs.getObject("new_estimate_id"),
            (Long) rs.getObject("new_approval_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ChangeOrderLineRow> LINE_MAPPER = (rs, n) -> new ChangeOrderLineRow(
            rs.getLong("id"),
            rs.getLong("change_order_id"),
            rs.getLong("damage_item_id"),
            rs.getBoolean("changed"),
            rs.getString("old_method_code"),
            rs.getLong("old_amount_cents"),
            rs.getString("new_method_code"),
            rs.getLong("new_amount_cents"));

    private static final RowMapper<ChangeOrderReviewRow> REVIEW_MAPPER = (rs, n) -> new ChangeOrderReviewRow(
            rs.getLong("id"),
            rs.getLong("change_order_id"),
            rs.getString("reviewer"),
            rs.getString("decision"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ChangeOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 变更单行 */
    public record ChangeOrderRow(
            long id,
            long caseId,
            int seq,
            int version,
            ChangeOrderStatus status,
            long approvalId,
            long estimateId,
            long openingId,
            long supplierId,
            int qualificationVersion,
            long oldTotalCents,
            long newTotalCents,
            Long newEstimateId,
            Long newApprovalId,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 变更单全清单冻结行 */
    public record ChangeOrderLineRow(
            long id,
            long changeOrderId,
            long damageItemId,
            boolean changed,
            String oldMethodCode,
            long oldAmountCents,
            String newMethodCode,
            long newAmountCents) {
    }

    /** 复核记录行 */
    public record ChangeOrderReviewRow(
            long id,
            long changeOrderId,
            String reviewer,
            String decision,
            Instant createdAt) {
    }

    /** 原中标报价行（按残损项对齐的冻结证据） */
    public record WinnerLineRow(
            long damageItemId,
            String methodCode,
            long amountCents) {
    }

    // ---- 变更单 ----

    /** 创建候选：version=1、status=PENDING、pending_key=caseId，返回新 ID */
    public long insertChangeOrder(long caseId, int seq, long approvalId, long estimateId, long openingId,
                                  long supplierId, int qualificationVersion,
                                  long oldTotalCents, long newTotalCents,
                                  String requestId, Instant now) {
        jdbc.update("insert into change_order(case_id, seq, version, status, pending_key,"
                        + " approval_id, estimate_id, opening_id, supplier_id, qualification_version,"
                        + " old_total_cents, new_total_cents, request_id, created_at, updated_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                caseId, seq, 1, ChangeOrderStatus.PENDING.name(), caseId,
                approvalId, estimateId, openingId, supplierId, qualificationVersion,
                oldTotalCents, newTotalCents, requestId, Timestamp.from(now), Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "select max(id) from change_order where case_id = ? and seq = ?", Long.class, caseId, seq);
        return id == null ? 0 : id;
    }

    public Optional<ChangeOrderRow> findById(long id) {
        return jdbc.query("select * from change_order where id = ?", ORDER_MAPPER, id)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询（复核/撤回/完工竞争时串行化） */
    public Optional<ChangeOrderRow> findByIdForUpdate(long id) {
        return jdbc.query("select * from change_order where id = ? for update", ORDER_MAPPER, id)
                .stream().findFirst();
    }

    /** 案件下最大变更单序号，无则 0 */
    public int maxSeq(long caseId) {
        Integer max = jdbc.queryForObject("select coalesce(max(seq), 0) from change_order where case_id = ?",
                Integer.class, caseId);
        return max == null ? 0 : max;
    }

    /** 案件是否存在待复核活动候选（阻断原完工入口） */
    public boolean hasPendingForCase(long caseId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from change_order where case_id = ? and status = 'PENDING'",
                Integer.class, caseId);
        return count != null && count > 0;
    }

    /** 案件下变更单列表（按序号） */
    public List<ChangeOrderRow> listByCase(long caseId) {
        return jdbc.query("select * from change_order where case_id = ? order by seq", ORDER_MAPPER, caseId);
    }

    /**
     * 条件推进单据状态：仅当 status='PENDING' 且 version=expectedVersion 时生效，
     * 同时清除 pending_key 解除完工阻断；返回受影响行数。
     */
    public int compareAndSetStatus(long id, int expectedVersion, ChangeOrderStatus newStatus, Instant now) {
        return jdbc.update("update change_order set status = ?, version = ?, pending_key = null, updated_at = ?"
                        + " where id = ? and version = ? and status = 'PENDING'",
                newStatus.name(), expectedVersion + 1, Timestamp.from(now), id, expectedVersion);
    }

    /** 生效换版：写入新估价/新批复并推进状态，条件同 compareAndSetStatus */
    public int markEffective(long id, int expectedVersion, long newEstimateId, long newApprovalId, Instant now) {
        return jdbc.update("update change_order set status = ?, version = ?, pending_key = null,"
                        + " new_estimate_id = ?, new_approval_id = ?, updated_at = ?"
                        + " where id = ? and version = ? and status = 'PENDING'",
                ChangeOrderStatus.EFFECTIVE.name(), expectedVersion + 1,
                newEstimateId, newApprovalId, Timestamp.from(now), id, expectedVersion);
    }

    // ---- 全清单冻结行 ----

    public void insertLine(long changeOrderId, long damageItemId, boolean changed,
                           String oldMethodCode, long oldAmountCents,
                           String newMethodCode, long newAmountCents) {
        jdbc.update("insert into change_order_line(change_order_id, damage_item_id, changed,"
                        + " old_method_code, old_amount_cents, new_method_code, new_amount_cents)"
                        + " values (?,?,?,?,?,?,?)",
                changeOrderId, damageItemId, changed, oldMethodCode, oldAmountCents,
                newMethodCode, newAmountCents);
    }

    public List<ChangeOrderLineRow> listLines(long changeOrderId) {
        return jdbc.query("select * from change_order_line where change_order_id = ? order by id",
                LINE_MAPPER, changeOrderId);
    }

    // ---- 复核 ----

    public void insertReview(long changeOrderId, String reviewer, String decision,
                             String requestId, Instant now) {
        jdbc.update("insert into change_order_review(change_order_id, reviewer, decision, request_id, created_at)"
                        + " values (?,?,?,?,?)",
                changeOrderId, reviewer, decision, requestId, Timestamp.from(now));
    }

    public List<ChangeOrderReviewRow> listReviews(long changeOrderId) {
        return jdbc.query("select * from change_order_review where change_order_id = ? order by id",
                REVIEW_MAPPER, changeOrderId);
    }

    // ---- 原中标报价行 ----

    /** 按开标冻结的中标报价版本，列出按残损项对齐的中标工法与分报价 */
    public List<WinnerLineRow> listWinnerLines(long winnerBidVersionId) {
        return jdbc.query("select ii.damage_item_id, bl.method_code, bl.amount_cents"
                        + " from bid_line bl"
                        + " join inquiry_item ii on ii.id = bl.inquiry_item_id"
                        + " where bl.bid_version_id = ? order by bl.line_no",
                (rs, n) -> new WinnerLineRow(
                        rs.getLong("damage_item_id"),
                        rs.getString("method_code"),
                        rs.getLong("amount_cents")),
                winnerBidVersionId);
    }
}
