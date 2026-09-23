package com.example.starter.calibration.api;

import com.example.starter.calibration.AbstractH2Test;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标定服务 REST 端到端测试（真实 H2 + 真实事务 + MockMvc，不启动外部服务）：
 * 验证 201/422/409/404 错误语义与发布、解析、灰度失败、回滚完整 HTTP 流程。
 */
class CalibrationControllerH2Test extends AbstractH2Test {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void fullHttpFlowCreateResolveCanaryFailureRollback() throws Exception {
        // 1) 非法标定 → 422，且不产生版本
        String badIntrinsic = """
                {"paramKey":"cam_front","sensorType":"CAMERA","kind":"INTRINSIC","version":"1.0.0",
                 "content":{"fx":0,"fy":1000,"cx":640,"cy":360},"operator":"alice"}
                """;
        mvc.perform(post("/api/calibrations").contentType(MediaType.APPLICATION_JSON).content(badIntrinsic))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fx")));

        // 2) 合法版本 v1 → 201
        String goodV1 = """
                {"paramKey":"cam_front","sensorType":"CAMERA","kind":"INTRINSIC","version":"1.0.0",
                 "content":{"fx":1000,"fy":980,"cx":640,"cy":360},"operator":"alice"}
                """;
        MvcResult created1 = mvc.perform(
                        post("/api/calibrations").contentType(MediaType.APPLICATION_JSON).content(goodV1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        long v1Id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created1.getResponse().getContentAsString(), "$.id")).longValue();

        // 同键同版本重复创建 → 409
        mvc.perform(post("/api/calibrations").contentType(MediaType.APPLICATION_JSON).content(goodV1))
                .andExpect(status().isConflict());

        // 3) 审批 + 全量生效
        mvc.perform(post("/api/calibrations/" + v1Id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/calibrations/" + v1Id + "/activate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 4) 解析基线
        MvcResult baseline = mvc.perform(post("/api/calibrations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"cam_front\",\"vehicleId\":\"V001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedVersionId").value(v1Id))
                .andReturn();
        long baselineVersion = ((Number) com.jayway.jsonpath.JsonPath.read(
                baseline.getResponse().getContentAsString(), "$.selectedVersionId")).longValue();

        // 5) 创建并审批 v2（车辆范围 V000..V050）
        String goodV2 = """
                {"paramKey":"cam_front","sensorType":"CAMERA","kind":"INTRINSIC","version":"2.0.0",
                 "content":{"fx":1010,"fy":990,"cx":640,"cy":360},
                 "vehicleFrom":"V000","vehicleTo":"V050","operator":"alice"}
                """;
        MvcResult created2 = mvc.perform(
                        post("/api/calibrations").contentType(MediaType.APPLICATION_JSON).content(goodV2))
                .andExpect(status().isCreated())
                .andReturn();
        long v2Id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created2.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post("/api/calibrations/" + v2Id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"bob\"}"))
                .andExpect(status().isOk());

        // 6) 灰度部分失败（V070 超范围）→ 422 整批回滚，车辆仍解析 v1
        mvc.perform(post("/api/calibrations/canaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canaryVersionId\":" + v2Id + ",\"vehicleIds\":[\"V001\",\"V070\"],\"operator\":\"bob\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/calibrations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"cam_front\",\"vehicleId\":\"V001\"}"))
                .andExpect(jsonPath("$.selectedVersionId").value(baselineVersion));

        // 7) 仅灰度 V001/V002：灰度车解析 v2，其他车解析 v1
        MvcResult canary = mvc.perform(post("/api/calibrations/canaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canaryVersionId\":" + v2Id + ",\"vehicleIds\":[\"V001\",\"V002\"],\"operator\":\"bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn();
        String releaseId = com.jayway.jsonpath.JsonPath.read(
                canary.getResponse().getContentAsString(), "$.releaseId");

        MvcResult canaryResolution = mvc.perform(post("/api/calibrations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"cam_front\",\"vehicleId\":\"V001\"}"))
                .andExpect(jsonPath("$.selectedVersionId").value(v2Id))
                .andReturn();
        String canaryResolutionId = com.jayway.jsonpath.JsonPath.read(
                canaryResolution.getResponse().getContentAsString(), "$.resolutionId");
        mvc.perform(post("/api/calibrations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"cam_front\",\"vehicleId\":\"V099\"}"))
                .andExpect(jsonPath("$.selectedVersionId").value(v1Id));

        // 8) 回滚 → 灰度车恢复 v1
        mvc.perform(post("/api/calibrations/canaries/" + releaseId + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mvc.perform(post("/api/calibrations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"cam_front\",\"vehicleId\":\"V001\"}"))
                .andExpect(jsonPath("$.selectedVersionId").value(v1Id));

        // 9) 历史 resolutionId 仍指向当时灰度版本 v2
        mvc.perform(get("/api/calibrations/resolutions/" + canaryResolutionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedVersionId").value(v2Id))
                .andExpect(jsonPath("$.selectedVersion").value("2.0.0"));

        // 10) 未知 resolutionId → 404
        mvc.perform(get("/api/calibrations/resolutions/non-existent-id"))
                .andExpect(status().isNotFound());
    }
}
