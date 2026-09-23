package com.example.starter.pointcloud.config;

import com.example.starter.pointcloud.service.ChunkFileStore;
import com.example.starter.pointcloud.service.ChunkIndexRepository;
import com.example.starter.pointcloud.service.PointCloudService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 点云服务装配：本地文件存储、H2 索引仓库与业务服务。
 */
@Configuration
@EnableConfigurationProperties(PointCloudProperties.class)
public class PointCloudConfig {

    /**
     * 本地块文件存储，启动时确保目录存在。
     */
    @Bean
    public ChunkFileStore chunkFileStore(PointCloudProperties properties) throws IOException {
        ChunkFileStore store = new ChunkFileStore(Path.of(properties.dataDir()));
        store.init();
        return store;
    }

    /**
     * H2 索引表初始化（幂等建表）。
     */
    @Bean
    public DataSourceInitializer pointCloudSchemaInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")));
        return initializer;
    }

    /**
     * 索引仓库。
     */
    @Bean
    public ChunkIndexRepository chunkIndexRepository(DataSource dataSource) {
        return new ChunkIndexRepository(dataSource);
    }

    /**
     * 点云业务服务。
     */
    @Bean
    public PointCloudService pointCloudService(ChunkFileStore fileStore,
                                               ChunkIndexRepository repository) {
        return new PointCloudService(fileStore, repository);
    }
}
