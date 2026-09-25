package com.example.starter.food.repo;

import com.example.starter.food.domain.RecipeStatus;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * recipe 表行对象。
 */
public class RecipeRow {

    public Long id;
    public String requestId;
    public String productCode;
    public String recipeVersion;
    public String categoryCode;
    public RecipeStatus status;
    public String fingerprint;
    public String reviewDetail;
    public String reviewedStd;
    public String snapshotJson;
    public String approvedStd;
    public Instant createdAt;
    public Instant reviewedAt;
    public Instant approvedAt;

    public static RecipeRow from(java.sql.ResultSet rs) throws java.sql.SQLException {
        RecipeRow r = new RecipeRow();
        r.id = rs.getLong("id");
        r.requestId = rs.getString("request_id");
        r.productCode = rs.getString("product_code");
        r.recipeVersion = rs.getString("recipe_version");
        r.categoryCode = rs.getString("category_code");
        r.status = RecipeStatus.valueOf(rs.getString("status"));
        r.fingerprint = rs.getString("fingerprint");
        r.reviewDetail = rs.getString("review_detail");
        r.reviewedStd = rs.getString("reviewed_std");
        r.snapshotJson = rs.getString("snapshot_json");
        r.approvedStd = rs.getString("approved_std");
        Timestamp ts = rs.getTimestamp("created_at");
        r.createdAt = ts == null ? null : ts.toInstant();
        ts = rs.getTimestamp("reviewed_at");
        r.reviewedAt = ts == null ? null : ts.toInstant();
        ts = rs.getTimestamp("approved_at");
        r.approvedAt = ts == null ? null : ts.toInstant();
        return r;
    }
}
