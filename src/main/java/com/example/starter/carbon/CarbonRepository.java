package com.example.starter.carbon;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.carbon.model.AccountRow;
import com.example.starter.carbon.model.BatchRow;
import com.example.starter.carbon.model.ItemRow;
import com.example.starter.carbon.model.RequestRow;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 碳市场履约数据访问层：JdbcTemplate 参数化 SQL。
 * 扣减与账户读取使用行级锁（FOR UPDATE），配合事务保证组合扣减原子性与并发串行生效。
 */
@Repository
public class CarbonRepository {

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, rowNum) -> mapBatch(rs);
    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, rowNum) -> new ItemRow(
            rs.getString("request_id"),
            rs.getString("source_type"),
            rs.getLong("batch_id"),
            rs.getLong("quantity"));

    private final JdbcTemplate jdbc;

    public CarbonRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertEnterprise(String name, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO enterprise (name, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setTimestamp(2, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public boolean enterpriseExists(long enterpriseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enterprise WHERE id = ?", Integer.class, enterpriseId);
        return count != null && count > 0;
    }

    public long insertAllowanceBatch(long enterpriseId, int year, long quantity,
            LocalDate expiryDate, Instant now) {
        return insertBatch("allowance_batch", enterpriseId, year, quantity, expiryDate, now);
    }

    public long insertOffsetBatch(long enterpriseId, int year, long quantity,
            LocalDate expiryDate, Instant now) {
        return insertBatch("offset_credit_batch", enterpriseId, year, quantity, expiryDate, now);
    }

    private long insertBatch(String table, long enterpriseId, int year, long quantity,
            LocalDate expiryDate, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table + " (enterprise_id, compliance_year, quantity_total,"
                            + " quantity_remaining, expiry_date, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, enterpriseId);
            ps.setInt(2, year);
            ps.setLong(3, quantity);
            ps.setLong(4, quantity);
            ps.setDate(5, Date.valueOf(expiryDate));
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void upsertAccount(long enterpriseId, int year, long emissions, Instant deadlineAt) {
        jdbc.update(
                "MERGE INTO compliance_account (enterprise_id, compliance_year, emissions,"
                        + " surrendered_allowance, surrendered_offset, status, deadline_at)"
                        + " KEY (enterprise_id, compliance_year) VALUES (?, ?, ?, 0, 0, 'PENDING', ?)",
                enterpriseId, year, emissions, Timestamp.from(deadlineAt));
    }

    public Optional<AccountRow> findAccount(long enterpriseId, int year) {
        return queryAccount(
                "SELECT * FROM compliance_account WHERE enterprise_id = ? AND compliance_year = ?",
                enterpriseId, year);
    }

    public Optional<AccountRow> findAccountForUpdate(long enterpriseId, int year) {
        return queryAccount(
                "SELECT * FROM compliance_account WHERE enterprise_id = ? AND compliance_year = ? FOR UPDATE",
                enterpriseId, year);
    }

    private Optional<AccountRow> queryAccount(String sql, long enterpriseId, int year) {
        List<AccountRow> rows = jdbc.query(sql, (rs, rowNum) -> new AccountRow(
                rs.getLong("enterprise_id"),
                rs.getInt("compliance_year"),
                rs.getLong("emissions"),
                rs.getLong("surrendered_allowance"),
                rs.getLong("surrendered_offset"),
                rs.getString("status"),
                rs.getTimestamp("deadline_at").toInstant()), enterpriseId, year);
        return rows.stream().findFirst();
    }

    public void updateAccountAfterSurrender(long enterpriseId, int year,
            long surrenderedAllowance, long surrenderedOffset, String status) {
        jdbc.update(
                "UPDATE compliance_account SET surrendered_allowance = ?, surrendered_offset = ?,"
                        + " status = ? WHERE enterprise_id = ? AND compliance_year = ?",
                surrenderedAllowance, surrenderedOffset, status, enterpriseId, year);
    }

    public int markNonCompliant(long enterpriseId, int year) {
        return jdbc.update(
                "UPDATE compliance_account SET status = 'NON_COMPLIANT'"
                        + " WHERE enterprise_id = ? AND compliance_year = ? AND status = 'PENDING'",
                enterpriseId, year);
    }

    public Optional<RequestRow> findRequest(String requestId) {
        List<RequestRow> rows = jdbc.query(
                "SELECT * FROM surrender_request WHERE request_id = ?",
                (rs, rowNum) -> mapRequest(rs), requestId);
        return rows.stream().findFirst();
    }

    public void insertRequest(RequestRow row) {
        jdbc.update(
                "INSERT INTO surrender_request (request_id, fingerprint, enterprise_id, compliance_year,"
                        + " allowance_amount, offset_amount, certificate_no, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.fingerprint(), row.enterpriseId(), row.complianceYear(),
                row.allowanceAmount(), row.offsetAmount(), row.certificateNo(),
                Timestamp.from(row.createdAt()));
    }

    public void insertItem(ItemRow item) {
        jdbc.update(
                "INSERT INTO surrender_item (request_id, source_type, batch_id, quantity) VALUES (?, ?, ?, ?)",
                item.requestId(), item.sourceType(), item.batchId(), item.quantity());
    }

    public List<ItemRow> findItems(String requestId) {
        return jdbc.query(
                "SELECT * FROM surrender_item WHERE request_id = ? ORDER BY id",
                ITEM_MAPPER, requestId);
    }

    /**
     * 按“到期日近者优先，到期日相同按批次主键（签发先后）”顺序锁定并返回有余量的配额批次。
     */
    public List<BatchRow> findAllowanceBatchesForUpdate(long enterpriseId, int year) {
        return jdbc.query(
                "SELECT * FROM allowance_batch WHERE enterprise_id = ? AND compliance_year = ?"
                        + " AND quantity_remaining > 0 ORDER BY expiry_date, id FOR UPDATE",
                BATCH_MAPPER, enterpriseId, year);
    }

    public List<BatchRow> findOffsetBatchesForUpdate(long enterpriseId, int year) {
        return jdbc.query(
                "SELECT * FROM offset_credit_batch WHERE enterprise_id = ? AND compliance_year = ?"
                        + " AND quantity_remaining > 0 ORDER BY expiry_date, id FOR UPDATE",
                BATCH_MAPPER, enterpriseId, year);
    }

    /**
     * 条件扣减：仅当剩余量充足时生效，返回受影响行数（0 表示并发下余额不足）。
     */
    public int deductAllowance(long batchId, long quantity) {
        return jdbc.update(
                "UPDATE allowance_batch SET quantity_remaining = quantity_remaining - ?,"
                        + " status = CASE WHEN quantity_remaining - ? = 0 THEN 'EXHAUSTED' ELSE status END"
                        + " WHERE id = ? AND quantity_remaining >= ?",
                quantity, quantity, batchId, quantity);
    }

    public int deductOffset(long batchId, long quantity) {
        return jdbc.update(
                "UPDATE offset_credit_batch SET quantity_remaining = quantity_remaining - ?"
                        + " WHERE id = ? AND quantity_remaining >= ?",
                quantity, batchId, quantity);
    }

    private static BatchRow mapBatch(ResultSet rs) throws SQLException {
        return new BatchRow(
                rs.getLong("id"),
                rs.getLong("enterprise_id"),
                rs.getInt("compliance_year"),
                rs.getLong("quantity_total"),
                rs.getLong("quantity_remaining"),
                rs.getDate("expiry_date").toLocalDate());
    }

    private static RequestRow mapRequest(ResultSet rs) throws SQLException {
        return new RequestRow(
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("enterprise_id"),
                rs.getInt("compliance_year"),
                rs.getLong("allowance_amount"),
                rs.getLong("offset_amount"),
                rs.getString("certificate_no"),
                rs.getTimestamp("created_at").toInstant());
    }
}
