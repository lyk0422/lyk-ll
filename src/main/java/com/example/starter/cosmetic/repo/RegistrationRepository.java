package com.example.starter.cosmetic.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.starter.cosmetic.domain.ChangeType;
import com.example.starter.cosmetic.domain.ClaimRow;
import com.example.starter.cosmetic.domain.IngredientRow;
import com.example.starter.cosmetic.domain.ObjectionRow;
import com.example.starter.cosmetic.domain.ObjectionStatus;
import com.example.starter.cosmetic.domain.RegistrationRow;
import com.example.starter.cosmetic.domain.RegistrationStatus;
import com.example.starter.cosmetic.domain.ReportConclusion;
import com.example.starter.cosmetic.domain.ReportRow;
import com.example.starter.cosmetic.domain.ReviewRecordRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 备案及其明细（宣称、配方、报告、异议、审查记录）的数据访问。
 * 状态流转一律使用带前置状态条件的 UPDATE，保证并发下形成唯一终态。
 */
@Repository
public class RegistrationRepository {

    private final JdbcClient jdbc;

    public RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入备案记录，返回自增主键。
     */
    public long insert(RegistrationRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO registration
                    (registration_no, product_id, version, change_type, previous_id, status,
                     packaging_text, publicity_start_date, publicity_deadline, effective_at,
                     cancelled_at, reject_reason, request_id, created_at)
                VALUES
                    (:no, :productId, :version, :changeType, :previousId, :status,
                     :packagingText, :publicityStartDate, :publicityDeadline, :effectiveAt,
                     :cancelledAt, :rejectReason, :requestId, :createdAt)
                """)
                .param("no", row.registrationNo())
                .param("productId", row.productId())
                .param("version", row.version())
                .param("changeType", row.changeType().name())
                .param("previousId", row.previousId())
                .param("status", row.status().name())
                .param("packagingText", row.packagingText())
                .param("publicityStartDate", row.publicityStartDate())
                .param("publicityDeadline", row.publicityDeadline() == null ? null : Timestamp.from(row.publicityDeadline()))
                .param("effectiveAt", row.effectiveAt() == null ? null : Timestamp.from(row.effectiveAt()))
                .param("cancelledAt", row.cancelledAt() == null ? null : Timestamp.from(row.cancelledAt()))
                .param("rejectReason", row.rejectReason())
                .param("requestId", row.requestId())
                .param("createdAt", Timestamp.from(row.createdAt()))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询备案。
     */
    public Optional<RegistrationRow> findById(long id) {
        return jdbc.sql("SELECT * FROM registration WHERE id = :id")
                .param("id", id)
                .query(RegistrationRepository::mapRow)
                .optional();
    }

    /**
     * 查询产品的全部备案版本（变更链），按版本号升序。
     */
    public List<RegistrationRow> findByProductId(long productId) {
        return jdbc.sql("SELECT * FROM registration WHERE product_id = :productId ORDER BY version")
                .param("productId", productId)
                .query(RegistrationRepository::mapRow)
                .list();
    }

    /**
     * 产品是否存在进行中或已生效的备案（公示/异议审查/生效）。
     */
    public boolean hasActiveRegistration(long productId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM registration
                WHERE product_id = :productId AND status IN ('PUBLICITY', 'OBJECTION_REVIEW', 'EFFECTIVE')
                """)
                .param("productId", productId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /**
     * 产品当前生效备案数量，用于校验“同一产品同时刻只允许一个有效备案”。
     */
    public int countEffectiveByProduct(long productId) {
        return jdbc.sql("SELECT COUNT(*) FROM registration WHERE product_id = :productId AND status = 'EFFECTIVE'")
                .param("productId", productId)
                .query(Integer.class)
                .single();
    }

    /**
     * 公示期内受理异议：仅当仍为公示中且未到期满时刻才转入异议审查，返回受影响行数。
     */
    public int markObjectionReview(long id, Instant now) {
        return jdbc.sql("""
                UPDATE registration SET status = 'OBJECTION_REVIEW'
                WHERE id = :id AND status = 'PUBLICITY' AND publicity_deadline > :now
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 公示期满生效：仅当仍为公示中才生效，返回受影响行数（与异议受理互斥）。
     */
    public int markEffective(long id, Instant now) {
        return jdbc.sql("""
                UPDATE registration SET status = 'EFFECTIVE', effective_at = :now
                WHERE id = :id AND status = 'PUBLICITY'
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 异议审查后恢复公示（期满前异议不成立）。
     */
    public int backToPublicity(long id) {
        return jdbc.sql("UPDATE registration SET status = 'PUBLICITY' WHERE id = :id AND status = 'OBJECTION_REVIEW'")
                .param("id", id)
                .update();
    }

    /**
     * 异议不成立且公示期已满：直接生效，返回受影响行数。
     */
    public int markEffectiveFromObjection(long id, Instant now) {
        return jdbc.sql("""
                UPDATE registration SET status = 'EFFECTIVE', effective_at = :now
                WHERE id = :id AND status = 'OBJECTION_REVIEW'
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 异议成立：备案不予生效。
     */
    public int markRejectedFromObjection(long id, String reason) {
        return jdbc.sql("""
                UPDATE registration SET status = 'REJECTED', reject_reason = :reason
                WHERE id = :id AND status = 'OBJECTION_REVIEW'
                """)
                .param("id", id)
                .param("reason", reason)
                .update();
    }

    /**
     * 将同产品其他生效备案置为被替代（与新生效备案同事务调用）。
     */
    public int supersedeOtherEffective(long productId, long keepId) {
        return jdbc.sql("""
                UPDATE registration SET status = 'SUPERSEDED'
                WHERE product_id = :productId AND status = 'EFFECTIVE' AND id <> :keepId
                """)
                .param("productId", productId)
                .param("keepId", keepId)
                .update();
    }

    /**
     * 注销生效备案（终态），返回受影响行数。
     */
    public int markCancelled(long id, Instant now) {
        return jdbc.sql("""
                UPDATE registration SET status = 'CANCELLED', cancelled_at = :now
                WHERE id = :id AND status = 'EFFECTIVE'
                """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 查询已到期满时刻的公示中备案。
     */
    public List<Long> findDuePublicity(Instant now) {
        return jdbc.sql("SELECT id FROM registration WHERE status = 'PUBLICITY' AND publicity_deadline <= :now ORDER BY id")
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .list();
    }

    /**
     * 写入功效宣称明细。
     */
    public void insertClaim(long registrationId, String efficacyCode) {
        jdbc.sql("INSERT INTO registration_claim (registration_id, efficacy_code) VALUES (:rid, :code)")
                .param("rid", registrationId)
                .param("code", efficacyCode)
                .update();
    }

    /**
     * 查询备案的功效宣称明细。
     */
    public List<ClaimRow> findClaims(long registrationId) {
        return jdbc.sql("SELECT * FROM registration_claim WHERE registration_id = :rid ORDER BY id")
                .param("rid", registrationId)
                .query((rs, rowNum) -> new ClaimRow(
                        rs.getLong("id"), rs.getLong("registration_id"), rs.getString("efficacy_code")))
                .list();
    }

    /**
     * 写入配方成分明细。
     */
    public void insertIngredient(long registrationId, String ingredientCode, java.math.BigDecimal concentration) {
        jdbc.sql("INSERT INTO registration_ingredient (registration_id, ingredient_code, concentration) VALUES (:rid, :code, :conc)")
                .param("rid", registrationId)
                .param("code", ingredientCode)
                .param("conc", concentration)
                .update();
    }

    /**
     * 查询备案的配方成分明细。
     */
    public List<IngredientRow> findIngredients(long registrationId) {
        return jdbc.sql("SELECT * FROM registration_ingredient WHERE registration_id = :rid ORDER BY id")
                .param("rid", registrationId)
                .query((rs, rowNum) -> new IngredientRow(
                        rs.getLong("id"), rs.getLong("registration_id"),
                        rs.getString("ingredient_code"), rs.getBigDecimal("concentration")))
                .list();
    }

    /**
     * 写入功效评价试验报告。
     */
    public void insertReport(long registrationId, String efficacyCode, ReportConclusion conclusion,
                             LocalDate issuedDate, String lab) {
        jdbc.sql("""
                INSERT INTO test_report (registration_id, efficacy_code, conclusion, issued_date, lab)
                VALUES (:rid, :code, :conclusion, :issuedDate, :lab)
                """)
                .param("rid", registrationId)
                .param("code", efficacyCode)
                .param("conclusion", conclusion.name())
                .param("issuedDate", issuedDate)
                .param("lab", lab)
                .update();
    }

    /**
     * 查询备案的试验报告。
     */
    public List<ReportRow> findReports(long registrationId) {
        return jdbc.sql("SELECT * FROM test_report WHERE registration_id = :rid ORDER BY id")
                .param("rid", registrationId)
                .query((rs, rowNum) -> new ReportRow(
                        rs.getLong("id"), rs.getLong("registration_id"), rs.getString("efficacy_code"),
                        ReportConclusion.valueOf(rs.getString("conclusion")),
                        rs.getDate("issued_date").toLocalDate(), rs.getString("lab")))
                .list();
    }

    /**
     * 写入异议记录，返回自增主键。
     */
    public long insertObjection(long registrationId, String content, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO objection (registration_id, content, status, created_at) VALUES (:rid, :content, 'PENDING', :now)")
                .param("rid", registrationId)
                .param("content", content)
                .param("now", Timestamp.from(now))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询备案的待审查异议。
     */
    public Optional<ObjectionRow> findPendingObjection(long registrationId) {
        return jdbc.sql("SELECT * FROM objection WHERE registration_id = :rid AND status = 'PENDING' ORDER BY id")
                .param("rid", registrationId)
                .query((rs, rowNum) -> new ObjectionRow(
                        rs.getLong("id"), rs.getLong("registration_id"), rs.getString("content"),
                        ObjectionStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /**
     * 更新异议状态。
     */
    public void updateObjectionStatus(long objectionId, ObjectionStatus status) {
        jdbc.sql("UPDATE objection SET status = :status WHERE id = :id")
                .param("status", status.name())
                .param("id", objectionId)
                .update();
    }

    /**
     * 写入形式审查明细。
     */
    public void insertReviewRecord(long registrationId, String item, boolean passed, String detail, Instant now) {
        jdbc.sql("INSERT INTO review_record (registration_id, item, result, detail, created_at) VALUES (:rid, :item, :result, :detail, :now)")
                .param("rid", registrationId)
                .param("item", item)
                .param("result", passed ? "PASS" : "FAIL")
                .param("detail", detail)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 查询备案的审查明细，按记录顺序。
     */
    public List<ReviewRecordRow> findReviewRecords(long registrationId) {
        return jdbc.sql("SELECT * FROM review_record WHERE registration_id = :rid ORDER BY id")
                .param("rid", registrationId)
                .query((rs, rowNum) -> new ReviewRecordRow(
                        rs.getLong("id"), rs.getLong("registration_id"), rs.getString("item"),
                        rs.getString("result"), rs.getString("detail"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static RegistrationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp deadline = rs.getTimestamp("publicity_deadline");
        Timestamp effectiveAt = rs.getTimestamp("effective_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        java.sql.Date publicityStart = rs.getDate("publicity_start_date");
        long previousId = rs.getLong("previous_id");
        return new RegistrationRow(
                rs.getLong("id"),
                rs.getString("registration_no"),
                rs.getLong("product_id"),
                rs.getInt("version"),
                ChangeType.valueOf(rs.getString("change_type")),
                rs.wasNull() ? null : previousId,
                RegistrationStatus.valueOf(rs.getString("status")),
                rs.getString("packaging_text"),
                publicityStart == null ? null : publicityStart.toLocalDate(),
                deadline == null ? null : deadline.toInstant(),
                effectiveAt == null ? null : effectiveAt.toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant(),
                rs.getString("reject_reason"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
