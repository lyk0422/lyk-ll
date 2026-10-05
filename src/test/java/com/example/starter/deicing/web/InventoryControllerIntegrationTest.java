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
 * 库存与配比管理 HTTP 契约测试：入库、台账、借用/归还幂等、液罐唯一、
 * 作业完成结算与人工确认队列。
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
        jdbcTemplate.update("DELETE FROM inventory_consumption");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM tank_borrow_open");
        jdbcTemplate.update("DELETE FROM borrow_order");
        jdbcTemplate.update("DELETE FROM fluid_batch");
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES ('BATCH-T1-SEED', 'TYPE_I', 100000, 100000, '2027-06-30 00:00:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES ('BATCH-T4-SEED', 'TYPE_IV', 100000, 100000, '2027-06-30 00:00:00')
                """);
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private String twoStepJson(String requestId, String flight, int duration, String takeoff) {
        return """
                {"requestId":"%s","flightNumber":"%s","standCode":"STAND-%s",
                 "type":"TWO_STEP","requiredBays":1,"durationMinutes":%d,
                 "targetTakeoffTime":"%s"}
                """.formatted(requestId, flight, flight, duration, takeoff);
    }

    @Test
    void inboundRegistersBatchAndDuplicateBatchNoConflicts() throws Exception {
        String body = """
                {"batchNo":"BATCH-X1","fluidCode":"TYPE_I","quantity":500,
                 "expiryTime":"2027-01-31T00:00:00Z"}
                """;
        mockMvc.perform(post("/api/deicing/inventory/inbounds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchNo").value("BATCH-X1"))
                .andExpect(jsonPath("$.quantityIn").value(500))
                .andExpect(jsonPath("$.quantityAvailable").value(500));

        // 重复批次号 → 409
        mockMvc.perform(post("/api/deicing/inventory/inbounds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // 未知除冰液 → 400
        mockMvc.perform(post("/api/deicing/inventory/inbounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"BATCH-X2","fluidCode":"NOPE","quantity":10,
                                 "expiryTime":"2027-01-31T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void borrowEndpointsEnforceIdempotencyAndTankUniqueness() throws Exception {
        String borrow = """
                {"requestId":"BOR-W1","tankId":"TANK-1","fluidCode":"TYPE_I",
                 "quantity":50,"fromTeam":"TEAM-A","toTeam":"TEAM-B"}
                """;
        // 首次 201
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON).content(borrow))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.order.status").value("OPEN"));
        // 同键同参重放 200
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON).content(borrow))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
        // 同键异参 409
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"BOR-W1","tankId":"TANK-1","fluidCode":"TYPE_I",
                                 "quantity":60,"fromTeam":"TEAM-A","toTeam":"TEAM-B"}
                                """))
                .andExpect(status().isConflict());
        // 同一液罐第二张未归还借用单 409
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"BOR-W2","tankId":"TANK-1","fluidCode":"TYPE_I",
                                 "quantity":10,"fromTeam":"TEAM-A","toTeam":"TEAM-C"}
                                """))
                .andExpect(status().isConflict());

        // 归还登记后同液罐可再借；重复归还幂等
        mockMvc.perform(post("/api/deicing/inventory/borrows/return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"BOR-W1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("RETURNED"))
                .andExpect(jsonPath("$.order.returnedAt").exists());
        mockMvc.perform(post("/api/deicing/inventory/borrows/return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"BOR-W1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
        mockMvc.perform(post("/api/deicing/inventory/borrows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"BOR-W3","tankId":"TANK-1","fluidCode":"TYPE_I",
                                 "quantity":10,"fromTeam":"TEAM-A","toTeam":"TEAM-D"}
                                """))
                .andExpect(status().isCreated());

        // 台账：借出量 = 未归还的 10 升，现存 = 入库 - 消耗 - 借出
        mockMvc.perform(get("/api/deicing/inventory/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fluids[0].fluidCode").value("TYPE_I"))
                .andExpect(jsonPath("$.fluids[0].lentOutLiters").value(10))
                .andExpect(jsonPath("$.fluids[0].currentLiters").value(99990));
    }

    @Test
    void twoStepApplyReserveAndOneStepSettleViaHttp() throws Exception {
        // 两步法申请受理即预占：10 分钟 × 6 升 = 60 升 TYPE_I、30 升 TYPE_IV
        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoStepJson("REQ-W2", "CA101", 10,
                                T0.plus(Duration.ofMinutes(60)).toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.status").value("QUEUED"));
        mockMvc.perform(get("/api/deicing/inventory/ledger"))
                .andExpect(jsonPath("$.fluids[0].activeReservedLiters").value(60))
                .andExpect(jsonPath("$.fluids[1].activeReservedLiters").value(30));

        // 出队、开始、完成：预占转消耗
        mockMvc.perform(post("/api/deicing/dequeue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-W2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/deicing/jobs/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-W2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/deicing/jobs/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-W2","actualEndTime":"2026-09-25T10:10:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.item.actualEnd").value("2026-09-25T10:10:00Z"));
        mockMvc.perform(get("/api/deicing/inventory/ledger"))
                .andExpect(jsonPath("$.fluids[0].consumedLiters").value(60))
                .andExpect(jsonPath("$.fluids[0].activeReservedLiters").value(0))
                .andExpect(jsonPath("$.fluids[0].currentLiters").value(99940))
                .andExpect(jsonPath("$.fluids[1].consumedLiters").value(30));
    }

    @Test
    void insufficientStockGoesToManualReviewAndConfirmRecovers() throws Exception {
        // 把 TYPE_I 库存调到仅 50 升：两步法需 60 升 → 人工确认队列
        jdbcTemplate.update("DELETE FROM fluid_batch");
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES ('BATCH-T1-LOW', 'TYPE_I', 50, 50, '2027-06-30 00:00:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES ('BATCH-T4-SEED', 'TYPE_IV', 100000, 100000, '2027-06-30 00:00:00')
                """);

        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoStepJson("REQ-W3", "CA101", 10,
                                T0.plus(Duration.ofMinutes(60)).toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.status").value("MANUAL_REVIEW"));

        // 库存不足时人工确认 409，且不得出队
        mockMvc.perform(post("/api/deicing/manual-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-W3\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/deicing/dequeue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-W3\"}"))
                .andExpect(status().isConflict());

        // 补料入库后确认成功并出队
        mockMvc.perform(post("/api/deicing/inventory/inbounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"BATCH-T1-ADD","fluidCode":"TYPE_I","quantity":100,
                                 "expiryTime":"2027-06-30T00:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/deicing/manual-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-W3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("QUEUED"));
        mockMvc.perform(post("/api/deicing/dequeue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-W3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("SCHEDULED"));
    }
}
