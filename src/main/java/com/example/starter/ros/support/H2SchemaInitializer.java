package com.example.starter.ros.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 仅对 H2 内存库执行兼容建表脚本；MySQL 历史入口由外部库自行维护表结构，
 * 因此这里按连接 URL 判定，避免把 H2 方言脚本跑到 MySQL 上。
 */
@Component
@Order(0)
public class H2SchemaInitializer implements ApplicationRunner {

    private final DataSource dataSource;

    public H2SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        Resource script = new ClassPathResource("schema-h2.sql");
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:")) {
                return;
            }
            ScriptUtils.executeSqlScript(connection, script);
        }
    }
}
