package com.example.starter.quarantine;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 层测试：验证状态码语义（200/400/404/409/422）与申报→出证→启运→落地报告全流程。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:quarantine_web_test;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class QuarantineApiTest extends AbstractQuarantineIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        // 登记可售存栏（合成数据）。
        mvc.perform(post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content("""
                {"farmId":"F-HTTP","species":"PIG","earTagRange":"TAG-H","quantity":10}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sellableQty").value(10));

        // 申报。
        mvc.perform(post("/api/quarantine/declarations").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"D-HTTP","farmId":"F-HTTP","destination":"DEST-HTTP",
                         "species":"PIG","quantity":4,"earTagRange":"TAG-H"}
                        """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLARED"));

        // 出证：核减存栏并生成证号。
        String certNo = mvc.perform(post("/api/quarantine/certificates")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"I-HTTP","declarationRequestId":"D-HTTP"}
                        """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"certNo\":\"", "").replaceAll("\".*", "");
        mvc.perform(get("/api/stocks")
                .param("farmId", "F-HTTP").param("species", "PIG").param("earTagRange", "TAG-H"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellableQty").value(6));

        // 启运。
        mvc.perform(post("/api/quarantine/departures").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"DEP-HTTP","certNo":"%s"}
                        """.formatted(certNo))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 落地报告：足额到达即结案。
        mvc.perform(post("/api/quarantine/arrivals").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"A-HTTP","certNo":"%s","quantity":4}
                        """.formatted(certNo))).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED_FINAL"))
                .andExpect(jsonPath("$.certStatus").value("CLOSED"));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        mvc.perform(post("/api/quarantine/declarations").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"D-HTTP-409","farmId":"F-HTTP","destination":"DEST-1",
                         "species":"PIG","quantity":4,"earTagRange":"TAG-H"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/quarantine/declarations").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"D-HTTP-409","farmId":"F-HTTP","destination":"DEST-2",
                         "species":"PIG","quantity":4,"earTagRange":"TAG-H"}
                        """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void missingCertReturns404() throws Exception {
        mvc.perform(get("/api/quarantine/certificates/AQ99999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mvc.perform(post("/api/quarantine/declarations").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"","farmId":"F-HTTP","destination":"DEST-1",
                         "species":"PIG","quantity":0,"earTagRange":"TAG-H"}
                        """)).andExpect(status().isBadRequest());
    }

    @Test
    void transitFlowOverHttp() throws Exception {
        // 过境申报（给定时限 2 小时）。
        mvc.perform(post("/api/quarantine/declarations").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"D-HTTP-TR","farmId":"F-HTTP-TR","destination":"DEST-PORT",
                         "species":"PIG","quantity":4,"earTagRange":"TAG-TR",
                         "transportType":"TRANSIT","transitHours":2}
                        """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.transportType").value("TRANSIT"))
                .andExpect(jsonPath("$.transitHours").value(2));

        // 出证：证书标注过境属性。
        mvc.perform(post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content("""
                {"farmId":"F-HTTP-TR","species":"PIG","earTagRange":"TAG-TR","quantity":10}
                """)).andExpect(status().isOk());
        String certNo = mvc.perform(post("/api/quarantine/certificates")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"I-HTTP-TR","declarationRequestId":"D-HTTP-TR"}
                        """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.transportType").value("TRANSIT"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"certNo\":\"", "").replaceAll("\".*", "");

        // 过境证用于本地流向登记 → 422 拦截。
        mvc.perform(post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content("""
                {"farmId":"F-HTTP-TR-IN","species":"PIG","earTagRange":"TAG-TR","quantity":4,
                 "certNo":"%s"}
                """.formatted(certNo))).andExpect(status().isUnprocessableEntity());

        // 启运后途经合成疫区抵达核销：加强监测标记置真。
        mvc.perform(post("/api/quarantine/departures").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"DEP-HTTP-TR","certNo":"%s"}
                        """.formatted(certNo))).andExpect(status().isOk())
                .andExpect(jsonPath("$.transitDeadline").exists());
        mvc.perform(post("/api/quarantine/arrivals").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"A-HTTP-TR","certNo":"%s","quantity":4,
                         "viaRegions":["SYNTH-EPICENTER-ALPHA"]}
                        """.formatted(certNo))).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED_FINAL"))
                .andExpect(jsonPath("$.enhancedMonitoring").value(true));

        // 轨迹查询：证书 + 抵达记录 + 预警列表。
        mvc.perform(get("/api/quarantine/certificates/{certNo}/trajectory", certNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.certNo").value(certNo))
                .andExpect(jsonPath("$.arrivals[0].result").value("ACCEPTED_FINAL"))
                .andExpect(jsonPath("$.alerts").isArray());

        // 超时扫描端点可用。
        mvc.perform(post("/api/quarantine/transit-alerts/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").isNumber());
    }

    @Test
    void issueOnLockedFarmReturns422() throws Exception {
        mvc.perform(post("/api/quarantine/declarations").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"D-HTTP-LK","farmId":"F-HTTP-LK","destination":"DEST-1",
                         "species":"PIG","quantity":4,"earTagRange":"TAG-L"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/quarantine/lockdowns").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"LK-HTTP","farmId":"F-HTTP-LK","reason":"疫点"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/quarantine/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"I-HTTP-LK","declarationRequestId":"D-HTTP-LK"}
                        """)).andExpect(status().isUnprocessableEntity());
    }
}
