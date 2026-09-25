package com.example.starter.blend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层语义测试：验证 HTTP 状态码（201/400/404/409/422）、幂等重放与错误体结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlendApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM lab_sample");
        jdbc.update("DELETE FROM tank_movement");
        jdbc.update("DELETE FROM blend_order_component");
        jdbc.update("DELETE FROM blend_batch");
        jdbc.update("DELETE FROM blend_tank_lock");
        jdbc.update("DELETE FROM blend_order");
        jdbc.update("DELETE FROM product_spec");
        jdbc.update("DELETE FROM component_tank");
        jdbc.update("INSERT INTO component_tank (code, name, octane, sulfur, rvp, stock_kg) "
                + "VALUES ('TA','合成组分A',90.0,8.0,50.0,10000)");
        jdbc.update("INSERT INTO component_tank (code, name, octane, sulfur, rvp, stock_kg) "
                + "VALUES ('TB','合成组分B',94.0,12.0,60.0,10000)");
        jdbc.update("INSERT INTO product_spec (product_code, octane_max, sulfur_max, rvp_max, "
                + "octane_margin, sulfur_margin, rvp_margin) "
                + "VALUES ('P1',92.0,10.0,60.0,1.0,2.0,5.0)");
    }

    private static final String CREATE_BODY = """
            {"requestId":"API-R-1","blendTankCode":"BT1","productCode":"P1",
             "targetQtyKg":1000,"components":[{"tankCode":"TA","qtyKg":500},{"tankCode":"TB","qtyKg":500}]}
            """;

    @Test
    void 下达双采样放行全流程HTTP语义() throws Exception {
        String created = mvc.perform(post("/api/blend-orders")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.indicators.octane").value(92.0))
                .andReturn().getResponse().getContentAsString();
        long orderId = Long.parseLong(
                created.replaceAll(".*\"orderId\":(\\d+).*", "$1"));

        // 同键同参重放：返回首次结果
        mvc.perform(post("/api/blend-orders")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.orderId").value(orderId));

        // 同键异参：409
        mvc.perform(post("/api/blend-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"targetQtyKg\":1000", "\"targetQtyKg\":2000")
                                .replace("\"qtyKg\":500}", "\"qtyKg\":1000}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("不一致")));

        // 双采样
        mvc.perform(post("/api/blend-orders/{id}/lab-samples", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"API-S-1\",\"samplePoint\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));
        // 复样未提交前不得封账：409
        mvc.perform(post("/api/blend-orders/{id}/release", orderId))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/blend-orders/{id}/lab-samples", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"API-S-2\",\"samplePoint\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("PASSED"));
        // 封账与重复封账幂等
        mvc.perform(post("/api/blend-orders/{id}/release", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(post("/api/blend-orders/{id}/release", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        // 迟到化验只记事件
        mvc.perform(post("/api/blend-orders/{id}/lab-samples", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"API-S-3\",\"samplePoint\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.orderStatus").value("RELEASED"));
        // 详情可复算且稳定
        mvc.perform(get("/api/blend-orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.batches.length()").value(1))
                .andExpect(jsonPath("$.samples.length()").value(3));
    }

    @Test
    void 参数校验与资源语义() throws Exception {
        // 缺字段：400
        mvc.perform(post("/api/blend-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"API-BAD-1\"}"))
                .andExpect(status().isBadRequest());
        // 组分不足：422
        mvc.perform(post("/api/blend-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"API-BAD-2\",\"blendTankCode\":\"BT1\","
                                + "\"productCode\":\"P1\",\"targetQtyKg\":20000,"
                                + "\"components\":[{\"tankCode\":\"TA\",\"qtyKg\":20000}]}"))
                .andExpect(status().isUnprocessableEntity());
        // 未知牌号：422
        mvc.perform(post("/api/blend-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"API-BAD-3\",\"blendTankCode\":\"BT1\","
                                + "\"productCode\":\"PX\",\"targetQtyKg\":100,"
                                + "\"components\":[{\"tankCode\":\"TA\",\"qtyKg\":100}]}"))
                .andExpect(status().isUnprocessableEntity());
        // 不存在的调合单：404
        mvc.perform(get("/api/blend-orders/{id}", 99999))
                .andExpect(status().isNotFound());
    }
}
