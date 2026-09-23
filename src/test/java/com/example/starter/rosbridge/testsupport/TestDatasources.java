package com.example.starter.rosbridge.testsupport;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 构造隔离的 H2 内存库（MySQL 兼容模式）并执行 schema.sql 的测试工具。
 */
public final class TestDatasources {

    private TestDatasources() {
    }

    public static DataSource newH2(String dbName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("schema.sql"))
                .execute(ds);
        return ds;
    }

    public static JdbcTemplate jdbc(DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
