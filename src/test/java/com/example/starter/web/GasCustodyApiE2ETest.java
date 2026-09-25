package com.example.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.StarterApplication;
import com.example.starter.support.ControllableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.dto.NominationRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.StationRequest;
import com.example.starter.web.dto.VoucherSubmitRequest;
import com.example.starter.service.GasCustodyService;
import com.example.starter.web.dto.ConfirmRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 真实 HTTP 入口端到端合成示例：读数入库（乱序）→凭证生成→双方确认→偏差考核。
 * 使用 MockMvc 驱动完整 Spring MVC 栈（含参数校验、事务、H2），非仅上下文启动断言。
 */
@SpringBootTest(classes = StarterApplication.class)
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class GasCustodyApiE2ETest {

    private static final String STATION = "ST-E2E";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GasCustodyService service;
    @Autowired
    private ControllableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reading_difference");
        jdbc.update("DELETE FROM dispute");
        jdbc.update("DELETE FROM voucher");
        jdbc.update("DELETE FROM meter_reading");
        jdbc.update("DELETE FROM daily_nomination");
        jdbc.update("DELETE FROM station");
        clock.setInstant(Instant.parse("2026-09-11T01:00:00Z"));
    }

    @Test
    void fullFlowIngestVoucherConfirmAssess() throws Exception {
        // 1. 建站
        mvc.perform(post("/api/gas/stations").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new StationRequest(STATION, "端到端站", "甲方", "乙方"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationCode").value(STATION));

        // 2. 日指定能量 8000 MJ，违约金单价 0.5 元/MJ（实际恰免考）
        mvc.perform(post("/api/gas/nominations").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new NominationRequest(STATION, DAY, new BigDecimal("8000"),
                                new BigDecimal("0.5")))))
                .andExpect(status().isCreated());

        // 3. 读数乱序入库：先 23:00 后 00:00
        mvc.perform(post("/api/gas/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ReadingRequest(STATION, "M1",
                                Instant.parse("2026-09-10T23:00:00Z"), new BigDecimal("1200.000"),
                                new BigDecimal("40.0000")))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/gas/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ReadingRequest(STATION, "M1",
                                Instant.parse("2026-09-10T00:00:00Z"), new BigDecimal("1000.000"),
                                new BigDecimal("38.0000")))))
                .andExpect(status().isCreated());

        // 4. 归位查询：00:00 排在前
        mvc.perform(get("/api/gas/stations/{code}/readings", STATION).param("gasDay", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].readingTime").value("2026-09-10T00:00:00Z"))
                .andExpect(jsonPath("$[1].readingTime").value("2026-09-10T23:00:00Z"));

        // 5. 生成凭证：体积 200，热值 40，能量 8000.00，DRAFT
        String voucherNo = mvc.perform(post("/api/gas/vouchers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoucherSubmitRequest("e2e-submit", STATION, DAY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.volume").value(200.000))
                .andExpect(jsonPath("$.avgHeatingValue").value(40.0000))
                .andExpect(jsonPath("$.energyMj").value(8000.00))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String no = objectMapper.readTree(voucherNo).get("voucherNo").asText();

        // 6. 仅 A 确认仍 DRAFT
        mvc.perform(post("/api/gas/vouchers/confirmations").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConfirmRequest("e2e-conf-a", no, "A", "张三"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.partyAConfirmed").value(true))
                .andExpect(jsonPath("$.partyBConfirmed").value(false));

        // 7. B 再确认 → EFFECTIVE，争议截止 2026-09-18
        mvc.perform(post("/api/gas/vouchers/confirmations").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConfirmRequest("e2e-conf-b", no, "B", "李四"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.disputeDeadline").value("2026-09-18"));

        // 8. 偏差考核：实际 8000 vs 指定 8000，恰 0 偏差免考
        mvc.perform(get("/api/gas/stations/{code}/assessments", STATION)
                        .param("from", DAY.toString()).param("to", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exempt").value(true))
                .andExpect(jsonPath("$[0].penaltyAmount").value(0.00))
                .andExpect(jsonPath("$[0].voucherNo").value(no));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
