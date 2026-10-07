package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 施工段闭锁 HTTP 层测试：端点状态码、错误报文与可用区间查询的端到端语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConstructionControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-05T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acceptance_credential");
        jdbc.update("DELETE FROM construction_segment");
        jdbc.update("DELETE FROM construction_order");
        jdbc.update("DELETE FROM controlled_runway");
        jdbc.update("DELETE FROM routine_completion_report");
        jdbc.update("DELETE FROM routine_check_item");
        jdbc.update("DELETE FROM routine_plan");
        jdbc.update("DELETE FROM flight_occupancy");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM runway_lock");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
        jdbc.update("MERGE INTO flight_calendar (version, published_at, request_id, created_at) KEY (version)"
                + " VALUES (1, ?, NULL, ?)", Timestamp.from(T0), Timestamp.from(T0));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void registerResizeAndQueryEndpoints() throws Exception {
        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RunwayRegisterRequest("h-reg", "RW-H1", 1000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lengthMeters").value(1000))
                .andExpect(jsonPath("$.version").value(1));

        // 重复登记（异requestId）409
        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RunwayRegisterRequest("h-reg2", "RW-H1", 1000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNWAY_ALREADY_REGISTERED"));

        // 旧版长度变更 409
        mockMvc.perform(put("/api/controlled-runways/RW-H1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RunwayResizeRequest("h-rs0", 9, 2000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNWAY_VERSION_CONFLICT"));

        mockMvc.perform(put("/api/controlled-runways/RW-H1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RunwayResizeRequest("h-rs1", 1, 2000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lengthMeters").value(2000))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/controlled-runways/RW-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lengthMeters").value(2000));

        mockMvc.perform(get("/api/controlled-runways/RW-NONE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTROLLED_RUNWAY_NOT_FOUND"));
    }

    @Test
    void constructionOrderLifecycleEndpoints() throws Exception {
        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RunwayRegisterRequest("h-reg-b", "RW-H2", 1000))))
                .andExpect(status().isCreated());

        // 同单重叠 422
        mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-co-bad", "RW-H2",
                                List.of(new ConstructionSegmentInput(1, 0, 500),
                                        new ConstructionSegmentInput(2, 400, 600))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSTRUCTION_SEGMENT_OVERLAP"));

        String created = mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-co", "RW-H2",
                                List.of(new ConstructionSegmentInput(1, 100, 400),
                                        new ConstructionSegmentInput(2, 600, 900))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(created).get("id").asLong();

        // 生效
        mockMvc.perform(post("/api/construction-orders/{id}/activate", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionActivateRequest("h-act", 1,
                                T0.plus(Duration.ofMinutes(1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));

        // 已生效不得修改缩段
        mockMvc.perform(put("/api/construction-orders/{id}", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderModifyRequest("h-mod", 2,
                                List.of(new ConstructionSegmentInput(1, 100, 200))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSTRUCTION_ORDER_NOT_PENDING"));

        // 可用区间查询
        mockMvc.perform(get("/api/controlled-runways/RW-H2/available-areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable.length()").value(2))
                .andExpect(jsonPath("$.available.length()").value(3))
                .andExpect(jsonPath("$.availableTotalMeters").value(400));

        // 分段验收：两个不同验收人
        mockMvc.perform(post("/api/construction-orders/{id}/segments/1/acceptances", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AcceptanceRequest("h-ac1", "inspector-A", "CLR-A",
                                T0.plus(Duration.ofMinutes(30))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segmentStatus").value("LOCKED"));
        mockMvc.perform(post("/api/construction-orders/{id}/segments/1/acceptances", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AcceptanceRequest("h-ac2", "inspector-B", "CLR-B",
                                T0.plus(Duration.ofMinutes(31))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segmentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.orderStatus").value("ACTIVE"));

        // 单段验收后可用区间仅释放本段
        mockMvc.perform(get("/api/controlled-runways/RW-H2/available-areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable.length()").value(1))
                .andExpect(jsonPath("$.unavailable[0].startMeters").value(600))
                .andExpect(jsonPath("$.availableTotalMeters").value(700));

        mockMvc.perform(get("/api/construction-orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.segments[0].acceptanceCount").value(2))
                .andExpect(jsonPath("$.segments[1].status").value("LOCKED"));

        mockMvc.perform(get("/api/construction-orders")
                        .param("runwayId", "RW-H2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/construction-orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONSTRUCTION_ORDER_NOT_FOUND"));
    }

    @Test
    void withdrawEndpointOnlyWhenPending() throws Exception {
        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RunwayRegisterRequest("h-reg-c", "RW-H3", 800))))
                .andExpect(status().isCreated());
        String created = mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-co-w", "RW-H3",
                                List.of(new ConstructionSegmentInput(1, 0, 100))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(post("/api/construction-orders/{id}/withdraw", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionWithdrawRequest("h-wd", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        // 已撤回单据不受理验收凭据
        mockMvc.perform(post("/api/construction-orders/{id}/segments/1/acceptances", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AcceptanceRequest("h-ac-w", "inspector-A", "CLR-A",
                                T0.plus(Duration.ofMinutes(30))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSTRUCTION_ORDER_WITHDRAWN"));
    }
}
