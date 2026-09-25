package com.example.starter.payment.repo;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 商户费率与商户校验仓储（合成数据）。
 */
@Repository
public class MerchantRepository {

    private final JdbcTemplate jdbc;

    public MerchantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsActive(String merchantNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM merchant WHERE merchant_no = ? AND status = 'ACTIVE'",
                Integer.class, merchantNo);
        return count != null && count > 0;
    }

    public Optional<BigDecimal> findFeeRate(String merchantNo) {
        return jdbc.query("SELECT fee_rate FROM merchant_fee_rate WHERE merchant_no = ?",
                (rs, n) -> rs.getBigDecimal("fee_rate"), merchantNo).stream().findFirst();
    }
}
