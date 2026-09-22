package com.example.starter.web;

import com.example.starter.testsupport.TestDataCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 轨迹 API 的 HTTP 语义测试：建轨/查询、乐观锁 409、非法迁移 400、
 * 轨迹不存在 404、快照重放接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDataCleaner cleaner;

    @BeforeEach
    void cleanData() {
        cleaner.clean();
    }

    @Test
    void createAssociateQueryAndReplayOverHttp() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detectionId":"d1","timestamp":1000,"source":"cam-1",
                                 "category":"car","x":0.0,"y":0.0,"z":0.0,"confidence":0.9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.trackUid").value("T-000001"))
                .andExpect(jsonPath("$.status").value("TRACKING"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/tracks/T-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackUid").value("T-000001"))
                .andExpect(jsonPath("$.lastDetectionId").value("d1"));

        // 旧版本号显式关联 => 409 并返回期望/实际版本。
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detectionId":"d2","timestamp":2000,"source":"cam-1",
                                 "category":"car","x":0.5,"y":0.0,"z":0.0,"confidence":0.9,
                                 "targetTrackUid":"T-000001","expectedVersion":1,
                                 "gateThreshold":2.0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedVersion").value(1))
                .andExpect(jsonPath("$.actualVersion").value(2));

        // 非法状态迁移 TRACKING -> REIDENTIFIED（必须先 LOST 再由观测重识别）=> 400。
        mockMvc.perform(post("/api/tracks/T-000001/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus":"REIDENTIFIED","expectedVersion":2,
                                 "eventTime":1500,"reason":"跳跃测试"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 合法迁移到 OCCLUDED。
        mockMvc.perform(post("/api/tracks/T-000001/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus":"OCCLUDED","expectedVersion":2,
                                 "eventTime":1500,"reason":"短时遮挡"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OCCLUDED"))
                .andExpect(jsonPath("$.version").value(3));

        // 轨迹不存在 => 404。
        mockMvc.perform(get("/api/tracks/T-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));

        // 低置信度 => 200 业务拒绝，REJECTED/LOW_CONFIDENCE 且无轨迹产生。
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detectionId":"weak","timestamp":1600,"source":"cam-1",
                                 "category":"car","x":3.0,"y":0.0,"z":0.0,"confidence":0.1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("LOW_CONFIDENCE"));

        // 时刻 1000 的快照：T-000001 已 TRACKING，遮挡事件（1500）尚不存在。
        mockMvc.perform(get("/api/tracks/snapshots").param("asOf", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trackUid").value("T-000001"))
                .andExpect(jsonPath("$[0].status").value("TRACKING"))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void invalidPayloadReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detectionId":"","timestamp":null,"source":"cam-1",
                                 "category":"car","x":0.0,"y":0.0,"z":0.0,"confidence":-1.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
