package com.example.starter.repository;

import com.example.starter.entity.Merchant;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 商户表数据访问。 */
@Repository
public class MerchantRepository {

    private static final RowMapper<Merchant> MAPPER = new MerchantRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public MerchantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Merchant findByNo(String merchantNo) {
        List<Merchant> list = jdbcTemplate.query(
                "SELECT merchant_no, merchant_name, fee_rate, status, created_at FROM merchant WHERE merchant_no = ?",
                MAPPER, merchantNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insertIfAbsent(String merchantNo, String merchantName, BigDecimal feeRate, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO merchant (merchant_no, merchant_name, fee_rate, status, created_at) "
                        + "SELECT ?, ?, ?, 'ACTIVE', ? WHERE NOT EXISTS (SELECT 1 FROM merchant WHERE merchant_no = ?)",
                merchantNo, merchantName, feeRate, java.sql.Timestamp.from(now), merchantNo);
    }

    private static class MerchantRowMapper implements RowMapper<Merchant> {
        @Override
        public Merchant mapRow(ResultSet rs, int rowNum) throws SQLException {
            Merchant m = new Merchant();
            m.setMerchantNo(rs.getString("merchant_no"));
            m.setMerchantName(rs.getString("merchant_name"));
            m.setFeeRate(rs.getBigDecimal("fee_rate"));
            m.setStatus(rs.getString("status"));
            m.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            return m;
        }
    }
}
