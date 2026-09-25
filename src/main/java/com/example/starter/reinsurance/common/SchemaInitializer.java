package com.example.starter.reinsurance.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * 启动时自动建表：本地默认嵌入式 H2（MODE=MySQL）执行 {@code db/schema-h2.sql}；
 * 若数据源为 MySQL 则执行 {@code db/schema-mysql.sql}。脚本均为幂等（IF NOT EXISTS）。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String productName;
        try (java.sql.Connection connection = dataSource.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("无法读取数据库元数据以初始化表结构", e);
        }
        String script = productName != null && productName.toUpperCase().contains("H2")
                ? "db/schema-h2.sql"
                : "db/schema-mysql.sql";
        EncodedResource resource =
                new EncodedResource(new ClassPathResource(script), StandardCharsets.UTF_8);
        try (java.sql.Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, resource, false, false, "--", ";", "/*", "*/");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("初始化建表脚本失败: " + script, e);
        }
    }
}
