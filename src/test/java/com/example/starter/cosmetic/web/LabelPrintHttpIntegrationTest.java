package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.support.LabelDigestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 包装标签印次台账真实 HTTP 入口测试（MockMvc + 完整 Spring 上下文 + H2）：
 * 登记 100 张 → 领 60 张 → 备案实质变更生效 → 旧印次剩余 40 张原子禁用、拒领。
 * 全部标识为合成。
 */
class LabelPrintHttpIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    @Test
    void registerIssueThenSubstantiveChangeRejectsOldRemaining() throws Exception {
        // 1. 合成成人备案提交并期满生效
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-S",
                                  "productCode": "P-LPH-1",
                                  "productName": "合成保湿乳",
                                  "formulation": [
                                    {"code": "WATER", "name": "水", "concentrationPct": 78.0000},
                                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 5.0000}
                                  ],
                                  "claims": ["MOISTURIZING"],
                                  "reports": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));
        clock.setInstant(clock.instant().plus(11, ChronoUnit.DAYS));
        mockMvc.perform(post("/api/filings/public-notice-expiry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));

        // 2. 印次登记 100 张（引用当前有效备案，标签摘要为备案包装文字摘要）
        String digest = LabelDigestSupport.adultDigest(null);
        mockMvc.perform(post("/api/label-print-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-R",
                                  "printRunNo": "RUN-LPH-1",
                                  "productCode": "P-LPH-1",
                                  "labelDigest": "%s",
                                  "printedCount": 100
                                }
                                """.formatted(digest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printRun.status").value("ACTIVE"))
                .andExpect(jsonPath("$.printRun.printedCount").value(100))
                .andExpect(jsonPath("$.printRun.remainingCount").value(100))
                .andExpect(jsonPath("$.printRun.versionNo").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/label-print-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-R",
                                  "printRunNo": "RUN-LPH-1",
                                  "productCode": "P-LPH-1",
                                  "labelDigest": "%s",
                                  "printedCount": 100
                                }
                                """.formatted(digest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 3. 领用 60 张（合成包装批 BATCH-LPH-1）：余额 40，版本 2
        mockMvc.perform(post("/api/label-print-runs/RUN-LPH-1/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-I1",
                                  "batchNo": "BATCH-LPH-1",
                                  "quantity": 60,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.quantity").value(60))
                .andExpect(jsonPath("$.issue.riskFlag").value(false))
                .andExpect(jsonPath("$.printRun.issuedCount").value(60))
                .andExpect(jsonPath("$.printRun.remainingCount").value(40))
                .andExpect(jsonPath("$.printRun.versionNo").value(2));

        // 4. 实质变更（新增功效宣称）并期满生效：旧印次剩余标签原子禁用
        clock.setInstant(clock.instant().plus(1, ChronoUnit.HOURS));
        mockMvc.perform(post("/api/filings/products/P-LPH-1/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-C",
                                  "claims": ["MOISTURIZING", "REFRESHING"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.changeType").value("SUBSTANTIVE"));
        clock.setInstant(clock.instant().plus(11, ChronoUnit.DAYS));
        mockMvc.perform(post("/api/filings/public-notice-expiry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));

        // 5. 查询：100 = 60 已领 + 0 已毁 + 40 剩余，禁用剩余 40
        mockMvc.perform(get("/api/label-print-runs/RUN-LPH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.printedCount").value(100))
                .andExpect(jsonPath("$.issuedCount").value(60))
                .andExpect(jsonPath("$.destroyedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(40))
                .andExpect(jsonPath("$.disabledRemainingCount").value(40))
                .andExpect(jsonPath("$.versionNo").value(3));

        // 6. 换版后拒领旧剩余：422（禁用终态）
        mockMvc.perform(post("/api/label-print-runs/RUN-LPH-1/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-I2",
                                  "batchNo": "BATCH-LPH-1",
                                  "quantity": 40,
                                  "expectedVersion": 3
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRINT_RUN_DISABLED"));

        // 7. 旧版本号领用：409
        mockMvc.perform(post("/api/label-print-runs/RUN-LPH-1/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-I3",
                                  "batchNo": "BATCH-LPH-1",
                                  "quantity": 40,
                                  "expectedVersion": 2
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 8. 已领凭据仅追加风险关联，不抹历史
        mockMvc.perform(get("/api/label-print-runs/RUN-LPH-1/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantity").value(60))
                .andExpect(jsonPath("$[0].riskFlag").value(true));

        // 9. 禁用剩余 40 张登记销毁：留证，守恒清零
        mockMvc.perform(post("/api/label-print-runs/RUN-LPH-1/destructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-D1",
                                  "quantity": 40,
                                  "expectedVersion": 3,
                                  "reason": "合成销毁：换版禁用剩余"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printRun.destroyedCount").value(40))
                .andExpect(jsonPath("$.printRun.remainingCount").value(0))
                .andExpect(jsonPath("$.printRun.status").value("DISABLED"));
        mockMvc.perform(get("/api/label-print-runs/RUN-LPH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printedCount").value(100))
                .andExpect(jsonPath("$.issuedCount").value(60))
                .andExpect(jsonPath("$.destroyedCount").value(40))
                .andExpect(jsonPath("$.remainingCount").value(0));
    }

    @Test
    void registerValidationAndUnknownRun() throws Exception {
        // 参数非法（printedCount 非正整数）：400
        mockMvc.perform(post("/api/label-print-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-LPH-V",
                                  "printRunNo": "RUN-LPH-V",
                                  "productCode": "P-LPH-V",
                                  "labelDigest": "abc",
                                  "printedCount": 0
                                }
                                """))
                .andExpect(status().isBadRequest());

        // 印次不存在：404
        mockMvc.perform(get("/api/label-print-runs/RUN-NOPE"))
                .andExpect(status().isNotFound());
    }
}
