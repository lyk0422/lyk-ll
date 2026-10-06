package com.example.starter.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 采收批登记接口测试：比例勾稽、字段校验与幂等语义。
 */
@AutoConfigureMockMvc
class HarvestBatchApiTest extends BaseTraceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 登记采收批_比例合计恰100_成功() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-1","batchNo":"HB-C-1","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":60.00},{"apiaryId":2,"ratio":40.00}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.contributions.length()").value(2));
    }

    @Test
    void 登记采收批_比例合计不足100_拒绝() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-2","batchNo":"HB-C-2","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":60.00},{"apiaryId":2,"ratio":39.99}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("100.00")));
    }

    @Test
    void 登记采收批_比例合计超过100_拒绝() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-3","batchNo":"HB-C-3","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":60.01},{"apiaryId":2,"ratio":40.00}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 登记采收批_比例超过两位小数_拒绝() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-4","batchNo":"HB-C-4","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":33.333},{"apiaryId":2,"ratio":66.667}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 登记采收批_同一蜂场重复贡献_拒绝() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-5","batchNo":"HB-C-5","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":50.00},{"apiaryId":1,"ratio":50.00}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 登记采收批_蜂场不存在_拒绝() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-6","batchNo":"HB-C-6","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":999,"ratio":100.00}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 幂等_同键同参_返回首次结果且不重复落库() throws Exception {
        String body = """
                {"requestId":"REQ-IDEM-1","batchNo":"HB-IDEM-1","flowerPeriod":"2026-洋槐",
                 "contributions":[{"apiaryId":1,"ratio":100.00}]}
                """;
        MvcResult first = mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(StandardCharsets.UTF_8));

        String batchId = com.jayway.jsonpath.JsonPath
                .read(first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")
                .toString();
        mockMvc.perform(get("/api/harvest-batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value("HB-IDEM-1"));
    }

    @Test
    void 幂等_同键异参_返回409() throws Exception {
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-IDEM-2","batchNo":"HB-IDEM-2","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":100.00}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/harvest-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-IDEM-2","batchNo":"HB-IDEM-2X","flowerPeriod":"2026-洋槐",
                                 "contributions":[{"apiaryId":1,"ratio":100.00}]}
                                """))
                .andExpect(status().isConflict());
    }
}
