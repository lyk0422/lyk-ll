package com.example.starter.lidar;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.Compression;
import com.example.starter.lidar.model.PointDatum;
import com.example.starter.lidar.model.QueryType;
import com.example.starter.lidar.model.SpatialQueryRequest;
import com.example.starter.lidar.model.SpatialQueryResponse;
import com.example.starter.lidar.model.WriteChunkRequest;
import com.example.starter.lidar.service.PointCloudService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 application.yaml 默认数据源（H2 MODE=MySQL 内存库）的启动冒烟：
 * 不提供任何 DB_* 环境变量即可完成建表、写入与查询，验证本地入口不依赖外部数据库。
 * 仅把块存储目录覆盖到临时目录，避免污染系统临时目录。
 */
@SpringBootTest
class ApplicationDefaultsSmokeTest {

    static Path storageRoot;

    static {
        try {
            storageRoot = Files.createTempDirectory("lidar-default-smoke-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("lidar.storage.root-dir", () -> storageRoot.toString());
    }

    @Autowired
    private PointCloudService service;

    @Test
    void defaultH2ContextServesWriteAndQuery() {
        service.write(new WriteChunkRequest("default-smoke", 1, "boot", Compression.NONE,
                List.of(new PointDatum(1, 2, 3, 10L))));

        SpatialQueryResponse resp = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(0, 0, 0, 1, 2, 3),
                null, null, null, null, null, null));
        assertEquals(1, resp.points().size());
        assertEquals("default-smoke", resp.points().get(0).chunkId());
        assertEquals(1, resp.points().get(0).version());
        assertEquals("boot", resp.points().get(0).source());
        assertTrue(Files.isDirectory(storageRoot.resolve("chunks")));
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (!Files.exists(storageRoot)) {
            return;
        }
        try (var walk = Files.walk(storageRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows 文件句柄延迟释放时不影响测试结论
                }
            });
        }
    }
}
