package com.example.starter.lc.repo;

import com.example.starter.lc.LcBusinessException;
import com.example.starter.lc.LcErrorCode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 信用证表数据访问（H2 MODE=MySQL，参数化 SQL）。
 */
@Repository
public class LcRepository {

    private static final LcRowMapper ROW_MAPPER = new LcRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public LcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入信用证，编号冲突时由上层转为 409。 */
    public void insert(LcCreditRow row) {
        jdbcTemplate.update("""
                INSERT INTO lc_credit
                    (lc_no, applicant, beneficiary, currency, total_amount, available_amount,
                     expiry_date, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.lcNo(), row.applicant(), row.beneficiary(), row.currency(),
                row.totalAmount(), row.availableAmount(), row.expiryDate(), row.status(),
                Timestamp.from(row.createdAt()));
    }

    /** 按编号查询信用证，不存在返回 null。 */
    public LcCreditRow find(String lcNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM lc_credit WHERE lc_no = ?", ROW_MAPPER, lcNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 行锁查询信用证，不存在返回 404。调用方须处于事务中。 */
    public LcCreditRow findForUpdate(String lcNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM lc_credit WHERE lc_no = ? FOR UPDATE", ROW_MAPPER, lcNo);
        } catch (EmptyResultDataAccessException e) {
            throw new LcBusinessException(LcErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                    "信用证不存在: " + lcNo);
        }
    }

    /**
     * 原子核减可用额度：仅当可用额度足额时扣减。
     *
     * @return 受影响行数；0 表示额度不足，调用方整笔拒绝
     */
    public int deductAvailableAmount(String lcNo, BigDecimal amount) {
        return jdbcTemplate.update("""
                UPDATE lc_credit
                   SET available_amount = available_amount - ?
                 WHERE lc_no = ?
                   AND available_amount >= ?
                """, amount, lcNo, amount);
    }

    private static final class LcRowMapper implements RowMapper<LcCreditRow> {

        @Override
        public LcCreditRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LcCreditRow(
                    rs.getString("lc_no"),
                    rs.getString("applicant"),
                    rs.getString("beneficiary"),
                    rs.getString("currency"),
                    rs.getBigDecimal("total_amount"),
                    rs.getBigDecimal("available_amount"),
                    rs.getDate("expiry_date").toLocalDate(),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
