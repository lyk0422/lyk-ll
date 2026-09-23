package com.example.starter.trajectory.web;

import com.example.starter.trajectory.dto.CreateRuleVersionRequest;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.ValidationResult;
import com.example.starter.trajectory.exception.ConflictException;
import com.example.starter.trajectory.exception.NotFoundException;
import com.example.starter.trajectory.service.TrajectorySafetyService;
import com.example.starter.trajectory.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口切片测试：状态码、错误结构与响应字段。
 */
@WebMvcTest(TrajectorySafetyController.class)
class TrajectorySafetyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TrajectorySafetyService service;

    @Test
    @DisplayName("发布规则版本成功返回 201")
    void publishRuleVersionReturns201() throws Exception {
        doNothing().when(service).publishRuleVersion(any());
        CreateRuleVersionRequest body = new CreateRuleVersionRequest("v1",
                TestFixtures.ruleConfig(2, 10, 5, 1, 0.5, 2));

        mockMvc.perform(post("/api/trajectory-safety/rule-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("缺少必填字段返回 400 及字段级错误信息")
    void invalidBodyReturns400() throws Exception {
        String body = "{\"ruleVersion\":\"\",\"config\":null}";

        mockMvc.perform(post("/api/trajectory-safety/rule-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields.ruleVersion").exists());
    }

    @Test
    @DisplayName("校验成功返回 200 与确定性摘要字段")
    void validateReturnsSummary() throws Exception {
        ValidationRequest request = TestFixtures.safeRequest("http-key", "v1", null);
        ValidationResult result = new ValidationResult(
                "http-key", "v1", true, 0,
                java.util.Map.of("R1", 0, "R2", 0, "R3", 0, "R4", 0, "R5", 0, "R6", 0),
                "a".repeat(64), java.util.List.of());
        when(service.validate(any())).thenReturn(result);

        mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safe").value(true))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.resultHash").value("a".repeat(64)));
    }

    @Test
    @DisplayName("同键异参返回 409")
    void sameKeyDifferentParametersReturns409() throws Exception {
        doThrow(new ConflictException("requestId 已被不同参数占用: k"))
                .when(service).validate(any());
        ValidationRequest request = TestFixtures.safeRequest("k", "v1", null);

        mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("requestId 已被不同参数占用: k"));
    }

    @Test
    @DisplayName("规则版本不存在返回 404；按 requestId 查询不存在同样 404")
    void notFoundScenarios() throws Exception {
        when(service.validate(any())).thenThrow(new NotFoundException("规则版本不存在: v9"));
        ValidationRequest request = TestFixtures.safeRequest("k", "v9", null);

        mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        when(service.getByRequestId("missing")).thenThrow(new NotFoundException("校验结果不存在: missing"));
        mockMvc.perform(get("/api/trajectory-safety/validations/missing"))
                .andExpect(status().isNotFound());
    }
}
