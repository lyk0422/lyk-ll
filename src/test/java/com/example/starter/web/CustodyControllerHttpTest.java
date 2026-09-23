package com.example.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 通过真实 HTTP 入口验证状态码语义：201 入库、404 不存在、400 参数非法、409 冲突，
 * 以及入库→分装→占用→取消的合成主流程与账本响应。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustodyControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE custody_event");
        jdbc.execute("TRUNCATE TABLE idempotent_request");
        jdbc.execute("TRUNCATE TABLE reservation");
        jdbc.execute("TRUNCATE TABLE aliquot");
        jdbc.execute("TRUNCATE TABLE specimen");
    }

    @Test
    void fullFlowOverHttpReturnsExpectedStatusesAndLedger() throws Exception {
        // 入库
        mockMvc.perform(post("/api/custody/specimens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"S1","volumeUl":"10.000","custodian":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingUl").value("10"))
                .andExpect(jsonPath("$.version").value(0));

        // 重复 specimenId → 409
        mockMvc.perform(post("/api/custody/specimens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"S1","volumeUl":"10.000","custodian":"alice"}
                                """))
                .andExpect(status().isConflict());

        // 分装 6 + 3
        mockMvc.perform(post("/api/custody/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"S1","requestId":"REQ-SP","expectedVersion":0,
                                 "aliquots":[{"aliquotId":"A1","volumeUl":"6.000"},
                                             {"aliquotId":"A2","volumeUl":"3.000"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specimen.remainingUl").value("1"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 分装超额整批 400
        mockMvc.perform(post("/api/custody/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"S1","requestId":"REQ-SP-BAD","expectedVersion":1,
                                 "aliquots":[{"aliquotId":"A9","volumeUl":"9.000"}]}
                                """))
                .andExpect(status().isBadRequest());

        // 占用 4µL
        mockMvc.perform(post("/api/custody/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R1","aliquotId":"A1","volumeUl":"4.000",
                                 "projectCode":"GLU","expectedVersion":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        // 超额占用 → 409（剩余可用 2µL）
        mockMvc.perform(post("/api/custody/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R2","aliquotId":"A1","volumeUl":"3.000",
                                 "projectCode":"GLU","expectedVersion":0}
                                """))
                .andExpect(status().isConflict());

        // 取消占用
        mockMvc.perform(post("/api/custody/reservations/REQ-R1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 账本：A1 可用恢复为 6
        mockMvc.perform(get("/api/custody/specimens/S1/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specimen.remainingUl").value("1"))
                .andExpect(jsonPath("$.aliquots[0].availableUl").value("6"))
                .andExpect(jsonPath("$.reservations[0].status").value("CANCELLED"));

        // 不存在资源 → 404
        mockMvc.perform(get("/api/custody/specimens/NOPE"))
                .andExpect(status().isNotFound());

        // Bean 校验失败 → 400
        mockMvc.perform(post("/api/custody/specimens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"","volumeUl":"1","custodian":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lateResultAfterCancelReturns409OverHttp() throws Exception {
        mockMvc.perform(post("/api/custody/specimens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"S2","volumeUl":"5.000","custodian":"alice"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/custody/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specimenId":"S2","requestId":"REQ-SP2","expectedVersion":0,
                                 "aliquots":[{"aliquotId":"B1","volumeUl":"5.000"}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/custody/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-Q","aliquotId":"B1","volumeUl":"2.000",
                                 "projectCode":"P","expectedVersion":0}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/custody/reservations/REQ-Q/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/custody/reservations/REQ-Q/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"result\":{\"v\":1}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("迟到结果")));
    }
}
