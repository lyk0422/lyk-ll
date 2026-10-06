package com.example.starter.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 端到端测试：评级 -> 许可 -> 回传闭环 -> 台账，以及 400/409 HTTP 语义。
 */
class WebApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowFromRatingToClosure() throws Exception {
        // 1. 路段建档
        MvcResult segResult = mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-seg-1\",\"name\":\"中山路\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        long segmentId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(segResult.getResponse().getContentAsString(), "$.id").toString());

        // 2. 树木建档：一级风险，自动生成 7 日任务
        MvcResult treeResult = mockMvc.perform(post("/api/trees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-tree-1","segmentId":%d,"treeCode":"WEB-T-001",
                                 "species":"悬铃木","age":60,"healthScore":35,"leanDegree":18.0,"cavity":true}
                                """.formatted(segmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value(1))
                .andExpect(jsonPath("$.taskDeadline").value("2026-01-12"))
                .andReturn();
        long taskId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(treeResult.getResponse().getContentAsString(), "$.taskId").toString());

        // 3. 签发高空作业许可
        MvcResult permitResult = mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-permit-1","taskId":%d,"workType":"AERIAL"}
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.deadlineDate").value("2026-01-12"))
                .andReturn();
        long permitId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(permitResult.getResponse().getContentAsString(), "$.id").toString());

        // 4. 恰第 7 日回传闭环
        clock.setDate(java.time.LocalDate.of(2026, 1, 12));
        mockMvc.perform(post("/api/permits/{permitId}/reports", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-report-1","beforePhotoId":"PH-B-1",
                                 "afterPhotoId":"PH-A-1","disposalRecord":"弃枝 3 车已清运"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onTime").value(true))
                .andExpect(jsonPath("$.escalated").value(false));

        // 5. 台账：闭环率 1.0，一级风险分布 1
        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].riskDistribution['1']").value(1))
                .andExpect(jsonPath("$.segments[0].closureRate").value(1.0));
    }

    @Test
    void reportMissingPhotoReturns400() throws Exception {
        long segmentId = createSegment("中山路").id();
        long taskId = registerLevelOneTree(segmentId, "WEB-T-002").taskId();
        MvcResult permitResult = mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-permit-2","taskId":%d,"workType":"GROUND"}
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andReturn();
        long permitId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(permitResult.getResponse().getContentAsString(), "$.id").toString());

        mockMvc.perform(post("/api/permits/{permitId}/reports", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-report-2","beforePhotoId":"PH-B-1",
                                 "afterPhotoId":"","disposalRecord":"弃枝已清运"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("作业后照片标识不能为空")));
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() throws Exception {
        mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-seg-dup\",\"name\":\"中山路\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-seg-dup\",\"name\":\"解放大道\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("不同的业务参数")));
    }

    @Test
    void sameKeySameParamsReplaysFirstResponseOverHttp() throws Exception {
        String body = "{\"requestId\":\"web-seg-replay\",\"name\":\"中山路\"}";
        MvcResult first = mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first.getResponse().getContentAsString()));
    }
}
