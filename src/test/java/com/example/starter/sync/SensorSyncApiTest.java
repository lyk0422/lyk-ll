package com.example.starter.sync;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * REST API 主流程与错误语义测试：接收 → 提交 → 查询 → 事件重建 → 漂移热更新。
 */
@AutoConfigureMockMvc
class SensorSyncApiTest extends AbstractSyncTest {

    @Autowired
    private MockMvc mockMvc;

    private void ingest(String sensorType, String messageId, long rawTs) throws Exception {
        mockMvc.perform(post("/api/sync/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensorType\":\"%s\",\"messageId\":\"%s\",\"rawTs\":%d}"
                                .formatted(sensorType, messageId, rawTs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void fullFlowIngestCommitQueryAndRebuild() throws Exception {
        mockMvc.perform(put("/api/sync/drift/LIDAR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"offsetMs\":20,\"effectiveFrom\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        ingest("LIDAR", "lidar-1", TARGET - 20);   // 校正后恰为目标时刻
        ingest("RADAR", "radar-1", TARGET + 30);
        ingest("CAMERA", "cam-1", TARGET - 10);
        ingest("IMU", "imu-1", TARGET + 5);

        mockMvc.perform(post("/api/sync/frames/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTs\":" + TARGET + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTs").value(TARGET))
                .andExpect(jsonPath("$.inputs", hasSize(4)))
                .andExpect(jsonPath("$.inputs[?(@.sensorType=='LIDAR')].driftVersion").doesNotExist())
                .andExpect(jsonPath("$.inputs[?(@.sensorType=='RADAR')].deltaMs").value(30));

        mockMvc.perform(get("/api/sync/frames/{targetTs}", TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs", hasSize(4)));

        mockMvc.perform(post("/api/sync/frames/{targetTs}/rebuild", TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs", hasSize(4)))
                .andExpect(jsonPath("$.inputs[?(@.sensorType=='LIDAR')].messageId").value("lidar-1"));
    }

    @Test
    void commitWithMissingSensorReturns422WithStableCode() throws Exception {
        ingest("LIDAR", "lidar-1", TARGET);
        ingest("RADAR", "radar-1", TARGET);
        ingest("CAMERA", "cam-1", TARGET);

        mockMvc.perform(post("/api/sync/frames/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTs\":" + TARGET + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_INPUT"));
    }

    @Test
    void duplicateCommitReturns409AndUnknownFrameReturns404() throws Exception {
        ingest("LIDAR", "lidar-1", TARGET);
        ingest("RADAR", "radar-1", TARGET);
        ingest("CAMERA", "cam-1", TARGET);
        ingest("IMU", "imu-1", TARGET);
        mockMvc.perform(post("/api/sync/frames/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTs\":" + TARGET + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sync/frames/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTs\":" + TARGET + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FRAME_ALREADY_COMMITTED"));

        mockMvc.perform(get("/api/sync/frames/{targetTs}", TARGET + 5_000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRAME_NOT_FOUND"));
    }

    @Test
    void invalidDriftVersionReturns400() throws Exception {
        mockMvc.perform(put("/api/sync/drift/IMU")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"offsetMs\":5,\"effectiveFrom\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/sync/drift/IMU")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"offsetMs\":9,\"effectiveFrom\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DRIFT_VERSION"));
    }
}
