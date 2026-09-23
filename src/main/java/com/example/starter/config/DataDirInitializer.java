package com.example.starter.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 启动时确保 H2 文件库所在目录存在，避免全新工作目录下文件库无法创建。
 * 在 {@code StarterApplication} 中手动注册，保证早于数据源初始化执行。
 */
public class DataDirInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DataDirInitializer.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        String dataDir = environment.getProperty("forwarder.data-dir", "./data");
        Path path = Path.of(dataDir);
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            log.warn("创建数据目录失败: {}", path.toAbsolutePath(), ex);
        }
    }
}
