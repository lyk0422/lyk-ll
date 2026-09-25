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
 * 列车作业 API 主流程与错误语义测试（MockMvc + H2）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_web_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrainJobApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
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
                .andExpect(jsonPath("$.status", is("ACCEPTED")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 主流程接入核对试验发车销记() throws Exception {
        long jobId = accept("w1-a", "W1");

        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-c\",\"expectedCarCount\":10,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", is(true)));

        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-s\",\"testType\":\"SENSITIVITY\","
                                + "\"pressureReduction\":50,\"pipePressureBefore\":500,"
                                + "\"allCarsBraked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("PASS")));

        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-t\",\"testType\":\"STABILITY\","
                                + "\"pressureReduction\":140,\"pipePressureBefore\":500,"
                                + "\"emergencyOccurred\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("PASS")));

        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved", is(true)));

        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed", is(true)))
                .andExpect(jsonPath("$.repeated", is(false)));

        // 重复销记幂等成功
        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-x2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repeated", is(true)));

        // 销记后迟到风压只记事件
        mockMvc.perform(post("/api/jobs/{id}/pressure-readings", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-p\",\"pressure\":480,"
                                + "\"recordedAt\":\"2026-09-26T12:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late", is(true)))
                .andExpect(jsonPath("$.recorded", is(false)));

        // 复算端点可用
        mockMvc.perform(get("/api/jobs/{id}/test-curve", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests.length()", is(2)));
        mockMvc.perform(get("/api/jobs/{id}/closed-cars", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withinLimit", is(true)));
        mockMvc.perform(get("/api/jobs/{id}/consist", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistVersion", is(1)));
    }

    @Test
    void 同键异参返回409() throws Exception {
        long jobId = accept("w2-a", "W2");
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("w2-a", "W2").replace("\"trainNo\":\"T1\"", "\"trainNo\":\"T9\"")))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM train_job", Integer.class));
        mockMvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainNo", is("T1")));
    }

    @Test
    void 运统1不一致发车返回422() throws Exception {
        long jobId = accept("w3-a", "W3");
        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3-c\",\"expectedCarCount\":9,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", is(false)));
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3-d\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 作业不存在返回404() throws Exception {
        mockMvc.perform(get("/api/jobs/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void 参数缺失返回400() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainNo\":\"T1\"}"))
                .andExpect(status().isBadRequest());
    }
}
