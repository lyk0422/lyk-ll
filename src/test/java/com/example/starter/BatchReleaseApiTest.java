package com.example.starter;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批签发 HTTP 接口冒烟测试：申报→补正→综合评定→公示签发真实入口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BatchReleaseApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM late_document_event");
        jdbc.update("DELETE FROM enterprise_suspension");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM publicity_record");
        jdbc.update("DELETE FROM inspection_item");
        jdbc.update("DELETE FROM document_item");
        jdbc.update("DELETE FROM review_process");
        jdbc.update("DELETE FROM vaccine_batch");
        clock.set(TestClockConfig.BASE_TIME);
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        // 申报：缺 SUMMARY，一次性告知缺项
        mockMvc.perform(post("/api/batch-release/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "req-http-1",
                                  "batchNo": "B2026200",
                                  "enterprise": "合成企业C",
                                  "productName": "合成疫苗Z",
                                  "documents": [
                                    {"itemCode": "LICENSE"},
                                    {"itemCode": "QUALITY_STD"},
                                    {"itemCode": "SELF_TEST_REPORT"},
                                    {"itemCode": "PROCESS_RECORD"}
                                  ],
                                  "inspections": [
                                    {"itemCode": "POTENCY", "limitValue": 10, "measuredValue": 8},
                                    {"itemCode": "STERILITY", "limitValue": 5, "measuredValue": 5},
                                    {"itemCode": "ABNORMAL_TOXICITY", "limitValue": 2, "measuredValue": 1}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CORRECTION"))
                .andExpect(jsonPath("$.missing[0].itemCode").value("SUMMARY"))
                .andExpect(jsonPath("$.correctionDeadline").value("2026-10-02"));

        // 同键重放：返回首次结果
        mockMvc.perform(post("/api/batch-release/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "req-http-1",
                                  "batchNo": "B2026200",
                                  "enterprise": "合成企业C",
                                  "productName": "合成疫苗Z",
                                  "documents": [
                                    {"itemCode": "LICENSE"},
                                    {"itemCode": "QUALITY_STD"},
                                    {"itemCode": "SELF_TEST_REPORT"},
                                    {"itemCode": "PROCESS_RECORD"}
                                  ],
                                  "inspections": [
                                    {"itemCode": "POTENCY", "limitValue": 10, "measuredValue": 8},
                                    {"itemCode": "STERILITY", "limitValue": 5, "measuredValue": 5},
                                    {"itemCode": "ABNORMAL_TOXICITY", "limitValue": 2, "measuredValue": 1}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CORRECTION"));

        // 补正：补齐缺项
        mockMvc.perform(post("/api/batch-release/batches/B2026200/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "req-http-2", "documents": [{"itemCode": "SUMMARY"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        // 综合评定：关键项目全项合格，进入公示
        mockMvc.perform(post("/api/batch-release/batches/B2026200/evaluation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "req-http-3"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluationResult").value("PASS"))
                .andExpect(jsonPath("$.status").value("IN_PUBLICITY"));

        // 公示恰满7个自然日后签发
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)));
        mockMvc.perform(post("/api/batch-release/batches/B2026200/issuance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "req-http-4"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        // 资料核对表复算：无缺项
        mockMvc.perform(get("/api/batch-release/batches/B2026200/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").isEmpty());

        // 公示记录复算：期满生效
        mockMvc.perform(get("/api/batch-release/batches/B2026200/publicity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicityStatus").value("EFFECTIVE"))
                .andExpect(jsonPath("$.expired").value(true));
    }

    @Test
    void sameKeyDifferentParamsOverHttpReturns409() throws Exception {
        String body = """
                {
                  "requestId": "req-http-9",
                  "batchNo": "B2026201",
                  "enterprise": "合成企业C",
                  "productName": "合成疫苗Z",
                  "documents": [
                    {"itemCode": "LICENSE"},
                    {"itemCode": "QUALITY_STD"},
                    {"itemCode": "SELF_TEST_REPORT"},
                    {"itemCode": "PROCESS_RECORD"},
                    {"itemCode": "SUMMARY"}
                  ],
                  "inspections": [
                    {"itemCode": "POTENCY", "limitValue": 10, "measuredValue": 8},
                    {"itemCode": "STERILITY", "limitValue": 5, "measuredValue": 5},
                    {"itemCode": "ABNORMAL_TOXICITY", "limitValue": 2, "measuredValue": 1}
                  ]
                }
                """;
        mockMvc.perform(post("/api/batch-release/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // 同键异参：409
        mockMvc.perform(post("/api/batch-release/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("B2026201", "B2026299")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }
}
