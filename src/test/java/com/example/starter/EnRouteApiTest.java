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
 * 途中异常停车与续行许可 API 主流程与错误语义测试（MockMvc + H2）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_enroute_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnRouteApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM en_route_confirmation");
        jdbc.update("DELETE FROM en_route_test");
        jdbc.update("DELETE FROM en_route_disposition");
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

    /** 经 HTTP 完成接入、核对、感度/安定试验与发车确认，返回作业ID。 */
    private long departJob(String key, String track) throws Exception {
        MvcResult accepted = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody(key + "-a", track)))
                .andExpect(status().isCreated())
                .andReturn();
        long jobId = Long.parseLong(accepted.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/jobs/{id}/consist-checks", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-c\",\"expectedCarCount\":10,"
                                + "\"expectedTotalLength\":13.0,\"expectedTotalWeight\":800.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", is(true)));
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-bs\",\"testType\":\"SENSITIVITY\","
                                + "\"pressureReduction\":50,\"pipePressureBefore\":500,"
                                + "\"allCarsBraked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("PASS")));
        mockMvc.perform(post("/api/jobs/{id}/brake-tests", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-bt\",\"testType\":\"STABILITY\","
                                + "\"pressureReduction\":140,\"pipePressureBefore\":500,"
                                + "\"emergencyOccurred\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("PASS")));
        mockMvc.perform(post("/api/jobs/{id}/departure-confirmations", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved", is(true)));
        return jobId;
    }

    private long reportAbnormality(String key, long jobId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key + "\",\"reason\":\"途中制动异常（合成数据）\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.resumedAt", nullValue()))
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 主流程发车后停车再试验续行() throws Exception {
        long jobId = departJob("w1", "WE1");
        long dispositionId = reportAbnormality("w1-r", jobId);

        // 阻断中不得销记释放列尾
        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-x\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 途中感度/安定试验（新压力曲线，绑定处置单）
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions/{dId}/tests", jobId, dispositionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-s\",\"expectedVersion\":1,"
                                + "\"testType\":\"SENSITIVITY\",\"pressureReduction\":50,"
                                + "\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result", is("PASS")))
                .andExpect(jsonPath("$.dispositionVersion", is(2)));
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions/{dId}/tests", jobId, dispositionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-t\",\"expectedVersion\":2,"
                                + "\"testType\":\"STABILITY\",\"pressureReduction\":140,"
                                + "\"pipePressureBefore\":500,\"emergencyOccurred\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result", is("PASS")))
                .andExpect(jsonPath("$.dispositionVersion", is(3)));

        // 两名不同人员确认新试验与列尾一致性
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions/{dId}/confirmations",
                        jobId, dispositionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-f\",\"expectedVersion\":3,"
                                + "\"confirmer1\":\"张三\",\"confirmer2\":\"李四\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.dispositionVersion", is(4)));

        // 续行许可：结束本次阻断
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions/{dId}/resume", jobId, dispositionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-re\",\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed", is(true)))
                .andExpect(jsonPath("$.version", is(5)));

        // 证据保留：处置单终态与途中试验/确认历史可查；作业仍发车确认状态
        mockMvc.perform(get("/api/jobs/{id}/en-route-dispositions/{dId}", jobId, dispositionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESUMED")))
                .andExpect(jsonPath("$.tests.length()", is(2)))
                .andExpect(jsonPath("$.confirmations.length()", is(1)));
        mockMvc.perform(get("/api/jobs/{id}/en-route-dispositions/active", jobId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DEPARTURE_CONFIRMED")));

        // 阻断结束后可销记
        mockMvc.perform(post("/api/jobs/{id}/closures", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1-x2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed", is(true)));
    }

    @Test
    void 错误语义未知作业404未发车422旧版409同键异参409() throws Exception {
        // 未知作业：404
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2-r0\",\"reason\":\"异常\"}"))
                .andExpect(status().isNotFound());

        // 未发车确认：422
        MvcResult accepted = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody("w2-a", "WE2")))
                .andExpect(status().isCreated())
                .andReturn();
        long acceptedId = Long.parseLong(accepted.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions", acceptedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2-r1\",\"reason\":\"异常\"}"))
                .andExpect(status().isUnprocessableEntity());

        long jobId = departJob("w3", "WE3");
        long dispositionId = reportAbnormality("w3-r", jobId);

        // 同作业仅一条活动处置：409
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3-r2\",\"reason\":\"另一条异常\"}"))
                .andExpect(status().isConflict());

        // 旧版 expectedVersion：409
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions/{dId}/tests", jobId, dispositionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3-s0\",\"expectedVersion\":9,"
                                + "\"testType\":\"SENSITIVITY\",\"pressureReduction\":50,"
                                + "\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isConflict());

        // 同键异参：409
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3-r\",\"reason\":\"异参异常\"}"))
                .andExpect(status().isConflict());

        // 跨作业引用：404
        long otherJobId = departJob("w4", "WE4");
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions/{dId}/tests", otherJobId, dispositionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w4-s\",\"expectedVersion\":1,"
                                + "\"testType\":\"SENSITIVITY\",\"pressureReduction\":50,"
                                + "\"pipePressureBefore\":500,\"allCarsBraked\":true}"))
                .andExpect(status().isNotFound());

        // 参数校验失败：400
        mockMvc.perform(post("/api/jobs/{id}/en-route-dispositions", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"reason\":\"异常\"}"))
                .andExpect(status().isBadRequest());
    }
}
