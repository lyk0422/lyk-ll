package com.example.starter.spectrum;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 契约测试（H2 + 完整 Spring 上下文 + MockMvc，不监听端口）：
 * 验证创建、冲突 409 报告体、幂等指纹 409、参数 400 与活动快照接口。
 */
@AutoConfigureMockMvc
class LeaseControllerTest extends AbstractSpectrumIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final long T = T0;

    private String sharedLeaseJson(String requestId, double eirp, String cell) {
        return """
                {
                  "requestId": "%s",
                  "startTime": %d,
                  "endTime": %d,
                  "freqLowMhz": 100.0,
                  "freqHighMhz": 200.0,
                  "mode": "SHARED",
                  "eirpMw": %s,
                  "cells": ["%s"]
                }
                """.formatted(requestId, T, T + 1000, String.valueOf(eirp), cell);
    }

    @Test
    void createReturns200WithLeaseBody() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLeaseJson("web-1", 500, "CELL-A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").isNumber())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mode").value("SHARED"))
                .andExpect(jsonPath("$.eirpMw").value(500.0))
                .andExpect(jsonPath("$.cells[0]").value("CELL-A"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
    }

    @Test
    void conflictReturns409WithReport() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "web-ex-1",
                                  "startTime": %d,
                                  "endTime": %d,
                                  "freqLowMhz": 100.0,
                                  "freqHighMhz": 200.0,
                                  "mode": "EXCLUSIVE",
                                  "eirpMw": 10,
                                  "cells": ["CELL-A"]
                                }
                                """.formatted(T, T + 1000)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLeaseJson("web-ex-2", 500, "CELL-A")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_CONFLICT"))
                .andExpect(jsonPath("$.report.compatible").value(false))
                .andExpect(jsonPath("$.report.conflicts[0].type").value("EXCLUSIVE_CONFLICT"))
                .andExpect(jsonPath("$.report.conflicts[0].cell").value("CELL-A"));
    }

    @Test
    void sameKeyDifferentParamsReturns409Idempotency() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLeaseJson("web-idem", 500, "CELL-A")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLeaseJson("web-idem", 600, "CELL-A")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "web-bad",
                                  "startTime": %d,
                                  "endTime": %d,
                                  "freqLowMhz": 200.0,
                                  "freqHighMhz": 100.0,
                                  "mode": "SHARED",
                                  "eirpMw": 500,
                                  "cells": ["CELL-A"]
                                }
                                """.formatted(T + 1000, T)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingLeaseReturns404() throws Exception {
        mockMvc.perform(get("/api/leases/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEASE_NOT_FOUND"));
    }

    @Test
    void activeSnapshotEndpointReturnsActiveLeases() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLeaseJson("web-snap", 500, "CELL-A")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/leases/active").param("at", String.valueOf(T + 500)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].cells[0]").value("CELL-A"));
        mockMvc.perform(get("/api/leases/active").param("at", String.valueOf(T + 1000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
