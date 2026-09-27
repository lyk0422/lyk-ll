package com.example.starter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 列尾装置绑定 API 主流程与错误语义测试（MockMvc + H2）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_tail_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TailDeviceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM tail_consistency_confirmation");
        jdbc.update("DELETE FROM tail_binding");
        jdbc.update("DELETE FROM tail_device");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM pressure_reading");
        jdbc.update("DELETE FROM brake_test");
        jdbc.update("DELETE FROM consist_check");
        jdbc.update("DELETE FROM train_car");
        jdbc.update("DELETE FROM train_job");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    private String acceptBody(String requestId, String track, boolean tailControl) {
        StringBuilder cars = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                cars.append(',');
            }
            cars.append("{\"carNo\":\"C").append(i)
                    .append("\",\"weightTons\":80.0,\"length\":1.3,\"valveClosed\":false}");
        }
        return "{\"requestId\":\"" + requestId + "\",\"trainNo\":\"T1\",\"track\":\"" + track
                + "\",\"cars\":[" + cars + "],\"tailControlEnabled\":" + tailControl + "}";
    }

    private long acceptControlled(String requestId, String track) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody(requestId, track, true)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void registerDevice(String requestId, String deviceNo, String validUntil) throws Exception {
        mockMvc.perform(post("/api/tail-devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"deviceNo\":\"" + deviceNo
                                + "\",\"selfTestValidUntil\":\"" + validUntil + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceNo", is(deviceNo)))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.currentJobId", nullValue()));
    }

    @Test
    void 列尾受控主流程绑定确认发车销记() throws Exception {
        registerDevice("a1-rd", "D1", "2099-01-01T00:00:00Z");
        long jobId = acceptControlled("a1-a", "W1");

        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-c\",\"expectedCarCount\":10,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", is(true)));
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-s\",\"testType\":\"SENSITIVITY\","
                                + "\"pressureReduction\":50,\"pipePressureBefore\":500,"
                                + "\"allCarsBraked\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-t\",\"testType\":\"STABILITY\","
                                + "\"pressureReduction\":140,\"pipePressureBefore\":500,"
                                + "\"emergencyOccurred\":false}"))
                .andExpect(status().isOk());

        // 绑定列尾装置（冻结尾车 C10 与编组版本 1）
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-b\",\"deviceNo\":\"D1\",\"expectedVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tailCarNo", is("C10")))
                .andExpect(jsonPath("$.consistVersion", is(1)))
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        // 装置被占用，版本加1
        mockMvc.perform(get("/api/tail-devices/{deviceNo}", "D1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentJobId", is((int) jobId)))
                .andExpect(jsonPath("$.version", is(2)));

        // 未双人确认时发车拒绝（422）
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-d0\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 两名不同人员确认
        mockMvc.perform(post("/api/jobs/{id}/tail-consistency-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-cf1\",\"confirmer\":\"检车员甲\","
                                + "\"tailCarNo\":\"C10\",\"consistVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valid", is(true)));
        mockMvc.perform(post("/api/jobs/{id}/tail-consistency-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-cf2\",\"confirmer\":\"检车员乙\","
                                + "\"tailCarNo\":\"C10\",\"consistVersion\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved", is(true)));

        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed", is(true)));

        // 销记后装置原子释放，绑定历史保留
        mockMvc.perform(get("/api/tail-devices/{deviceNo}", "D1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentJobId", nullValue()))
                .andExpect(jsonPath("$.version", is(3)));
        mockMvc.perform(get("/api/jobs/{id}/tail-bindings", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings.length()", is(1)))
                .andExpect(jsonPath("$.bindings[0].status", is("RELEASED")))
                .andExpect(jsonPath("$.bindings[0].releaseReason", is("CLOSURE")))
                .andExpect(jsonPath("$.bindings[0].confirmations.length()", is(2)));
    }

    @Test
    void 装置编号重复建档409未知装置404版本冲突409() throws Exception {
        registerDevice("a2-rd", "D2", "2099-01-01T00:00:00Z");
        // 重复编号建档：409
        mockMvc.perform(post("/api/tail-devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a2-rd2\",\"deviceNo\":\"D2\","
                                + "\"selfTestValidUntil\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isConflict());
        // 未知装置：404
        mockMvc.perform(get("/api/tail-devices/{deviceNo}", "D-UNKNOWN"))
                .andExpect(status().isNotFound());
        // 自检更新版本不符：409
        mockMvc.perform(post("/api/tail-devices/{deviceNo}/self-tests", "D2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a2-st\",\"expectedVersion\":9,"
                                + "\"selfTestValidUntil\":\"2099-06-01T00:00:00Z\"}"))
                .andExpect(status().isConflict());
        // 版本匹配：成功，版本加1
        mockMvc.perform(post("/api/tail-devices/{deviceNo}/self-tests", "D2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a2-st2\",\"expectedVersion\":1,"
                                + "\"selfTestValidUntil\":\"2099-06-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(2)));
    }

    @Test
    void 绑定错误语义未知装置404过期422参数缺失400() throws Exception {
        long jobId = acceptControlled("a3-a", "W3");
        // 未知装置编号：404
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b1\",\"deviceNo\":\"D-UNKNOWN\","
                                + "\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        // 装置自检已过期（截止时刻早于当前）：422
        registerDevice("a3-rd", "D3", "2000-01-01T00:00:00Z");
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b2\",\"deviceNo\":\"D3\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity());
        // 参数缺失：400
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b3\"}"))
                .andExpect(status().isBadRequest());
        // 作业不存在：404
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b4\",\"deviceNo\":\"D3\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 提前解绑后装置释放且确认撤销() throws Exception {
        registerDevice("a4-rd", "D4", "2099-01-01T00:00:00Z");
        long jobId = acceptControlled("a4-a", "W4");
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-b\",\"deviceNo\":\"D4\",\"expectedVersion\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/jobs/{id}/tail-consistency-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-cf1\",\"confirmer\":\"检车员甲\","
                                + "\"tailCarNo\":\"C10\",\"consistVersion\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/jobs/{id}/tail-unbind", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-u\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.releaseReason", is("EARLY_UNBIND")));

        mockMvc.perform(get("/api/tail-devices/{deviceNo}", "D4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentJobId", nullValue()))
                .andExpect(jsonPath("$.version", is(3)));
        mockMvc.perform(get("/api/jobs/{id}/tail-bindings", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings[0].confirmations[0].valid", is(false)));
    }
}
