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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 施工段闭锁与可用区域查询的 HTTP 层测试：状态码、错误报文与幂等语义的端到端验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConstructionControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM clearance_credential");
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
                + " VALUES (1, ?, NULL, ?)", java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** 端点主流程：登记跑道→施工单→生效→可用区间→双验收→单结束→全可用。 */
    @Test
    void endpointMainFlow() throws Exception {
        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRunwayRequest("h-reg", "RWH", 1000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lengthMeters").value(1000))
                .andExpect(jsonPath("$.version").value(1));

        String orderBody = mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-co", "RWH",
                                List.of(new SegmentInput(1, 100, 400), new SegmentInput(2, 600, 700))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(orderBody).get("orderId").asLong();

        mockMvc.perform(post("/api/construction-orders/{id}/activate", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new OrderActionRequest("h-act", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.version").value(2));

        // 可用区间：[0,100) + [400,600) + [700,1000)
        mockMvc.perform(get("/api/controlled-runways/{runwayId}/usable-segments", "RWH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usableSegments.length()").value(3))
                .andExpect(jsonPath("$.usableSegments[0].startMeter").value(0))
                .andExpect(jsonPath("$.usableSegments[0].endMeter").value(100))
                .andExpect(jsonPath("$.usableSegments[1].startMeter").value(400))
                .andExpect(jsonPath("$.usableSegments[1].endMeter").value(600))
                .andExpect(jsonPath("$.totalUsableMeters").value(600));

        // 段1双验收人 → CLEARED；段2未动
        mockMvc.perform(post("/api/construction-orders/{id}/segments/{no}/clearances", orderId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ClearanceRequest("h-c1", "acc-A", "CR-1",
                                T0.plus(Duration.ofHours(1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segmentStatus").value("LOCKED"))
                .andExpect(jsonPath("$.distinctAcceptors").value(1));
        mockMvc.perform(post("/api/construction-orders/{id}/segments/{no}/clearances", orderId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ClearanceRequest("h-c2", "acc-B", "CR-2",
                                T0.plus(Duration.ofHours(1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segmentStatus").value("CLEARED"))
                .andExpect(jsonPath("$.orderStatus").value("EFFECTIVE"));

        // 段2双验收人 → 单结束
        mockMvc.perform(post("/api/construction-orders/{id}/segments/{no}/clearances", orderId, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ClearanceRequest("h-c3", "acc-C", "CR-3",
                                T0.plus(Duration.ofHours(1))))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/construction-orders/{id}/segments/{no}/clearances", orderId, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ClearanceRequest("h-c4", "acc-D", "CR-4",
                                T0.plus(Duration.ofHours(1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

        mockMvc.perform(get("/api/controlled-runways/{runwayId}/usable-segments", "RWH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usableSegments.length()").value(1))
                .andExpect(jsonPath("$.totalUsableMeters").value(1000));
    }

    /** 端点失败分支：参数校验400、重叠422、未受控跑道404、版本冲突409、幂等异参409。 */
    @Test
    void endpointFailureBranches() throws Exception {
        // 非法长度：400（Bean Validation）
        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRunwayRequest("h-bad", "RHX", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRunwayRequest("h-reg2", "RHX", 1000))))
                .andExpect(status().isCreated());

        // 单内重叠：422
        mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-ov", "RHX",
                                List.of(new SegmentInput(1, 100, 300), new SegmentInput(2, 200, 400))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEGMENT_OVERLAP"));

        // 未受控跑道：404
        mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-nc", "RHX-NONE",
                                List.of(new SegmentInput(1, 0, 100))))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUNWAY_NOT_CONTROLLED"));

        // 长度修改旧版本：409
        mockMvc.perform(put("/api/controlled-runways/{runwayId}", "RHX")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateRunwayLengthRequest("h-ver", 5, 1200))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNWAY_VERSION_CONFLICT"));

        // 幂等异参：409
        mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-idem", "RHX",
                                List.of(new SegmentInput(1, 100, 200))))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-idem", "RHX",
                                List.of(new SegmentInput(1, 100, 300))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    /** 端点：未受控跑道可用查询404；生效单缩改长度422。 */
    @Test
    void endpointUsableAndShrinkRules() throws Exception {
        mockMvc.perform(get("/api/controlled-runways/{runwayId}/usable-segments", "RHZ-NONE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUNWAY_NOT_CONTROLLED"));

        mockMvc.perform(post("/api/controlled-runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRunwayRequest("h-reg3", "RHZ", 1000))))
                .andExpect(status().isCreated());
        String orderBody = mockMvc.perform(post("/api/construction-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConstructionOrderCreateRequest("h-co3", "RHZ",
                                List.of(new SegmentInput(1, 0, 500))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(orderBody).get("orderId").asLong();
        mockMvc.perform(post("/api/construction-orders/{id}/activate", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new OrderActionRequest("h-act3", 1))))
                .andExpect(status().isOk());

        // 存在活动施工：缩改长度422
        mockMvc.perform(put("/api/controlled-runways/{runwayId}", "RHZ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateRunwayLengthRequest("h-sh", 1, 800))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RUNWAY_SHRINK_BLOCKED"));
    }
}
