package com.example.starter.pointcloud.api;

import com.example.starter.pointcloud.service.ChunkFileStore;
import com.example.starter.pointcloud.service.ChunkIndexRepository;
import com.example.starter.pointcloud.model.ChunkMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 点云接口全栈测试：Spring 上下文、H2 自动建表、临时数据目录、HTTP 状态与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointCloudControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("pointcloud.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChunkIndexRepository repository;

    @Autowired
    private ChunkFileStore fileStore;

    @BeforeEach
    void cleanUp() throws Exception {
        for (ChunkMetadata m : repository.findAll()) {
            repository.delete(m.chunkId());
        }
        for (String sub : new String[]{"chunks", "quarantine", "audit"}) {
            Path dir = tempDir.resolve(sub);
            if (Files.exists(dir)) {
                try (var paths = Files.list(dir)) {
                    for (Path p : paths.toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    private static String chunkJson(String chunkId, long version, String source,
                                    String compression, String pointsJson) {
        return "{\"chunkId\":\"" + chunkId + "\",\"version\":" + version
                + ",\"source\":\"" + source + "\",\"compression\":\"" + compression
                + "\",\"points\":" + pointsJson + "}";
    }

    @Test
    void writeThenIdempotentRetryThenConflict() throws Exception {
        String body = chunkJson("web-1", 3, "lidar-1", "NONE",
                "[{\"x\":1,\"y\":2,\"z\":3,\"timestampNanos\":100}]");

        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkId").value("web-1"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.source").value("lidar-1"))
                .andExpect(jsonPath("$.pointCount").value(1))
                .andExpect(jsonPath("$.compression").value("NONE"))
                .andExpect(jsonPath("$.deduplicated").value(false))
                .andExpect(jsonPath("$.checksum").isNotEmpty());

        // 同 ID 同内容重试：返回原结果
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deduplicated").value(true));

        // 同 ID 改内容：409
        String changed = chunkJson("web-1", 3, "lidar-1", "NONE",
                "[{\"x\":1,\"y\":2,\"z\":4,\"timestampNanos\":100}]");
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidWriteRequestReturnsBadRequest() throws Exception {
        String body = "{\"chunkId\":\"\",\"version\":0,\"source\":\"s\","
                + "\"compression\":\"NONE\",\"points\":[]}";
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boxQueryReturnsSortedHitsWithVersionAndSource() throws Exception {
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON)
                        .content(chunkJson("q-a", 1, "lidar-1", "NONE",
                                "[{\"x\":0,\"y\":0,\"z\":0,\"timestampNanos\":20}]")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON)
                        .content(chunkJson("q-b", 7, "lidar-2", "NONE",
                                "[{\"x\":10,\"y\":10,\"z\":10,\"timestampNanos\":10}]")))
                .andExpect(status().isCreated());

        String box = "{\"minX\":0,\"minY\":0,\"minZ\":0,\"maxX\":10,\"maxY\":10,\"maxZ\":10}";
        mockMvc.perform(post("/api/pointcloud/query/box").contentType(APPLICATION_JSON).content(box))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                // 时间升序：q-b(t=10) 在前，其版本与来源来自实际读取的块
                .andExpect(jsonPath("$.points[0].chunkId").value("q-b"))
                .andExpect(jsonPath("$.points[0].chunkVersion").value(7))
                .andExpect(jsonPath("$.points[0].source").value("lidar-2"))
                .andExpect(jsonPath("$.points[1].chunkId").value("q-a"))
                .andExpect(jsonPath("$.points[1].chunkVersion").value(1))
                .andExpect(jsonPath("$.quarantined.length()").value(0));
    }

    @Test
    void radiusAndCorridorEndpointsAreReachable() throws Exception {
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON)
                        .content(chunkJson("geo", 1, "s", "NONE",
                                "[{\"x\":3,\"y\":4,\"z\":0,\"timestampNanos\":1}]")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/pointcloud/query/radius").contentType(APPLICATION_JSON)
                        .content("{\"centerX\":0,\"centerY\":0,\"centerZ\":0,\"radiusMeters\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1));

        String corridor = "{\"vertices\":[{\"x\":0,\"y\":0,\"z\":0},{\"x\":10,\"y\":0,\"z\":0}],"
                + "\"halfWidthMeters\":5}";
        mockMvc.perform(post("/api/pointcloud/query/corridor").contentType(APPLICATION_JSON)
                        .content(corridor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1));

        mockMvc.perform(post("/api/pointcloud/query/corridor").contentType(APPLICATION_JSON)
                        .content("{\"vertices\":[{\"x\":0,\"y\":0,\"z\":0}],\"halfWidthMeters\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corruptFileIsQuarantinedViaApiAndReindexRecovers() throws Exception {
        mockMvc.perform(post("/api/pointcloud/chunks").contentType(APPLICATION_JSON)
                        .content(chunkJson("damaged", 1, "s", "NONE",
                                "[{\"x\":1,\"y\":1,\"z\":1,\"timestampNanos\":1}]")))
                .andExpect(status().isCreated());

        // 直接破坏正式块文件
        String fileName = repository.find("damaged").fileName();
        Path chunkFile = fileStore.chunkDir().resolve(fileName);
        byte[] bytes = Files.readAllBytes(chunkFile);
        bytes[20] ^= (byte) 0xFF;
        Files.write(chunkFile, bytes);

        String box = "{\"minX\":-100,\"minY\":-100,\"minZ\":-100,"
                + "\"maxX\":100,\"maxY\":100,\"maxZ\":100}";
        mockMvc.perform(post("/api/pointcloud/query/box").contentType(APPLICATION_JSON).content(box))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(0))
                .andExpect(jsonPath("$.quarantined.length()").value(1))
                .andExpect(jsonPath("$.quarantined[0].chunkId").value("damaged"));

        // 重建后索引中不应复活隔离块
        mockMvc.perform(post("/api/pointcloud/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reindexed").value(0));
    }
}
