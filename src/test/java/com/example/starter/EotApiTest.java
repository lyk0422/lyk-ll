package com.example.starter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 列尾装置 API 主流程与错误语义测试（MockMvc + H2 MySQL 兼容模式，真实事务与唯一约束）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_web_eot_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EotApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM eot_consistency_confirmation");
        jdbc.update("DELETE FROM eot_binding");
        jdbc.update("DELETE FROM eot_device");
        jdbc.update("DELETE FROM idempotency_record");
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

    private String acceptBody(String requestId, String track) {
        StringBuilder cars = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                cars.append(',');
            }
            cars.append("{\"carNo\":\"C").append(i)
                    .append("\",\"weightTons\":80.0,\"length\":1.3,\"valveClosed\":false}");
        }
        return "{\"requestId\":\"" + requestId + "\",\"trainNo\":\"T1\",\"track\":\"" + track
                + "\",\"cars\":[" + cars + "]}";
    }

    private long accept(String requestId, String track) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody(requestId, track)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void checkAndPassBrake(long jobId, String prefix) throws Exception {
        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + prefix + "-c\",\"expectedCarCount\":10,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", is(true)));
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + prefix + "-s\",\"testType\":\"SENSITIVITY\","
                                + "\"pressureReduction\":50,\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + prefix + "-t\",\"testType\":\"STABILITY\","
                                + "\"pressureReduction\":140,\"pipePressureBefore\":500,\"emergencyOccurred\":false}"))
                .andExpect(status().isOk());
    }

    private void registerDevice(String requestId, String deviceNo, String validUntil) throws Exception {
        mockMvc.perform(post("/api/eot-devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"deviceNo\":\"" + deviceNo
                                + "\",\"selfCheckValidUntil\":\"" + validUntil + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.deviceNo", is(deviceNo)));
    }

    @Test
    void 列尾主流程建档绑定双人确认发车销记证据留存() throws Exception {
        long jobId = accept("a1-a", "A1");
        checkAndPassBrake(jobId, "a1");
        registerDevice("a1-r", "EOT-001", "2099-01-01T00:00:00Z");

        // 未做双人确认直接发车：422
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-b\",\"deviceNo\":\"EOT-001\",\"expectedVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.tailCarNo", is("C10")))
                .andExpect(jsonPath("$.consistVersion", is(1)))
                .andExpect(jsonPath("$.deviceVersion", is(2)));

        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-d0\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 两名不同人员共同确认最新尾车与版本
        mockMvc.perform(post("/api/jobs/{id}/eot-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-f\",\"expectedVersion\":2,\"tailCarNo\":\"C10\","
                                + "\"consistVersion\":1,\"confirmer1\":\"ZhangSan\",\"confirmer2\":\"LiSi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.deviceVersion", is(3)));

        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved", is(true)));

        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a1-x\"}"))
                .andExpect(status().isOk());

        // 销记后装置释放，绑定历史与确认证据保留（确认标记失效）
        mockMvc.perform(get("/api/eot-devices/EOT-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundJobId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.bindings.length()", is(1)))
                .andExpect(jsonPath("$.bindings[0].status", is("RELEASED")))
                .andExpect(jsonPath("$.bindings[0].confirmations.length()", is(1)))
                .andExpect(jsonPath("$.bindings[0].confirmations[0].valid", is(false)));
        mockMvc.perform(get("/api/jobs/{id}/eot-evidence", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)));
    }

    @Test
    void 参数非法返回400() throws Exception {
        long jobId = accept("a2-a", "A2");
        // 建档缺编号
        mockMvc.perform(post("/api/eot-devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a2-r\",\"selfCheckValidUntil\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        registerDevice("a2-r2", "EOT-002", "2099-01-01T00:00:00Z");
        // expectedVersion 非正数：400
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a2-b\",\"deviceNo\":\"EOT-002\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        // 确认时刻/人员缺失：400
        mockMvc.perform(post("/api/jobs/{id}/eot-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a2-f\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 编号未知404版本不符409占用与过期422() throws Exception {
        long jobId = accept("a3-a", "A3");
        registerDevice("a3-r", "EOT-003", "2099-01-01T00:00:00Z");

        // 编号未知：404
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b0\",\"deviceNo\":\"EOT-MISSING\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());

        // 版本不符：409
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b1\",\"deviceNo\":\"EOT-003\",\"expectedVersion\":9}"))
                .andExpect(status().isConflict());

        // 自检恰达截止（UTC 2026-01-01T00:00:00Z，已过期）：422
        registerDevice("a3-rx", "EOT-003X", "2026-01-01T00:00:00Z");
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b2\",\"deviceNo\":\"EOT-003X\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity());

        // 合法绑定后装置占用：另一作业再绑 422
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b3\",\"deviceNo\":\"EOT-003\",\"expectedVersion\":1}"))
                .andExpect(status().isCreated());
        long other = accept("a3-o", "A4");
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-b4\",\"deviceNo\":\"EOT-003\",\"expectedVersion\":2}"))
                .andExpect(status().isUnprocessableEntity());

        // 同一确认人充当两人：422
        mockMvc.perform(post("/api/jobs/{id}/eot-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-f1\",\"expectedVersion\":2,\"tailCarNo\":\"C10\","
                                + "\"consistVersion\":1,\"confirmer1\":\"X\",\"confirmer2\":\"X\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 尾车/版本不匹配：422
        mockMvc.perform(post("/api/jobs/{id}/eot-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a3-f2\",\"expectedVersion\":2,\"tailCarNo\":\"C9\","
                                + "\"consistVersion\":1,\"confirmer1\":\"X\",\"confirmer2\":\"Y\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 同键同参重放同键异参409解绑后无列尾门禁() throws Exception {
        long jobId = accept("a4-a", "A5");
        checkAndPassBrake(jobId, "a4");
        registerDevice("a4-r", "EOT-004", "2099-01-01T00:00:00Z");

        String bindBody = "{\"requestId\":\"a4-b\",\"deviceNo\":\"EOT-004\",\"expectedVersion\":1}";
        MvcResult first = mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isCreated()).andReturn();
        int firstBindingId = Integer.parseInt(first.getResponse().getContentAsString()
                .replaceAll(".*\"bindingId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bindingId", is(firstBindingId)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM eot_binding", Integer.class));

        // 同键异参：409
        mockMvc.perform(post("/api/jobs/{id}/eot-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-b\",\"deviceNo\":\"EOT-004\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict());

        // 双人确认后发车前提前解绑：成功并撤销确认
        mockMvc.perform(post("/api/jobs/{id}/eot-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-f\",\"expectedVersion\":2,\"tailCarNo\":\"C10\","
                                + "\"consistVersion\":1,\"confirmer1\":\"X\",\"confirmer2\":\"Y\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/eot-unbindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-u\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released", is(true)))
                .andExpect(jsonPath("$.voidedConfirmations", is(1)))
                .andExpect(jsonPath("$.deviceVersion", is(4)));

        // 提前解绑后无列尾新门禁，制动合格即可发车
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a4-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved", is(true)));

        // 作业不存在的证据查询：404
        mockMvc.perform(get("/api/jobs/{id}/eot-evidence", 999999))
                .andExpect(status().isNotFound());
    }
}
