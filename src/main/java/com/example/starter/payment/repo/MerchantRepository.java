package com.example.starter.payment.repo;

import com.example.starter.payment.Merchant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 商户数据访问。
 */
@Repository
public class MerchantRepository {

    private final JdbcTemplate jdbc;

    public MerchantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Merchant> findByMerchantNo(String merchantNo) {
        return jdbc.query("SELECT merchant_no, merchant_name, fee_rate_bp FROM merchant WHERE merchant_no = ?",
                (rs, i) -> new Merchant(rs.getString("merchant_no"), rs.getString("merchant_name"),
                        rs.getInt("fee_rate_bp")),
                merchantNo).stream().findFirst();
    }
}
