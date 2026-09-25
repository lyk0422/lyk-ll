package com.example.starter.repository;

import com.example.starter.domain.BloodIssue;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 发血记录仓储，barcode 唯一约束保证并发只放行成功一次。
 */
@Repository
public class BloodIssueRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BloodIssueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BloodIssue> MAPPER = (rs, n) -> {
        BloodIssue i = new BloodIssue();
        i.setId(rs.getLong("id"));
        i.setBarcode(rs.getString("barcode"));
        i.setRequestId(rs.getString("request_id"));
        i.setIssuedAt(rs.getTimestamp("issued_at").toInstant());
        i.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
        i.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return i;
    };

    /**
     * 插入发血记录；条码已发血时由数据库唯一约束拒绝（DuplicateKeyException）。
     */
    public void insert(BloodIssue issue) {
        String sql = "INSERT INTO blood_issue (barcode, request_id, issued_at, expires_at, created_at) "
                + "VALUES (:barcode, :requestId, :issuedAt, :expiresAt, :createdAt)";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("barcode", issue.getBarcode())
                .addValue("requestId", issue.getRequestId())
                .addValue("issuedAt", BloodUnitRepository.ts(issue.getIssuedAt()))
                .addValue("expiresAt", BloodUnitRepository.ts(issue.getExpiresAt()))
                .addValue("createdAt", BloodUnitRepository.ts(issue.getCreatedAt()));
        jdbc.update(sql, p);
    }

    public Optional<BloodIssue> findByBarcode(String barcode) {
        String sql = "SELECT id, barcode, request_id, issued_at, expires_at, created_at "
                + "FROM blood_issue WHERE barcode = :barcode";
        List<BloodIssue> list = jdbc.query(sql,
                new MapSqlParameterSource("barcode", barcode), MAPPER);
        return list.stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM blood_issue",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }
}
