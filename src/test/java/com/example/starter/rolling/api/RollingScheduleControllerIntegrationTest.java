package com.example.starter.rolling.api;

import com.example.starter.rolling.AbstractRollingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 公开契约：主流程 200、整单违例 422 含全部明细、同键异参 409、资源 404。
 */
@AutoConfigureMockMvc
class RollingScheduleControllerIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 编制主流程返回200与块明细() throws Exception {
        String body = """
                {
                  "requestId": "http-1",
                  "lineCode": "LH",
                  "unitCode": "UH",
                  "items": [
                    {"contractNo":"H1","widthMm":1250,"rollingKm":3,"warmup":true,
                     "startTime":"2026-09-25T08:00:00","endTime":"2026-09-25T09:00:00"},
                    {"contractNo":"H2","widthMm":1200,"rollingKm":3,"warmup":false,
                     "startTime":"2026-09-25T09:00:00","endTime":"2026-09-25T10:00:00"}
                  ],
                  "windows": []
                }
                """;
        mockMvc.perform(post("/api/rolling/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].campaignNo").value(1));
    }

    @Test
    void 违例整单拒绝422且返回全部明细() throws Exception {
        // 跳两级 + 第 6 块烫辊：两类违例同时上报。
        String body = """
                {
                  "requestId": "http-bad",
                  "lineCode": "LB",
                  "unitCode": "UB",
                  "items": [
                    {"contractNo":"B1","widthMm":1250,"rollingKm":1,"warmup":false,
                     "startTime":"2026-09-25T08:00:00","endTime":"2026-09-25T09:00:00"},
                    {"contractNo":"B2","widthMm":1250,"rollingKm":1,"warmup":false,
                     "startTime":"2026-09-25T09:00:00","endTime":"2026-09-25T10:00:00"},
                    {"contractNo":"B3","widthMm":1200,"rollingKm":1,"warmup":false,
                     "startTime":"2026-09-25T10:00:00","endTime":"2026-09-25T11:00:00"},
                    {"contractNo":"B4","widthMm":1100,"rollingKm":1,"warmup":false,
                     "startTime":"2026-09-25T11:00:00","endTime":"2026-09-25T12:00:00"},
                    {"contractNo":"B5","widthMm":1050,"rollingKm":1,"warmup":false,
                     "startTime":"2026-09-25T12:00:00","endTime":"2026-09-25T13:00:00"},
                    {"contractNo":"B6","widthMm":1000,"rollingKm":1,"warmup":true,
                     "startTime":"2026-09-25T13:00:00","endTime":"2026-09-25T14:00:00"}
                  ],
                  "windows": []
                }
                """;
        mockMvc.perform(post("/api/rolling/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_VIOLATION"))
                .andExpect(jsonPath("$.violations.length()").value(2));
    }

    @Test
    void 同键异参返回409() throws Exception {
        String first = """
                {
                  "requestId": "http-conflict",
                  "lineCode": "LC",
                  "unitCode": "UC",
                  "items": [
                    {"contractNo":"K1","widthMm":1250,"rollingKm":3,"warmup":true,
                     "startTime":"2026-09-25T08:00:00","endTime":"2026-09-25T09:00:00"}
                  ],
                  "windows": []
                }
                """;
        String second = first.replace("\"rollingKm\":3", "\"rollingKm\":4");
        mockMvc.perform(post("/api/rolling/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rolling/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void 查询不存在排产单404() throws Exception {
        mockMvc.perform(get("/api/rolling/schedules/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 请求体缺必填字段400() throws Exception {
        String body = """
                {
                  "lineCode": "LX",
                  "unitCode": "UX",
                  "items": []
                }
                """;
        mockMvc.perform(post("/api/rolling/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
