package com.example.starter.payment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个用例前清空业务表并重新播种合成商户/费率，保证场景间无顺序依赖。
 * 使用真实嵌入式 H2（MODE=MySQL）与真实事务，不 mock 数据库边界。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanAndSeed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("TRUNCATE TABLE recon_diff_log");
        jdbc.update("TRUNCATE TABLE recon_diff");
        jdbc.update("TRUNCATE TABLE recon_run");
        jdbc.update("TRUNCATE TABLE settlement_order");
        jdbc.update("TRUNCATE TABLE adjustment_order");
        jdbc.update("TRUNCATE TABLE payment_transaction");
        jdbc.update("TRUNCATE TABLE payment_batch");
        jdbc.update("TRUNCATE TABLE merchant_fee_rate");
        jdbc.update("TRUNCATE TABLE merchant");
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        jdbc.update("INSERT INTO merchant (merchant_no, merchant_name, status) VALUES (?,?,?)",
                "M001", "合成示例商户一号", "ACTIVE");
        jdbc.update("INSERT INTO merchant (merchant_no, merchant_name, status) VALUES (?,?,?)",
                "M002", "合成示例商户二号", "ACTIVE");
        jdbc.update("INSERT INTO merchant_fee_rate (merchant_no, fee_rate) VALUES (?,?)",
                "M001", new java.math.BigDecimal("0.0060"));
        jdbc.update("INSERT INTO merchant_fee_rate (merchant_no, fee_rate) VALUES (?,?)",
                "M002", new java.math.BigDecimal("0.0100"));
    }
}
