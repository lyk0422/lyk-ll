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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 途中异常停车与续行许可 API 测试（MockMvc + H2 MySQL 兼容模式）：端点接线与 HTTP 状态语义。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_web_incident_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM en_route_confirmation");
        jdbc.update("DELETE FROM en_route_test");
        jdbc.update("DELETE FROM incident");
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

    /** 经 API 完成接入→核对→试验→发车，返回作业ID。 */
    private long departedJob(String key, String track) throws Exception {
        StringBuilder cars = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                cars.append(',');
            }
            cars.append("{\"carNo\":\"C").append(i)
                    .append("\",\"weightTons\":80.0,\"length\":1.3,\"valveClosed\":false}");
        }
        MvcResult accepted = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-a\",\"trainNo\":\"T1\",\"track\":\""
                                + track + "\",\"cars\":[" + cars + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        long jobId = Long.parseLong(accepted.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-c\",\"expectedCarCount\":10,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-s\",\"testType\":\"SENSITIVITY\","
                                + "\"pressureReduction\":50,\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-t\",\"testType\":\"STABILITY\","
                                + "\"pressureReduction\":140,\"pipePressureBefore\":500,"
                                + "\"emergencyOccurred\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-d\"}"))
                .andExpect(status().isOk());
        return jobId;
    }

    private long reportIncident(long jobId, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs/{id}/incidents", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-i\",\"reason\":\"制动异常\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.version", is(1)))
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 发车后停车再试验续行全流程() throws Exception {
        long jobId = departedJob("w1", "G1");
        long incidentId = reportIncident(jobId, "w1");

        // 阻断中：销记 422、旧发车入口 422
        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-d2\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 始发曲线不能冒充：未做途中试验直接续行 422
        mockMvc.perform(post("/api/jobs/{id}/incidents/{iid}/resumes", jobId, incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-r0\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity());
        // 处置版本旧版 409
        mockMvc.perform(post("/api/jobs/{id}/incidents/{iid}/en-route-tests", jobId, incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-e0\",\"expectedVersion\":9,"
                                + "\"testType\":\"SENSITIVITY\",\"pressureReduction\":50,"
                                + "\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isConflict());
        // 途中感度/安定试验
        mockMvc.perform(post("/api/jobs/{id}/incidents/{iid}/en-route-tests", jobId, incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-e1\",\"expectedVersion\":1,"
                                + "\"testType\":\"SENSITIVITY\",\"pressureReduction\":50,"
                                + "\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result", is("PASS")))
                .andExpect(jsonPath("$.incidentVersion", is(2)));
        mockMvc.perform(post("/api/jobs/{id}/incidents/{iid}/en-route-tests", jobId, incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-e2\",\"expectedVersion\":2,"
                                + "\"testType\":\"STABILITY\",\"pressureReduction\":140,"
                                + "\"pipePressureBefore\":500,\"emergencyOccurred\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result", is("PASS")));
        // 续行许可 200
        mockMvc.perform(post("/api/jobs/{id}/incidents/{iid}/resumes", jobId, incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-r\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed", is(true)))
                .andExpect(jsonPath("$.incidentVersion", is(4)));
        // 证据保留：处置单 RESUMED、两条途中试验
        mockMvc.perform(get("/api/jobs/{id}/incidents", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("RESUMED")))
                .andExpect(jsonPath("$[0].tests.length()", is(2)));
        // 作业仍发车确认态，可正常销记
        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-x2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed", is(true)));
    }

    @Test
    void 未知作业404与同键重放() throws Exception {
        mockMvc.perform(post("/api/jobs/{id}/incidents", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2-i\",\"reason\":\"制动异常\"}"))
                .andExpect(status().isNotFound());

        long jobId = departedJob("w2", "G2");
        MvcResult first = mockMvc.perform(post("/api/jobs/{id}/incidents", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2-i\",\"reason\":\"制动异常\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        // 同键同参重放：返回首次响应快照（不再新建）
        MvcResult replay = mockMvc.perform(post("/api/jobs/{id}/incidents", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2-i\",\"reason\":\"制动异常\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键异参：409
        mockMvc.perform(post("/api/jobs/{id}/incidents", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2-i\",\"reason\":\"其他原因\"}"))
                .andExpect(status().isConflict());
    }
}
