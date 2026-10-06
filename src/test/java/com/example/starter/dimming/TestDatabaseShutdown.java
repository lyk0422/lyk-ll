package com.example.starter.dimming;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 测试上下文关闭后释放命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）。
 */
@TestConfiguration
public class TestDatabaseShutdown implements ApplicationListener<ContextClosedEvent> {

    private static final String TEST_DB_URL =
            "jdbc:h2:mem:tunnel_dimming_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try (Connection con = DriverManager.getConnection(TEST_DB_URL, "sa", "");
                Statement st = con.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // 上下文已关闭，释放失败不影响测试结果
        }
    }
}
