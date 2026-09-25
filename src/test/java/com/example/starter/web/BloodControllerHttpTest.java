package com.example.starter.web;

import com.example.starter.ClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端测试：经真实 Spring MVC 链路验证状态码、JSON 字段与完整业务流程。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:blood_release_web_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always"
        })
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class BloodControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM blood_issue");
        jdbcTemplate.update("DELETE FROM release_order");
        jdbcTemplate.update("DELETE FROM test_result_event");
        jdbcTemplate.update("DELETE FROM blood_unit");
    }

    @Test
    void fullFlow_intake_twoTests_release_andExpiry() throws Exception {
        // 1. 入库
        mockMvc.perform(post("/api/blood-units/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"W0001","componentType":"RED_CELL",
                                 "expiresAt":"2026-09-30T15:59:59Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 重复入库 -> 409
        mockMvc.perform(post("/api/blood-units/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"W0001","componentType":"RED_CELL",
                                 "expiresAt":"2026-09-30T15:59:59Z"}
                                """))
                .andExpect(status().isConflict());

        // 2a. 复检先回传（乱序）-> 隔离
        mockMvc.perform(post("/api/blood-units/test-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"W0001","testType":"REPEAT","result":"PASS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        // 2b. 初检合格 -> 可放行
        mockMvc.perform(post("/api/blood-units/test-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"W0001","testType":"INITIAL","result":"PASS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASABLE"));

        // 3. 放行发血成功
        mockMvc.perform(post("/api/blood-units/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"WREQ-1","barcode":"W0001",
                                 "windowStart":"2026-09-01T00:00:00Z",
                                 "windowEnd":"2026-10-10T00:00:00Z",
                                 "requestTime":"2026-09-25T11:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.unitStatus").value("RELEASED"));

        // 同键同参重放 -> 首次结果，replay=true
        mockMvc.perform(post("/api/blood-units/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"WREQ-1","barcode":"W0001",
                                 "windowStart":"2026-09-01T00:00:00Z",
                                 "windowEnd":"2026-10-10T00:00:00Z",
                                 "requestTime":"2026-09-25T11:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.replay").value(true));

        // 同键异参 -> 409
        mockMvc.perform(post("/api/blood-units/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"WREQ-1","barcode":"W0001",
                                 "windowStart":"2026-09-01T00:00:00Z",
                                 "windowEnd":"2026-10-11T00:00:00Z",
                                 "requestTime":"2026-09-25T11:05:00Z"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/blood-units/W0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void release_expiredUnit_rejectedAndMarkedExpired() throws Exception {
        mockMvc.perform(post("/api/blood-units/intake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"barcode":"W0002","componentType":"PLASMA",
                         "expiresAt":"2026-09-30T15:59:59Z"}
                        """)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/blood-units/test-results")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"barcode":"W0002","testType":"INITIAL","result":"PASS"}
                        """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/blood-units/test-results")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"barcode":"W0002","testType":"REPEAT","result":"PASS"}
                        """)).andExpect(status().isOk());

        // 效期边界之后 1 秒请求放行 -> 拒绝并过期报废
        mockMvc.perform(post("/api/blood-units/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"WREQ-2","barcode":"W0002",
                                 "windowStart":"2026-09-01T00:00:00Z",
                                 "windowEnd":"2026-10-10T00:00:00Z",
                                 "requestTime":"2026-09-30T16:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("EXPIRED"))
                .andExpect(jsonPath("$.unitStatus").value("EXPIRED"));

        mockMvc.perform(get("/api/blood-units/W0002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.scrapReason").value("EXPIRED"));
    }

    @Test
    void release_unknownBarcode_404_andInvalidRequest_400() throws Exception {
        mockMvc.perform(post("/api/blood-units/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"X","barcode":"GHOST",
                                 "windowStart":"2026-09-01T00:00:00Z",
                                 "windowEnd":"2026-10-10T00:00:00Z"}
                                """))
                .andExpect(status().isNotFound());

        MvcResult invalid = mockMvc.perform(post("/api/blood-units/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"","componentType":"RED_CELL",
                                 "expiresAt":"2026-09-30T15:59:59Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(invalid.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED");
    }

    @Test
    void failedTest_scrapsAndLatePassDoesNotRevive() throws Exception {
        mockMvc.perform(post("/api/blood-units/intake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"barcode":"W0003","componentType":"PLATELET",
                         "expiresAt":"2026-09-30T15:59:59Z"}
                        """)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/blood-units/test-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"W0003","testType":"INITIAL","result":"FAIL"}
                                """))
                .andExpect(jsonPath("$.status").value("SCRAPPED"))
                .andExpect(jsonPath("$.scrapReason").value("TEST_FAILED"));

        // 终态后合格回传不复活
        mockMvc.perform(post("/api/blood-units/test-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"barcode":"W0003","testType":"REPEAT","result":"PASS"}
                                """))
                .andExpect(jsonPath("$.status").value("SCRAPPED"))
                .andExpect(jsonPath("$.repeatResult").doesNotExist());
    }
}
