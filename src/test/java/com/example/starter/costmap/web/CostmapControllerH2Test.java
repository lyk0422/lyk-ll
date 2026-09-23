package com.example.starter.costmap.web;

import com.example.starter.costmap.support.AbstractH2IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口端到端测试：验证稳定 HTTP 状态码、错误码与合成业务主流程
 * （建图 -> 障碍物更新 -> 区域查询 -> 快照 -> 差异 -> 恢复）。
 */
class CostmapControllerH2Test extends AbstractH2IntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        String createBody = """
                {"mapId":"m-http","width":8,"height":8,"resolution":0.1,
                 "inscribedRadius":1.0,"inflationRadius":2.0,"costScalingFactor":2.0}
                """;
        mockMvc.perform(post("/api/costmaps")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mapId").value("m-http"))
                .andExpect(jsonPath("$.liveSeq").value(0));

        String obstacleBody = """
                {"requestId":"req-1","sourceId":"lidar","obstacleId":"box",
                 "priority":3,"kind":"OCCUPY",
                 "region":{"x0":3,"y0":3,"x1":3,"y1":3},
                 "eventTime":1000,"ttlMillis":100000}
                """;
        mockMvc.perform(post("/api/costmaps/m-http/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(obstacleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.statementSeq").value(1))
                .andExpect(jsonPath("$.recomputeRegion.x0").value(0))
                .andExpect(jsonPath("$.recomputeRegion.x1").value(6));

        // 同参重放：200 且 replayed=true，不分配新序号
        mockMvc.perform(post("/api/costmaps/m-http/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(obstacleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.statementSeq").value(1));

        mockMvc.perform(get("/api/costmaps/m-http/regions")
                        .param("x0", "3").param("y0", "3").param("x1", "3").param("y1", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells[0].cost").value(254));

        mockMvc.perform(post("/api/costmaps/m-http/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snapshotId\":\"snap-1\",\"label\":\"base\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1));

        mockMvc.perform(get("/api/costmaps/m-http/snapshots/snap-1/diff")
                        .param("x0", "0").param("y0", "0").param("x1", "7").param("y1", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray());

        mockMvc.perform(post("/api/costmaps/m-http/snapshots/snap-1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredStatementCount").value(1));
    }

    @Test
    void stableErrorsOverHttp() throws Exception {
        // 地图缺失 -> 404 MAP_NOT_FOUND
        mockMvc.perform(get("/api/costmaps/missing/regions")
                        .param("x0", "0").param("y0", "0").param("x1", "1").param("y1", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MAP_NOT_FOUND"));

        String createBody = """
                {"mapId":"m-err","width":4,"height":4,"resolution":0.1,
                 "inscribedRadius":1.0,"inflationRadius":2.0,"costScalingFactor":2.0}
                """;
        mockMvc.perform(post("/api/costmaps")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated());

        // 重复地图 ID -> 409 VERSION_CONFLICT
        mockMvc.perform(post("/api/costmaps")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"));

        // 非法区域 -> 400 INVALID_REGION
        String obstacleBody = """
                {"requestId":"bad","sourceId":"lidar","obstacleId":"box",
                 "priority":1,"kind":"OCCUPY",
                 "region":{"x0":0,"y0":0,"x1":9,"y1":9},
                 "eventTime":1000,"ttlMillis":100000}
                """;
        mockMvc.perform(post("/api/costmaps/m-err/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(obstacleBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REGION"));

        // Bean 校验失败（缺 requestId） -> 400 BAD_REQUEST
        String invalidBody = """
                {"sourceId":"lidar","obstacleId":"box","priority":1,"kind":"OCCUPY",
                 "region":{"x0":0,"y0":0,"x1":1,"y1":1},"ttlMillis":100000}
                """;
        mockMvc.perform(post("/api/costmaps/m-err/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        // 同 requestId 异参 -> 409 IDEMPOTENCY_CONFLICT
        String first = """
                {"requestId":"dup","sourceId":"lidar","obstacleId":"box","priority":1,
                 "kind":"OCCUPY","region":{"x0":0,"y0":0,"x1":0,"y1":0},
                 "eventTime":1000,"ttlMillis":100000}
                """;
        String second = """
                {"requestId":"dup","sourceId":"lidar","obstacleId":"box","priority":1,
                 "kind":"OCCUPY","region":{"x0":1,"y0":1,"x1":1,"y1":1},
                 "eventTime":1000,"ttlMillis":100000}
                """;
        mockMvc.perform(post("/api/costmaps/m-err/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/costmaps/m-err/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        // 快照缺失 -> 404 SNAPSHOT_NOT_FOUND
        mockMvc.perform(get("/api/costmaps/m-err/snapshots/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SNAPSHOT_NOT_FOUND"));
    }
}
