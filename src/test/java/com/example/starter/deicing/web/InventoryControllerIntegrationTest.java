package com.example.starter.deicing.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 库存接口 HTTP 契约测试：入库、借用幂等/409、归还、台账与人工确认队列。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryControllerIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.example.starter.deicing.service.TimeSource timeSource;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        jdbcTemplate.update("DELETE FROM fluid_reservation");
        jdbcTemplate.update("DELETE FROM fluid_consumption");
        jdbcTemplate.update("DELETE FROM fluid_borrow");
        jdbcTemplate.update("DELETE FROM manual_review_queue");
        jdbcTemplate.update("DELETE FROM fluid_batch WHERE source = 'INBOUND'");
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = quantity_liters");
        jdbcTemplate.update("UPDATE fluid_batch SET expiry_time = '2027-12-31 00:00:00'");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void inboundBorrowReturnAndLedgerFlow() throws Exception {
        // 补料入库 201
        mockMvc.perform(post("/api/deicing/inventory/inbounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"BATCH-W1","fluidCode":"TYPE-II",
                                 "quantityLiters":500,"expiryTime":"%s"}
                                """.formatted(T0.plus(Duration.ofDays(30)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchNo").value("BATCH-W1"));

        // 借用 201 → 同键同参 200 重放 → 同键异参 409
        String borrow = """
                {"requestId":"BOR-W1","fluidCode":"TYPE-II","tankCode":"TANK-1",
                 "quantityLiters":100,"fromTeam":"TEAM-A","toTeam":"TEAM-B"}
                """;
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON).content(borrow))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.item.status").value("OPEN"));
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON).content(borrow))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"BOR-W1","fluidCode":"TYPE-II","tankCode":"TANK-1",
                                 "quantityLiters":200,"fromTeam":"TEAM-A","toTeam":"TEAM-B"}
                                """))
                .andExpect(status().isConflict());

        // 归还登记 200，重复归还幂等
        mockMvc.perform(post("/api/deicing/inventory/borrow-returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"BOR-W1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("RETURNED"));

        // 台账可复算：期初 10000 + 入库 500，借出已归还
        mockMvc.perform(get("/api/deicing/inventory/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fluids[1].fluidCode").value("TYPE-II"))
                .andExpect(jsonPath("$.fluids[1].openingLiters").value(10000.00))
                .andExpect(jsonPath("$.fluids[1].inboundLiters").value(500.00))
                .andExpect(jsonPath("$.fluids[1].borrowedLiters").value(0.00))
                .andExpect(jsonPath("$.fluids[1].currentLiters").value(10500.00));
    }

    @Test
    void insufficientTwoStepAppearsInManualReviewQueue() throws Exception {
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = 100"
                + " WHERE batch_no = 'OPENING-TYPE-II'");

        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-W9","flightNumber":"CA101","standCode":"STAND-CA101",
                                 "type":"TWO_STEP","requiredBays":1,"durationMinutes":10,
                                 "targetTakeoffTime":"%s"}
                                """.formatted(T0.plus(Duration.ofMinutes(60)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("人工确认")));

        mockMvc.perform(get("/api/deicing/inventory/manual-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("REQ-W9"))
                .andExpect(jsonPath("$[0].reason").value("INSUFFICIENT_FLUID"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void borrowValidationErrorReturns400() throws Exception {
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","fluidCode":"TYPE-I","tankCode":"TANK-1",
                                 "quantityLiters":-5,"fromTeam":"TEAM-A","toTeam":"TEAM-B"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
