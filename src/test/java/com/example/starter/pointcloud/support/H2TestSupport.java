package com.example.starter.pointcloud.support;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 测试支持：每个实例使用独立的命名内存库（MODE=MySQL），并在关闭时显式释放。
 */
public final class H2TestSupport {

    private static final AtomicLong SEQ = new AtomicLong();

    private final JdbcDataSource dataSource;

    private H2TestSupport(JdbcDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 创建独立内存库并执行建表脚本。
     */
    public static H2TestSupport start() {
        long id = SEQ.incrementAndGet();
        JdbcDataSource ds = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1：populator 连接关闭后内存库仍保留，供后续连接复用
        ds.setUrl("jdbc:h2:mem:test_pc_" + id
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new H2TestSupport(ds);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * 关闭并释放内存库。
     */
    public void shutdown() {
        try {
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            }
        } catch (Exception ignored) {
            // 测试结束清理失败不影响结果
        }
    }
}
