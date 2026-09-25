package com.example.starter.epidemic.api;

import com.example.starter.epidemic.support.TimeProvider;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 REST 入口验证状态码语义：成功 200、重复去重 200 且带已存在卡号、同键异参 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EpidemicCardWebTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeProvider timeProvider;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM card_version");
        jdbc.update("DELETE FROM special_correction");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM epidemic_card");
        jdbc.update("DELETE FROM card_number_seq");
        timeProvider.setClock(Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC));
    }

    private String initialJson(String requestId, String patient, String disease, String diagnosisType) {
        return """
                {
                  "requestId": "%s",
                  "patientId": "%s",
                  "patientName": "合成患者",
                  "diseaseCode": "%s",
                  "diagnosisType": "%s",
                  "onsetDate": "2026-09-24",
                  "diagnosisTime": "2026-09-25T07:00:00Z",
                  "facilityCode": "F001",
                  "reporter": "张报告"
                }
                """.formatted(requestId, patient, disease, diagnosisType);
    }

    @Test
    void initial_then_duplicate_then_conflicting_replay() throws Exception {
        String rid = "REQ-WEB-1";
        mockMvc.perform(post("/api/epidemic/cards/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialJson(rid, "PW1", "PLAGUE", "CONFIRMED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        // 重复报告：200，提示已存在卡片编号，不新建
        MvcResult dupResult = mockMvc.perform(post("/api/epidemic/cards/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialJson("REQ-WEB-2", "PW1", "PLAGUE", "CONFIRMED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true))
                .andReturn();
        JsonNode dupBody = objectMapper.readTree(dupResult.getResponse().getContentAsString());
        String existing = dupBody.path("existingCardNumber").asText();
        org.assertj.core.api.Assertions.assertThat(existing).startsWith("C");

        // 同 requestId 异参：409
        String conflicting = initialJson(rid, "PW1", "CHOLERA", "CONFIRMED");
        mockMvc.perform(post("/api/epidemic/cards/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }

    @Test
    void late_report_without_reason_is_400() throws Exception {
        mockMvc.perform(post("/api/epidemic/cards/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialJson("REQ-WEB-3", "PW3", "PLAGUE", "CONFIRMED")
                                .replace("2026-09-25T07:00:00Z", "2026-09-24T00:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LATE_REASON_REQUIRED"));
    }
}
