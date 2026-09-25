package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.Decision;
import com.example.starter.marpol.domain.InspectionState;
import com.example.starter.marpol.domain.ItemStatus;
import com.example.starter.marpol.model.Inspection;
import com.example.starter.marpol.model.InspectionItem;
import com.example.starter.marpol.model.InspectionReview;
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

/**
 * 港口国核查仓储：核查单、逐条结论（钉住版本）、复查证据。
 */
@Repository
public class InspectionRepository {

    private static final RowMapper<Inspection> INSPECTION_MAPPER = (rs, n) -> {
        Timestamp issued = rs.getTimestamp("issued_at");
        return new Inspection(
                rs.getLong("id"),
                rs.getString("inspection_no"),
                rs.getLong("ship_id"),
                rs.getString("officer"),
                InspectionState.valueOf(rs.getString("status")),
                rs.getDate("inspection_date").toLocalDate(),
                issued == null ? null : issued.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    };

    private static final RowMapper<InspectionItem> ITEM_MAPPER = (rs, n) -> {
        Date due = rs.getDate("due_date");
        Timestamp rectified = rs.getTimestamp("rectified_at");
        String status = rs.getString("status");
        String reason = rs.getString("reason");
        return new InspectionItem(
                rs.getLong("id"),
                rs.getLong("inspection_id"),
                rs.getLong("root_record_id"),
                rs.getLong("pinned_record_id"),
                rs.getInt("pinned_version_no"),
                Decision.valueOf(rs.getString("decision")),
                reason,
                status == null ? null : ItemStatus.valueOf(status),
                due == null ? null : due.toLocalDate(),
                rectified == null ? null : rectified.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };

    private static final RowMapper<InspectionReview> REVIEW_MAPPER = (rs, n) -> new InspectionReview(
            rs.getLong("id"),
            rs.getLong("inspection_id"),
            rs.getString("inspection_no"),
            (Long) rs.getObject("item_id"),
            rs.getString("evidence"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public InspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertInspection(Long shipId, String officer, LocalDate inspectionDate, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection (inspection_no, ship_id, officer, status, inspection_date, issued_at, created_at) "
                            + "VALUES (?, ?, ?, 'OPEN', ?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "TMP-" + java.util.UUID.randomUUID());
            ps.setLong(2, shipId);
            ps.setString(3, officer);
            ps.setDate(4, Date.valueOf(inspectionDate));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    public void updateInspectionNo(Long id, String no) {
        jdbc.update("UPDATE inspection SET inspection_no = ? WHERE id = ?", no, id);
    }

    public Inspection findByNo(String inspectionNo) {
        List<Inspection> list = jdbc.query(inspectionSelect("inspection_no = ?"), INSPECTION_MAPPER, inspectionNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行级锁定核查单（须在事务内）。 */
    public Inspection findByNoForUpdate(String inspectionNo) {
        List<Inspection> list = jdbc.query(
                inspectionSelect("inspection_no = ? FOR UPDATE"), INSPECTION_MAPPER, inspectionNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 签发：仅 OPEN -> ISSUED；重复签发影响行数为 0，由上层按幂等成功处理。
     */
    public int markIssued(Long id, Instant issuedAt) {
        return jdbc.update(
                "UPDATE inspection SET status = 'ISSUED', issued_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(issuedAt), id);
    }

    public Long insertItem(InspectionItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection_item (inspection_id, root_record_id, pinned_record_id, "
                            + "pinned_version_no, decision, reason, status, due_date, rectified_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, item.inspectionId());
            ps.setLong(2, item.rootRecordId());
            ps.setLong(3, item.pinnedRecordId());
            ps.setInt(4, item.pinnedVersionNo());
            ps.setString(5, item.decision().name());
            ps.setString(6, item.reason());
            ps.setString(7, item.status() == null ? null : item.status().name());
            ps.setDate(8, item.dueDate() == null ? null : Date.valueOf(item.dueDate()));
            ps.setTimestamp(9, Timestamp.from(item.createdAt()));
            ps.setTimestamp(10, Timestamp.from(item.updatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    public void updateItemDecision(InspectionItem item) {
        jdbc.update(
                "UPDATE inspection_item SET pinned_record_id = ?, pinned_version_no = ?, decision = ?, "
                        + "reason = ?, status = ?, due_date = ?, updated_at = ? WHERE id = ?",
                item.pinnedRecordId(), item.pinnedVersionNo(), item.decision().name(),
                item.reason(), item.status() == null ? null : item.status().name(),
                item.dueDate() == null ? null : Date.valueOf(item.dueDate()),
                Timestamp.from(item.updatedAt()), item.id());
    }

    public InspectionItem findItemForUpdate(Long inspectionId, Long rootRecordId) {
        List<InspectionItem> list = jdbc.query(itemSelect(
                "inspection_id = ? AND root_record_id = ? FOR UPDATE"), ITEM_MAPPER, inspectionId, rootRecordId);
        return list.isEmpty() ? null : list.get(0);
    }

    public InspectionItem findItemById(Long itemId) {
        List<InspectionItem> list = jdbc.query(itemSelect("id = ?"), ITEM_MAPPER, itemId);
        return list.isEmpty() ? null : list.get(0);
    }

    public InspectionItem findItemByIdForUpdate(Long itemId) {
        List<InspectionItem> list = jdbc.query(itemSelect("id = ? FOR UPDATE"), ITEM_MAPPER, itemId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<InspectionItem> findItems(Long inspectionId) {
        return jdbc.query(itemSelect("inspection_id = ? ORDER BY id"), ITEM_MAPPER, inspectionId);
    }

    /**
     * 整改关闭：仅仍 OPEN 的缺陷项可更新。
     */
    public int markRectified(Long itemId, Instant at) {
        return jdbc.update(
                "UPDATE inspection_item SET status = 'RECTIFIED', rectified_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(at), Timestamp.from(at), itemId);
    }

    public int markOverdue(Long itemId, Instant at) {
        return jdbc.update(
                "UPDATE inspection_item SET status = 'OVERDUE', updated_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(at), itemId);
    }

    public Long insertReview(Long inspectionId, String inspectionNo, Long itemId, String evidence, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection_review (inspection_id, inspection_no, item_id, evidence, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, inspectionId);
            ps.setString(2, inspectionNo);
            if (itemId == null) {
                ps.setObject(3, null);
            } else {
                ps.setLong(3, itemId);
            }
            ps.setString(4, evidence);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    public List<InspectionReview> findReviews(Long inspectionId) {
        return jdbc.query(
                "SELECT id, inspection_id, inspection_no, item_id, evidence, created_at "
                        + "FROM inspection_review WHERE inspection_id = ? ORDER BY id",
                REVIEW_MAPPER, inspectionId);
    }

    private String inspectionSelect(String where) {
        return "SELECT id, inspection_no, ship_id, officer, status, inspection_date, issued_at, created_at "
                + "FROM inspection WHERE " + where;
    }

    private String itemSelect(String where) {
        return "SELECT id, inspection_id, root_record_id, pinned_record_id, pinned_version_no, decision, "
                + "reason, status, due_date, rectified_at, created_at, updated_at "
                + "FROM inspection_item WHERE " + where;
    }
}
