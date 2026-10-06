package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 接口层测试：调光到事故锁定的合成全流程及 HTTP 错误语义。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tl_test_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 调光到事故锁定全流程() throws Exception {
        // 人工调光 3→4
        mvc.perform(post("/api/circuits/1/dim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-1\",\"targetLevel\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.fromLevel").value(3))
                .andExpect(jsonPath("$.toLevel").value(4))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        // 同键同参返回首次结果
        mvc.perform(post("/api/circuits/1/dim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-1\",\"targetLevel\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.toLevel").value(4));

        // 同键异参 409
        mvc.perform(post("/api/circuits/1/dim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-1\",\"targetLevel\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 事故告警：锁定在事故模式（最高档），不受稳定期限制
        mvc.perform(post("/api/circuits/1/accident-lock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-2\",\"operator\":\"op-wang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.toLevel").value(5))
                .andExpect(jsonPath("$.source").value("ACCIDENT"));

        // 锁定期间普通调光不得覆盖锁定值
        mvc.perform(post("/api/circuits/1/dim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-3\",\"targetLevel\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCIDENT_LOCKED"));

        // 解除缺少现场确认人 → 400
        mvc.perform(post("/api/circuits/1/accident-unlock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 现场确认后解除
        mvc.perform(post("/api/circuits/1/accident-unlock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-5\",\"confirmBy\":\"cf-zhao\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // 回路状态查询
        mvc.perform(get("/api/circuits/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.currentLevel").value(5));
    }

    @Test
    void 亮度恰等阈值不触发_日结接口可调用() throws Exception {
        mvc.perform(post("/api/circuits/2/brightness-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-br-1\",\"brightness\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_TRIGGERED"));

        mvc.perform(post("/api/settlement/daily").param("date", "2026-10-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].dimCount").value(0));
    }
}
