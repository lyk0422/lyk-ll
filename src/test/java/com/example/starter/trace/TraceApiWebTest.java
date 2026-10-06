package com.example.starter.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层：采收到加工放行的合成主流程与错误语义。
 */
class TraceApiWebTest extends BaseTraceTest {

    @Autowired
    private MockMvc mockMvc;

    private Long apiaryId(String code) {
        return jdbc.queryForObject("SELECT id FROM apiary WHERE code = ?", Long.class, code);
    }

    @Test
    void 采收到加工放行全流程() throws Exception {
        Long ap1 = apiaryId("AP-001");
        Long ap2 = apiaryId("AP-002");

        // 1. 登记采收批：两个蜂场贡献 70.00 / 30.00
        String registerBody = """
                {"requestId":"req-w-1","batchNo":"HB-W1","floralPeriod":"2026-洋槐",
                 "contributions":[{"apiaryId":%d,"ratio":70.00},{"apiaryId":%d,"ratio":30.00}]}
                """.formatted(ap1, ap2);
        String registerResponse = mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.contributions.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        Long batchId = Long.valueOf(
                com.jayway.jsonpath.JsonPath.read(registerResponse, "$.id").toString());

        // 2. 快检疑似（抑制率 50.00），批冻结
        mockMvc.perform(post("/api/harvest-batches/{id}/rapid-tests", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-w-2\",\"sampleNo\":\"SMP-W1\",\"inhibitionRate\":50.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUSPECT"))
                .andExpect(jsonPath("$.batchStatus").value("FROZEN"));

        // 3. 冻结批不得进入加工
        mockMvc.perform(post("/api/processing-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-w-3\",\"processNo\":\"PB-W1\",\"harvestBatchId\":" + batchId + "}"))
                .andExpect(status().isUnprocessableEntity());

        // 4. 同一样品复检拒绝
        mockMvc.perform(post("/api/harvest-batches/{id}/retests", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-w-4\",\"sampleNo\":\"SMP-W1\",\"result\":\"QUALIFIED\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 5. 换样复检合格，解除冻结
        mockMvc.perform(post("/api/harvest-batches/{id}/retests", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-w-5\",\"sampleNo\":\"SMP-W1-B\",\"result\":\"QUALIFIED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));

        // 6. 原快检记录保留可查
        mockMvc.perform(get("/api/harvest-batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.rapidTests.length()").value(1))
                .andExpect(jsonPath("$.rapidTests[0].result").value("SUSPECT"))
                .andExpect(jsonPath("$.retests.length()").value(1));

        // 7. 放行后创建加工批成功
        mockMvc.perform(post("/api/processing-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-w-6\",\"processNo\":\"PB-W1\",\"harvestBatchId\":" + batchId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.harvestBatchId").value(batchId));
    }

    @Test
    void 同键异参_返回409() throws Exception {
        Long ap1 = apiaryId("AP-001");
        String body = """
                {"requestId":"req-w-7","batchNo":"HB-W2","floralPeriod":"2026-洋槐",
                 "contributions":[{"apiaryId":%d,"ratio":100.00}]}
                """.formatted(ap1);
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        String tampered = """
                {"requestId":"req-w-7","batchNo":"HB-W2","floralPeriod":"2026-荆条",
                 "contributions":[{"apiaryId":%d,"ratio":100.00}]}
                """.formatted(ap1);
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict());
    }

    @Test
    void 比例合计非100_返回422() throws Exception {
        Long ap1 = apiaryId("AP-001");
        String body = """
                {"requestId":"req-w-8","batchNo":"HB-W3","floralPeriod":"2026-洋槐",
                 "contributions":[{"apiaryId":%d,"ratio":99.99}]}
                """.formatted(ap1);
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 缺少必填字段_返回400() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"HB-W4\"}"))
                .andExpect(status().isBadRequest());
    }
}
