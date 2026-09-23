package com.example.starter.calibration.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 全链路测试：错误标定 422、灰度发布、未参与车辆、回滚、resolutionId 历史查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CalibrationControllerTest {

    private static final String KEY = "lidar_top.extrinsic";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resolution_record");
        jdbc.update("DELETE FROM canary_vehicle");
        jdbc.update("DELETE FROM canary_release");
        jdbc.update("DELETE FROM param_status_log");
        jdbc.update("DELETE FROM calibration_param");
    }

    private String body(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private Map<String, Object> extrinsic(double yawRad) {
        // 绕 z 轴旋转
        double c = Math.cos(yawRad), s = Math.sin(yawRad);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rotation", List.of(
                List.of(c, -s, 0.0),
                List.of(s, c, 0.0),
                List.of(0.0, 0.0, 1.0)));
        m.put("translation", List.of(0.0, 0.0, 1.8));
        return m;
    }

    @Test
    void rejectsBadCalibrationWith422AndCreatesNothing() throws Exception {
        Map<String, Object> bad = extrinsic(0.1);
        bad.put("rotation", List.of(
                List.of(1.0, 0.5, 0.0),
                List.of(0.0, 1.0, 0.0),
                List.of(0.0, 0.0, 1.0)));
        mvc.perform(post("/api/calibration/params")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "paramKey", KEY, "kind", "EXTRINSIC", "sensorType", "LIDAR",
                                "version", "bad", "content", bad))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("正交")));

        mvc.perform(get("/api/calibration/params/" + KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullCanaryRollbackAndResolutionHistoryFlowOverHttp() throws Exception {
        // v1 全量
        mvc.perform(post("/api/calibration/params").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("paramKey", KEY, "kind", "EXTRINSIC", "sensorType", "LIDAR",
                        "version", "v1", "content", extrinsic(0.0))))).andExpect(status().isCreated());
        mvc.perform(post("/api/calibration/params/" + KEY + "/versions/v1/approve")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("operator", "alice"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/calibration/params/" + KEY + "/versions/v1/activate")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("operator", "alice"))))
                .andExpect(status().isOk());

        // v2 灰度候选
        mvc.perform(post("/api/calibration/params").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("paramKey", KEY, "kind", "EXTRINSIC", "sensorType", "LIDAR",
                        "version", "v2", "content", extrinsic(0.05),
                        "vehicleScope", List.of("V001", "V002"))))).andExpect(status().isCreated());
        mvc.perform(post("/api/calibration/params/" + KEY + "/versions/v2/approve")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("operator", "alice"))))
                .andExpect(status().isOk());

        // 灰度前 V001 解析 v1，记录 resolutionId
        MvcResult resolveBefore = mvc.perform(post("/api/calibration/resolutions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("paramKey", KEY, "vehicleId", "V001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v1"))
                .andReturn();
        JsonNode beforeJson = mapper.readTree(resolveBefore.getResponse().getContentAsString());
        String resolutionId = beforeJson.get("resolutionId").asText();
        String digestV1 = beforeJson.get("contentDigest").asText();

        // 灰度发布（仅 V001）
        MvcResult releaseResult = mvc.perform(post("/api/calibration/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("paramKey", KEY, "version", "v2",
                                "vehicles", List.of("V001"), "operator", "bob",
                                "remark", "http 灰度"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String releaseId = mapper.readTree(releaseResult.getResponse().getContentAsString())
                .get("releaseId").asText();

        // 参与车辆 v2，未参与车辆 v1
        mvc.perform(post("/api/calibration/resolutions").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("paramKey", KEY, "vehicleId", "V001"))))
                .andExpect(jsonPath("$.version").value("v2"));
        mvc.perform(get("/api/calibration/resolve")
                        .param("paramKey", KEY).param("vehicleId", "V002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v1"));

        // 回滚
        mvc.perform(post("/api/calibration/releases/" + releaseId + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("operator", "bob", "remark", "http 回滚"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mvc.perform(post("/api/calibration/resolutions").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("paramKey", KEY, "vehicleId", "V001"))))
                .andExpect(jsonPath("$.version").value("v1"));

        // 历史 resolutionId 仍指向当时 v1
        mvc.perform(get("/api/calibration/resolutions/" + resolutionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedVersion").value("v1"))
                .andExpect(jsonPath("$.contentDigest").value(digestV1))
                .andExpect(jsonPath("$.vehicleId").value("V001"));

        // 重复回滚 → 409
        mvc.perform(post("/api/calibration/releases/" + releaseId + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of())))
                .andExpect(status().isConflict());
    }

    @Test
    void returns404WhenNoVersionResolves() throws Exception {
        mvc.perform(post("/api/calibration/resolutions").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("paramKey", "missing.key", "vehicleId", "V1"))))
                .andExpect(status().isNotFound());
    }
}
