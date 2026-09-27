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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 列尾装置 API 端到端测试（MockMvc + H2 MySQL 兼容模式）：主流程、400/404/409/422 与证据查询。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_tail_web_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TailDeviceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM tail_confirmation");
        jdbc.update("DELETE FROM tail_binding");
        jdbc.update("DELETE FROM tail_device");
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

    private String tenCars() {
        StringBuilder cars = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                cars.append(',');
            }
            cars.append("{\"carNo\":\"C").append(i)
                    .append("\",\"weightTons\":80.0,\"length\":1.3,\"valveClosed\":false}");
        }
        return cars.toString();
    }

    private long acceptJob(String requestId, String track) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"trainNo\":\"T1\",\"track\":\""
                                + track + "\",\"cars\":[" + tenCars() + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 列尾主流程绑定确认发车销记与证据查询() throws Exception {
        long jobId = acceptJob("t-a", "TW1");

        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-c\",\"expectedCarCount\":10,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-s\",\"testType\":\"SENSITIVITY\","
                                + "\"pressureReduction\":50,\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-t\",\"testType\":\"STABILITY\","
                                + "\"pressureReduction\":140,\"pipePressureBefore\":500,\"emergencyOccurred\":false}"))
                .andExpect(status().isOk());

        // 建档：版本1、空闲
        mockMvc.perform(post("/api/tail-devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-r\",\"deviceNo\":\"TD1\","
                                + "\"selfCheckExpiresAt\":\"2026-09-28T12:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.occupied", is(false)));

        // 绑定：版本升至2
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-b\",\"expectedVersion\":1,\"deviceNo\":\"TD1\","
                                + "\"tailCarNo\":\"C10\",\"boundAt\":\"2026-09-28T00:01:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistVersion", is(1)))
                .andExpect(jsonPath("$.tailCarNo", is("C10")))
                .andExpect(jsonPath("$.deviceVersion", is(2)));

        // 双人确认：版本升至3
        mockMvc.perform(post("/api/jobs/{id}/tail-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-f\",\"expectedVersion\":2,\"confirmer1\":\"zhang\","
                                + "\"confirmer2\":\"wang\",\"confirmedAt\":\"2026-09-28T00:02:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tailCarNo", is("C10")))
                .andExpect(jsonPath("$.deviceVersion", is(3)));

        // 发车（制动试验 + 列尾自检/一致性同事务通过）
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"t-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved", is(true)));

        // 销记原子释放
        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"t-x\"}"))
                .andExpect(status().isOk());

        // 证据查询：装置空闲版本4，绑定历史与确认留痕
        mockMvc.perform(get("/api/tail-devices").param("deviceNo", "TD1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device.version", is(4)))
                .andExpect(jsonPath("$.device.occupied", is(false)))
                .andExpect(jsonPath("$.bindings.length()", is(1)))
                .andExpect(jsonPath("$.bindings[0].active", is(false)))
                .andExpect(jsonPath("$.bindings[0].releaseType", is("CLOSURE")))
                .andExpect(jsonPath("$.bindings[0].confirmations[0].valid", is(false)))
                .andExpect(jsonPath("$.bindings[0].confirmations[0].invalidatedReason", is("CLOSURE")));
    }

    @Test
    void 建档参数非法返回400() throws Exception {
        mockMvc.perform(post("/api/tail-devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"deviceNo\":\"TDX\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 未知装置编号绑定返回422() throws Exception {
        long jobId = acceptJob("u-a", "TW2");
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"u-b\",\"expectedVersion\":1,\"deviceNo\":\"GHOST\","
                                + "\"tailCarNo\":\"C10\",\"boundAt\":\"2026-09-28T00:01:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 证据查询未知装置返回404() throws Exception {
        mockMvc.perform(get("/api/tail-devices").param("deviceNo", "NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 绑定缺少期望版本返回400() throws Exception {
        long jobId = acceptJob("b-a", "TW4");
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b-b\",\"deviceNo\":\"TDB\","
                                + "\"tailCarNo\":\"C10\",\"boundAt\":\"2026-09-28T00:01:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 同一确认人返回422且同键异参409() throws Exception {
        long jobId = acceptJob("c-a", "TW3");
        mockMvc.perform(post("/api/tail-devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"c-r\",\"deviceNo\":\"TDC\","
                                + "\"selfCheckExpiresAt\":\"2026-09-28T12:00:00Z\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/jobs/{id}/tail-bindings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"c-b\",\"expectedVersion\":1,\"deviceNo\":\"TDC\","
                                + "\"tailCarNo\":\"C10\",\"boundAt\":\"2026-09-28T00:01:00Z\"}"))
                .andExpect(status().isOk());

        // 同一人确认：422
        mockMvc.perform(post("/api/jobs/{id}/tail-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"c-f\",\"expectedVersion\":2,\"confirmer1\":\"li\","
                                + "\"confirmer2\":\"li\",\"confirmedAt\":\"2026-09-28T00:02:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键：同键改为两名不同人员后成功
        mockMvc.perform(post("/api/jobs/{id}/tail-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"c-f\",\"expectedVersion\":2,\"confirmer1\":\"li\","
                                + "\"confirmer2\":\"wang\",\"confirmedAt\":\"2026-09-28T00:02:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceVersion", is(3)));

        // 同键异参（版本不同）：409
        mockMvc.perform(post("/api/jobs/{id}/tail-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"c-f\",\"expectedVersion\":9,\"confirmer1\":\"li\","
                                + "\"confirmer2\":\"wang\",\"confirmedAt\":\"2026-09-28T00:02:00Z\"}"))
                .andExpect(status().isConflict());
    }
}
