package com.example.starter.costmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 代价地图 REST API 测试：验证公开主流程与稳定错误语义（HTTP 状态 + 错误码）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostmapControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM obstacle_update");
        jdbc.update("DELETE FROM obstacle_request");
        jdbc.update("DELETE FROM costmap_snapshot");
    }

    private String newMapId() {
        return "http-" + UUID.randomUUID();
    }

    private void createMap(String mapId) throws Exception {
        mvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mapId":"%s","width":10,"height":10,"resolution":1.0,"inflationRadius":1.0}
                                """.formatted(mapId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.grid", hasSize(100)));
    }

    private void applyObstacle(String mapId, String requestId, int x, int y) throws Exception {
        mvc.perform(post("/api/maps/{mapId}/obstacles", mapId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","sourceId":"lidar","priority":1,
                                 "region":{"x":%d,"y":%d,"width":1,"height":1},
                                 "cells":[254],"timestamp":1000}
                                """.formatted(requestId, x, y)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        String mapId = newMapId();
        createMap(mapId);
        applyObstacle(mapId, "req-1", 3, 3);

        mvc.perform(get("/api/maps/{mapId}", mapId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.grid[33]").value(254));

        mvc.perform(get("/api/maps/{mapId}/versions/0", mapId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grid[33]").value(0));

        mvc.perform(get("/api/maps/{mapId}/diff", mapId)
                        .param("fromVersion", "0")
                        .param("toVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.changes[?(@.x==3 && @.y==3)].toCost").value(org.hamcrest.Matchers.hasItem(254)));

        mvc.perform(post("/api/maps/{mapId}/snapshots", mapId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        applyObstacle(mapId, "req-2", 7, 7);

        mvc.perform(get("/api/maps/{mapId}/snapshots/1", mapId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grid[33]").value(254))
                .andExpect(jsonPath("$.grid[77]").value(0));

        mvc.perform(post("/api/maps/{mapId}/snapshots/1/restore", mapId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.grid[77]").value(0));

        mvc.perform(post("/api/maps/{mapId}/cleanup", mapId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(0));
    }

    @Test
    void idempotentReplayOverHttp() throws Exception {
        String mapId = newMapId();
        createMap(mapId);
        applyObstacle(mapId, "req-1", 3, 3);

        mvc.perform(post("/api/maps/{mapId}/obstacles", mapId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","sourceId":"lidar","priority":1,
                                 "region":{"x":3,"y":3,"width":1,"height":1},
                                 "cells":[254],"timestamp":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void errorSemanticsAreStable() throws Exception {
        String mapId = newMapId();
        createMap(mapId);

        mvc.perform(get("/api/maps/{mapId}", "no-such-map"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAP_NOT_FOUND"));

        mvc.perform(get("/api/maps/{mapId}/versions/99", mapId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));

        mvc.perform(get("/api/maps/{mapId}/snapshots/5", mapId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));

        mvc.perform(post("/api/maps/{mapId}/obstacles", mapId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bad-region","sourceId":"lidar",
                                 "region":{"x":9,"y":9,"width":2,"height":2},
                                 "cells":[1,2,3,4],"timestamp":1000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REGION"));

        mvc.perform(post("/api/maps/{mapId}/obstacles", mapId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bad-cells","sourceId":"lidar",
                                 "region":{"x":0,"y":0,"width":2,"height":2},
                                 "cells":[1,2],"timestamp":1000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        applyObstacle(mapId, "req-1", 3, 3);
        mvc.perform(post("/api/maps/{mapId}/obstacles", mapId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","sourceId":"lidar",
                                 "region":{"x":4,"y":4,"width":1,"height":1},
                                 "cells":[254],"timestamp":1000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));

        mvc.perform(post("/api/maps/{mapId}/snapshots", mapId))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/maps/{mapId}/snapshots", mapId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_CONFLICT"));

        mvc.perform(post("/api/maps/{mapId}/obstacles", mapId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
