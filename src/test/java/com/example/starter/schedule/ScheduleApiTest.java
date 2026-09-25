package com.example.starter.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口测试：编制→校验→下达→实绩→冻结全链路状态码与幂等语义。
 */
@AutoConfigureMockMvc
class ScheduleApiTest extends BaseScheduleTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String CREATE_BODY = """
            {
              "requestId": "api-c1",
              "lineCode": "L1",
              "unitNo": "U-API",
              "items": [
                {"seq": 1, "contractNo": "C1", "widthMm": 1500, "mileageKm": 2.0, "warmup": true,
                 "plannedStart": "2026-09-25T08:00:00", "plannedEnd": "2026-09-25T09:00:00"},
                {"seq": 2, "contractNo": "C2", "widthMm": 1400, "mileageKm": 2.0, "warmup": false,
                 "plannedStart": "2026-09-25T09:00:00", "plannedEnd": "2026-09-25T10:00:00"}
              ],
              "rollChangeWindows": [
                {"start": "2026-09-25T10:00:00", "end": "2026-09-25T10:30:00"}
              ]
            }
            """;

    @Test
    void fullLifecycleOverHttp() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.scheduleId")).longValue();

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").value(id));

        // 同键异参：409
        mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"mileageKm\": 2.0", "\"mileageKm\": 3.0")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/schedules/{id}/validate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        mockMvc.perform(post("/api/schedules/{id}/release", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.effective").value(true));

        mockMvc.perform(post("/api/schedules/{id}/actuals", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "api-a1", "actuals": [
                                  {"contractNo": "C1", "passNo": 1, "mileageKm": 6.0,
                                   "occurredAt": "2026-09-25T08:30:00"},
                                  {"contractNo": "C2", "passNo": 1, "mileageKm": 4.0,
                                   "occurredAt": "2026-09-25T09:30:00"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.actualMileageKm").value(10.0))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.rollChangeRequired").value(true));

        mockMvc.perform(post("/api/schedules/{id}/freeze", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        // 重复冻结幂等成功
        mockMvc.perform(post("/api/schedules/{id}/freeze", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(get("/api/schedules/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void invalidScheduleReturns422WithAllViolations() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "api-bad",
                                  "lineCode": "L1",
                                  "unitNo": "U-API-BAD",
                                  "items": [
                                    {"seq": 1, "contractNo": "C1", "widthMm": 1500, "mileageKm": 6.0,
                                     "warmup": false,
                                     "plannedStart": "2026-09-25T08:00:00",
                                     "plannedEnd": "2026-09-25T09:00:00"},
                                    {"seq": 2, "contractNo": "C2", "widthMm": 1300, "mileageKm": 6.0,
                                     "warmup": false,
                                     "plannedStart": "2026-09-25T09:00:00",
                                     "plannedEnd": "2026-09-25T10:00:00"},
                                    {"seq": 3, "contractNo": "C3", "widthMm": 1300, "mileageKm": 6.0,
                                     "warmup": false,
                                     "plannedStart": "2026-09-25T10:00:00",
                                     "plannedEnd": "2026-09-25T11:00:00"}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.scheduleId")).longValue();

        MvcResult rejected = mockMvc.perform(post("/api/schedules/{id}/validate", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations").isArray())
                .andReturn();
        String violations = rejected.getResponse().getContentAsString();
        assertThat(violations).contains("WIDTH_JUMP").contains("MILEAGE_LIMIT");

        mockMvc.perform(get("/api/schedules/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
