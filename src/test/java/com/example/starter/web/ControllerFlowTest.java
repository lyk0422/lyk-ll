package com.example.starter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 控制器测试：HTTP 状态语义（200/400/404/409/422）与评估到运行的主流程。
 */
@AutoConfigureMockMvc
class ControllerFlowTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void assessmentToRunningFlowOverHttp() throws Exception {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        insertStudent(1);
        assignStudent(1, 1);

        // 1) 安全评估通过，生成 1 年资格
        mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-a1","routeId":1,"vehicleId":1,
                                 "roadGrade":3,"stopDistancesM":[800,1200]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.validTo").value(today().plusYears(1).toString()));

        // 2) 线路进入执行中，资格有效
        mockMvc.perform(get("/api/routes/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.qualification.status").value("ACTIVE"));

        // 3) 学生打卡
        mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-c1","routeId":1,"studentId":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduped").value(false));

        // 4) 漏乘名单：该学生已打卡，名单为空
        mockMvc.perform(get("/api/routes/1/missed").param("date", today().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missedStudentIds").isEmpty());
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        insertVehicle(1, 30);
        insertVehicle(2, 30);
        insertRoute(1, null, "APPLIED");
        String body1 = """
                {"requestId":"http-idem","routeId":1,"vehicleId":1,
                 "roadGrade":3,"stopDistancesM":[800]}
                """;
        mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());

        // 同键同参：返回首次结果
        MvcResult replay = mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andReturn();
        assertThat(count("assessment")).isEqualTo(1);
        assertThat(replay.getResponse().getContentAsString()).contains("assessmentId");

        // 同键异参：409
        mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-idem","routeId":1,"vehicleId":2,
                                 "roadGrade":3,"stopDistancesM":[800]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":1,"vehicleId":1,"roadGrade":3,"stopDistancesM":[800]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/api/routes/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void earlyRenewalReturns422() throws Exception {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "RUNNING");
        LocalDate validTo = today().plusMonths(6);
        insertQualification(1, today(), validTo, "ACTIVE");

        mockMvc.perform(post("/api/routes/1/renewal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-ren-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void duplicateAssessmentReturns409() throws Exception {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        String failBody = """
                {"requestId":"http-dup-1","routeId":1,"vehicleId":1,
                 "roadGrade":1,"stopDistancesM":[800]}
                """;
        mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON).content(failBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false));

        mockMvc.perform(post("/api/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-dup-2","routeId":1,"vehicleId":1,
                                 "roadGrade":3,"stopDistancesM":[800]}
                                """))
                .andExpect(status().isConflict());
    }
}
