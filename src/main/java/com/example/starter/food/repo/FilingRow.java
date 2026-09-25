package com.example.starter.food.repo;

import com.example.starter.food.domain.FilingStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * filing 表行对象；时间均为 UTC。
 */
public class FilingRow {

    public Long id;
    public String requestId;
    public String productCode;
    public Long recipeId;
    public String recipeVersion;
    public String standardVersion;
    public FilingStatus status;
    public String snapshotJson;
    public Instant filedAt;
    public LocalDate reviewDueDate;
    public Instant reviewedAt;
    public String reviewedStd;
    public Instant suspendedAt;
    public String suspendReason;
    public Instant cancelledAt;

    /** 由 ResultSet 组装。 */
    public static FilingRow from(java.sql.ResultSet rs) throws java.sql.SQLException {
        FilingRow r = new FilingRow();
        r.id = rs.getLong("id");
        r.requestId = rs.getString("request_id");
        r.productCode = rs.getString("product_code");
        r.recipeId = rs.getLong("recipe_id");
        r.recipeVersion = rs.getString("recipe_version");
        r.standardVersion = rs.getString("standard_version");
        r.status = FilingStatus.valueOf(rs.getString("status"));
        r.snapshotJson = rs.getString("snapshot_json");
        Timestamp ts = rs.getTimestamp("filed_at");
        r.filedAt = ts == null ? null : ts.toInstant();
        java.sql.Date d = rs.getDate("review_due_date");
        r.reviewDueDate = d == null ? null : d.toLocalDate();
        ts = rs.getTimestamp("reviewed_at");
        r.reviewedAt = ts == null ? null : ts.toInstant();
        r.reviewedStd = rs.getString("reviewed_std");
        ts = rs.getTimestamp("suspended_at");
        r.suspendedAt = ts == null ? null : ts.toInstant();
        r.suspendReason = rs.getString("suspend_reason");
        ts = rs.getTimestamp("cancelled_at");
        r.cancelledAt = ts == null ? null : ts.toInstant();
        return r;
    }
}
