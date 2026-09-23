package com.example.starter.lidar.config;

import com.example.starter.lidar.audit.AuditRepository;
import com.example.starter.lidar.index.ChunkIndexRepository;
import com.example.starter.lidar.store.ChunkFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;

/**
 * 点云服务 Bean 配置：本地文件存储、H2 建表与可控时钟。
 */
@Configuration
@EnableConfigurationProperties(LidarStorageProperties.class)
public class LidarConfig {

    private static final Logger log = LoggerFactory.getLogger(LidarConfig.class);

    /** 块文件存储：启动时确保 chunks/tmp/quarantine 目录存在。 */
    @Bean
    public ChunkFileStore chunkFileStore(LidarStorageProperties props) throws IOException {
        ChunkFileStore store = new ChunkFileStore(props.rootDir());
        store.init();
        log.info("点云块存储根目录: {}", store.getRootDir());
        return store;
    }

    /** 建表（幂等），H2 MODE=MySQL 下随内存库自动执行。 */
    @Bean
    public Boolean lidarSchemaInitializer(ChunkIndexRepository indexRepository,
                                          AuditRepository auditRepository) {
        indexRepository.initSchema();
        auditRepository.initSchema();
        return Boolean.TRUE;
    }

    /** 系统 UTC 时钟；测试可替换为固定时钟。 */
    @Bean
    public Clock lidarClock() {
        return Clock.systemUTC();
    }
}
