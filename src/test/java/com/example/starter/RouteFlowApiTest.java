package com.example.starter;

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
 * 端到端主流程测试：线路申请 → 安全评估 → 启动执行 → 打卡 → 漏乘名单 → 日结。
 */
@AutoConfigureMockMvc
class RouteFlowApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 评估到运行的完整主流程() throws Exception {
        long vehicleId = insertVehicle("PLATE-F-1", 40);

        // 1. 创建线路申请
        String routeResponse = mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-flow-1","routeCode":"RC-F-1","name":"合成示范线",
                                "vehicleId":%d,"stationSpacingM":800,"roadGrade":2,"requiredSeats":35}
                                """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();
        long routeId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(routeResponse, "$.id").toString());

        // 2. 安全评估通过，生成 1 年有效期资格
        mockMvc.perform(post("/api/routes/{id}/assessments", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-flow-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"))
                .andExpect(jsonPath("$.validFrom").value("2026-10-06"))
                .andExpect(jsonPath("$.validTo").value("2027-10-05"));

        // 3. 启动执行
        mockMvc.perform(post("/api/routes/{id}/start", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-flow-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 4. 学生上车打卡；重复打卡去重
        long studentId = insertStudent("STU-F-1", routeId);
        mockMvc.perform(post("/api/routes/{id}/check-ins", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-flow-4","studentId":%d,"checkType":"BOARD"}
                                """.formatted(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(false));
        mockMvc.perform(post("/api/routes/{id}/check-ins", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-flow-5","studentId":%d,"checkType":"BOARD"}
                                """.formatted(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(true));

        // 5. 漏乘名单：另一学生未打卡，生成合成通知
        long missedStudentId = insertStudent("STU-F-2", routeId);
        mockMvc.perform(post("/api/routes/{id}/missed-boarding", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-flow-6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missedStudentIds[0]").value(missedStudentId))
                .andExpect(jsonPath("$.notificationIds.length()").value(1));

        // 6. 日结复算
        mockMvc.perform(get("/api/settlements/daily").param("date", "2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInCount").value(1))
                .andExpect(jsonPath("$.missedNoticeCount").value(1))
                .andExpect(jsonPath("$.reassignmentCount").value(0));
    }

    @Test
    void 同键异参通过HTTP返回409() throws Exception {
        long vehicleId = insertVehicle("PLATE-F-2", 40);
        String body = """
                {"requestId":"req-flow-7","routeCode":"RC-F-2","name":"合成线路",
                "vehicleId":%d,"stationSpacingM":800,"roadGrade":2,"requiredSeats":30}
                """.formatted(vehicleId);
        mockMvc.perform(post("/api/routes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        String conflict = """
                {"requestId":"req-flow-7","routeCode":"RC-F-3","name":"另一线路",
                "vehicleId":%d,"stationSpacingM":900,"roadGrade":2,"requiredSeats":30}
                """.formatted(vehicleId);
        mockMvc.perform(post("/api/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
