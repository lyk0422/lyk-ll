package com.example.starter.repo;

import com.example.starter.domain.InspectionRecord;
import com.example.starter.domain.PublicityRecord;
import com.example.starter.domain.PublicityStatus;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.domain.ReviewStatus;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.List;

/**
 * 批次审查主表及检验链、公示链、事件流水存取。
 * 状态流转使用 SELECT ... FOR UPDATE 行锁，保证并发终态唯一。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Review> REVIEW_MAPPER = (rs, rowNum) -> {
        Review review = new Review();
        review.setId(rs.getLong("id"));
        review.setEnterpriseName(rs.getString("enterprise_name"));
        review.setProductName(rs.getString("product_name"));
        review.setBatchNo(rs.getString("batch_no"));
        review.setStatus(ReviewStatus.valueOf(rs.getString("status")));
        review.setDocumentsJson(rs.getString("documents_json"));
        review.setMissingJson(rs.getString("missing_json"));
        review.setDeclaredAt(rs.getTimestamp("declared_at").toInstant());
        Date notice = rs.getDate("correction_notice_date");
        review.setCorrectionNoticeDate(notice == null ? null : notice.toLocalDate());
        Date deadline = rs.getDate("correction_deadline");
        review.setCorrectionDeadline(deadline == null ? null : deadline.toLocalDate());
        Timestamp corrected = rs.getTimestamp("corrected_at");
        review.setCorrectedAt(corrected == null ? null : corrected.toInstant());
        Date pubStart = rs.getDate("publicity_start_utc");
        review.setPublicityStartUtc(pubStart == null ? null : pubStart.toLocalDate());
        Date pubEnd = rs.getDate("publicity_end_utc");
        review.setPublicityEndUtc(pubEnd == null ? null : pubEnd.toLocalDate());
        review.setConclusion(rs.getString("conclusion"));
        Timestamp issued = rs.getTimestamp("issued_at");
        review.setIssuedAt(issued == null ? null : issued.toInstant());
        review.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        review.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return review;
    };

    private static final RowMapper<InspectionRecord> INSPECTION_MAPPER = (rs, rowNum) -> {
        InspectionRecord record = new InspectionRecord();
        record.setId(rs.getLong("id"));
        record.setReviewId(rs.getLong("review_id"));
        record.setItemCode(rs.getString("item_code"));
        record.setItemName(rs.getString("item_name"));
        record.setCritical(rs.getBoolean("critical"));
        record.setMeasuredValue(rs.getString("measured_value"));
        record.setLimitValue(rs.getString("limit_value"));
        record.setComparison(rs.getString("comparison"));
        record.setLimitMax(rs.getString("limit_max"));
        record.setQualified(rs.getBoolean("qualified"));
        return record;
    };

    private static final RowMapper<PublicityRecord> PUBLICITY_MAPPER = (rs, rowNum) -> {
        PublicityRecord record = new PublicityRecord();
        record.setId(rs.getLong("id"));
        record.setReviewId(rs.getLong("review_id"));
        record.setStartUtcDate(rs.getDate("start_utc_date").toLocalDate());
        record.setEndUtcDate(rs.getDate("end_utc_date").toLocalDate());
        record.setStatus(PublicityStatus.valueOf(rs.getString("status")));
        Timestamp objection = rs.getTimestamp("objection_at");
        record.setObjectionAt(objection == null ? null : objection.toInstant());
        Timestamp effective = rs.getTimestamp("effective_at");
        record.setEffectiveAt(effective == null ? null : effective.toInstant());
        return record;
    };

    /**
     * 插入新审查流程。批号唯一冲突时返回 null（并发申报唯一受理落败方）。
     */
    public Long insert(Review review) {
        String sql = """
                INSERT INTO batch_review
                    (enterprise_name, product_name, batch_no, status, documents_json, missing_json,
                     declared_at, correction_notice_date, correction_deadline, corrected_at,
                     publicity_start_utc, publicity_end_utc, conclusion, issued_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, review.getEnterpriseName());
                ps.setString(2, review.getProductName());
                ps.setString(3, review.getBatchNo());
                ps.setString(4, review.getStatus().name());
                ps.setString(5, review.getDocumentsJson());
                ps.setString(6, review.getMissingJson());
                ps.setTimestamp(7, Timestamp.from(review.getDeclaredAt()));
                ps.setDate(8, toDate(review.getCorrectionNoticeDate()));
                ps.setDate(9, toDate(review.getCorrectionDeadline()));
                ps.setTimestamp(10, toTimestamp(review.getCorrectedAt()));
                ps.setDate(11, toDate(review.getPublicityStartUtc()));
                ps.setDate(12, toDate(review.getPublicityEndUtc()));
                ps.setString(13, review.getConclusion());
                ps.setTimestamp(14, toTimestamp(review.getIssuedAt()));
                ps.setTimestamp(15, Timestamp.from(review.getCreatedAt()));
                ps.setTimestamp(16, Timestamp.from(review.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按批号查询（不加锁）。 */
    public Review findByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM batch_review WHERE batch_no = ?",
                REVIEW_MAPPER, batchNo).stream().findFirst().orElse(null);
    }

    /** 按主键查询（不加锁）。 */
    public Review findById(Long id) {
        return jdbc.query("SELECT * FROM batch_review WHERE id = ?",
                REVIEW_MAPPER, id).stream().findFirst().orElse(null);
    }

    /** 按批号加行锁查询，不存在返回 null。须在事务内调用。 */
    public Review lockByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM batch_review WHERE batch_no = ? FOR UPDATE",
                REVIEW_MAPPER, batchNo).stream().findFirst().orElse(null);
    }

    /** 按主键加行锁查询，不存在返回 null。须在事务内调用。 */
    public Review lockById(Long id) {
        return jdbc.query("SELECT * FROM batch_review WHERE id = ? FOR UPDATE",
                REVIEW_MAPPER, id).stream().findFirst().orElse(null);
    }

    /** 通用字段更新：状态、资料、缺项、补正、公示、结论、签发等列。 */
    public void update(Review review) {
        jdbc.update("""
                        UPDATE batch_review
                        SET status = ?, documents_json = ?, missing_json = ?,
                            correction_notice_date = ?, correction_deadline = ?, corrected_at = ?,
                            publicity_start_utc = ?, publicity_end_utc = ?, conclusion = ?,
                            issued_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                review.getStatus().name(),
                review.getDocumentsJson(),
                review.getMissingJson(),
                toDate(review.getCorrectionNoticeDate()),
                toDate(review.getCorrectionDeadline()),
                toTimestamp(review.getCorrectedAt()),
                toDate(review.getPublicityStartUtc()),
                toDate(review.getPublicityEndUtc()),
                review.getConclusion(),
                toTimestamp(review.getIssuedAt()),
                Timestamp.from(review.getUpdatedAt()),
                review.getId());
    }

    /** 批量写入检验项目明细。 */
    public void insertInspections(Long reviewId, List<InspectionRecord> records) {
        jdbc.batchUpdate("""
                        INSERT INTO inspection_record
                            (review_id, item_code, item_name, critical, measured_value,
                             limit_value, comparison, limit_max, qualified)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                records, records.size(), (ps, record) -> {
                    ps.setLong(1, reviewId);
                    ps.setString(2, record.getItemCode());
                    ps.setString(3, record.getItemName());
                    ps.setBoolean(4, record.isCritical());
                    ps.setString(5, record.getMeasuredValue());
                    ps.setString(6, record.getLimitValue());
                    ps.setString(7, record.getComparison());
                    ps.setString(8, record.getLimitMax());
                    ps.setBoolean(9, record.isQualified());
                });
    }

    /** 读取检验链（按主键排序，可复算）。 */
    public List<InspectionRecord> listInspections(Long reviewId) {
        return jdbc.query("SELECT * FROM inspection_record WHERE review_id = ? ORDER BY id",
                INSPECTION_MAPPER, reviewId);
    }

    /** 插入公示记录。 */
    public void insertPublicity(PublicityRecord record) {
        jdbc.update("""
                        INSERT INTO publicity_record
                            (review_id, start_utc_date, end_utc_date, status, objection_at, effective_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                record.getReviewId(),
                Date.valueOf(record.getStartUtcDate()),
                Date.valueOf(record.getEndUtcDate()),
                record.getStatus().name(),
                toTimestamp(record.getObjectionAt()),
                toTimestamp(record.getEffectiveAt()));
    }

    /** 查询公示记录，不存在返回 null。 */
    public PublicityRecord findPublicity(Long reviewId) {
        return jdbc.query("SELECT * FROM publicity_record WHERE review_id = ?",
                PUBLICITY_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 加行锁查询公示记录，不存在返回 null。须在事务内调用。 */
    public PublicityRecord lockPublicity(Long reviewId) {
        return jdbc.query("SELECT * FROM publicity_record WHERE review_id = ? FOR UPDATE",
                PUBLICITY_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 更新公示记录状态与异议/生效时刻。 */
    public void updatePublicity(PublicityRecord record) {
        jdbc.update("""
                        UPDATE publicity_record
                        SET status = ?, objection_at = ?, effective_at = ?
                        WHERE id = ?
                        """,
                record.getStatus().name(),
                toTimestamp(record.getObjectionAt()),
                toTimestamp(record.getEffectiveAt()),
                record.getId());
    }

    /** 登记审查事件。 */
    public void insertEvent(ReviewEvent event) {
        Instant now = event.getEventAt() != null ? event.getEventAt() : Instant.now();
        jdbc.update("""
                        INSERT INTO review_event (review_id, event_type, payload, event_at, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                event.getReviewId(),
                event.getEventType(),
                event.getPayload(),
                Timestamp.from(now),
                Timestamp.from(Instant.now()));
    }

    /** 读取事件流水（按发生顺序，可复算）。 */
    public List<ReviewEvent> listEvents(Long reviewId) {
        return jdbc.query("SELECT * FROM review_event WHERE review_id = ? ORDER BY id",
                (rs, rowNum) -> {
                    ReviewEvent event = new ReviewEvent();
                    event.setId(rs.getLong("id"));
                    event.setReviewId(rs.getLong("review_id"));
                    event.setEventType(rs.getString("event_type"));
                    event.setPayload(rs.getString("payload"));
                    event.setEventAt(rs.getTimestamp("event_at").toInstant());
                    event.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    return event;
                }, reviewId);
    }

    private static Date toDate(java.time.LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
