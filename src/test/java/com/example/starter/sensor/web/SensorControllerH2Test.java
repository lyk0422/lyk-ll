package com.example.starter.sensor.web;

import com.example.starter.sensor.support.AbstractH2IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同步 REST API 端到端测试：经真实 HTTP 入口完成摄取、融合、查询、重建与错误语义。
 */
class SensorControllerH2Test extends AbstractH2IntegrationTest {

    private static final long T = 3_000_000_000L;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mockMvc;
    }

    private void ingest(String sensor, String id, long rawTs) throws Exception {
        String body = "{\"messageId\":\"" + id + "\",\"rawTimestamp\":" + rawTs + "}";
        mvc().perform(post("/api/sensors/" + sensor + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void ingestBuildQueryReconstructOverHttp() throws Exception {
        ingest("lidar", "l0", T);
        ingest("radar", "r0", T);
        ingest("camera", "c0", T);
        ingest("imu", "i0", T);

        // 构建融合帧（跳过水位线门控）
        mvc().perform(post("/api/sensors/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTimestamp\":" + T + ",\"forceWait\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.frameId").value("F-" + T))
                .andExpect(jsonPath("$.inputs.length()").value(4));

        // 查询已提交帧
        mvc().perform(get("/api/sensors/frames/" + T))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.reconstructed").value(false));

        // 事件重建（幂等，不落库）
        mvc().perform(post("/api/sensors/frames/" + T + "/reconstruct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.reconstructed").value(true))
                .andExpect(jsonPath("$.inputs.length()").value(4));

        // 水位线查询
        mvc().perform(get("/api/sensors/lidar/watermark"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(T));
    }

    @Test
    void missingFrameReturnsStableReasonOverHttp() throws Exception {
        ingest("lidar", "l0", T);
        ingest("radar", "r0", T);
        // 缺 camera / imu
        mvc().perform(post("/api/sensors/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTimestamp\":" + T + ",\"forceWait\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("MISSING_FRAME"))
                .andExpect(jsonPath("$.reasonDetail").value(org.hamcrest.Matchers.containsString("CAMERA")));
    }

    @Test
    void duplicateAndLateAreQueryableDrops() throws Exception {
        ingest("lidar", "dup", T);
        // 重复
        mvc().perform(post("/api/sensors/lidar/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"dup\",\"rawTimestamp\":" + T + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
        // 严重迟到
        mvc().perform(post("/api/sensors/lidar/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"old\",\"rawTimestamp\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LATE"));

        mvc().perform(get("/api/sensors/lidar/dropped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reason").value("DUPLICATE"))
                .andExpect(jsonPath("$[1].reason").value("LATE"));
    }

    @Test
    void unknownFrameIs404AndUnknownSensorIs400() throws Exception {
        mvc().perform(get("/api/sensors/frames/99999999"))
                .andExpect(status().isNotFound());

        mvc().perform(get("/api/sensors/sonar/watermark"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctionHotUpdateExposesVersions() throws Exception {
        mvc().perform(post("/api/sensors/imu/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slope\":1.0,\"offsetNanos\":1000,\"effectiveAt\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.offsetNanos").value(1000));

        mvc().perform(post("/api/sensors/imu/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slope\":0.0,\"offsetNanos\":0,\"effectiveAt\":0}"))
                .andExpect(status().isBadRequest());

        mvc().perform(get("/api/sensors/imu/corrections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
