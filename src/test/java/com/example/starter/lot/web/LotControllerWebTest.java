package com.example.starter.lot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次谱系 REST API 的端到端测试：真实 Spring MVC + H2，覆盖主流程与失败分支状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LotControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM lot_quarantine");
        jdbc.update("DELETE FROM quarantine_event");
        jdbc.update("DELETE FROM lot_ledger");
        jdbc.update("DELETE FROM lot_link");
        jdbc.update("DELETE FROM transform_io");
        jdbc.update("DELETE FROM transform_record");
        jdbc.update("DELETE FROM lot");
    }

    private String uid(String prefix) {
        return prefix + "_W" + seq.incrementAndGet();
    }

    private String createLotJson(String id, String unit, String qty) {
        return """
                {"lotId":"%s","unit":"%s","quantity":%s}""".formatted(id, unit, qty);
    }

    @Test
    @DisplayName("POST /api/lots 建批次成功返回 201 与三位小数余额")
    void createLotEndpoint() throws Exception {
        String id = uid("P");
        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLotJson(id, "KG", "12.500")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lotId").value(id))
                .andExpect(jsonPath("$.availableQty").value(12.500))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("建批次数量非正或超过三位小数返回 400")
    void createLotValidationErrors() throws Exception {
        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLotJson(uid("P"), "KG", "0")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLotJson(uid("P"), "KG", "1.0001")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("重复建批次返回 409")
    void duplicateLotReturns409() throws Exception {
        String id = uid("P");
        mockMvc.perform(post("/api/lots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(id, "KG", "1"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLotJson(id, "KG", "2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("查询不存在批次返回 404")
    void missingLotReturns404() throws Exception {
        mockMvc.perform(get("/api/lots/NO_SUCH_LOT/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("转换主流程：合并两父批次产出子批次，余额与谱系可查询")
    void transformEndpointHappyPath() throws Exception {
        String a = uid("A");
        String b = uid("B");
        String c = uid("C");
        mockMvc.perform(post("/api/lots").contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(a, "KG", "10"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/lots").contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(b, "KG", "10"))).andExpect(status().isCreated());

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("requestId", "REQ-WEB-1");
        req.put("unit", "KG");
        req.put("allowedLoss", "0.500");
        req.put("parents", List.of(
                Map.of("lotId", a, "expectedVersion", 0, "quantity", "4.000"),
                Map.of("lotId", b, "expectedVersion", 0, "quantity", "4.000")));
        req.put("children", List.of(Map.of("lotId", c, "quantity", "7.500")));

        mockMvc.perform(post("/api/lots/transforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.totalConsumed").value(8.000))
                .andExpect(jsonPath("$.totalProduced").value(7.500))
                .andExpect(jsonPath("$.loss").value(0.500))
                .andExpect(jsonPath("$.childLotIds[0]").value(c));

        mockMvc.perform(get("/api/lots/" + a + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQty").value(6.000));

        mockMvc.perform(get("/api/lots/" + c + "/genealogy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ancestors[0]").value(a))
                .andExpect(jsonPath("$.ancestors[1]").value(b));
    }

    @Test
    @DisplayName("同键异参（改消费量）返回 409")
    void transformSameKeyDifferentParamsReturns409() throws Exception {
        String a = uid("A");
        mockMvc.perform(post("/api/lots").contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(a, "KG", "10"))).andExpect(status().isCreated());

        String body1 = transformBody("REQ-WEB-FP", a, "3.000", 0, uid("C"), "3.000", "0");
        mockMvc.perform(post("/api/lots/transforms")
                .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());

        String body2 = transformBody("REQ-WEB-FP", a, "4.000", 1, uid("C"), "4.000", "0");
        mockMvc.perform(post("/api/lots/transforms")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("同键同参重放返回 200 且 replayed=true")
    void transformReplayReturnsReplayed() throws Exception {
        String a = uid("A");
        mockMvc.perform(post("/api/lots").contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(a, "KG", "10"))).andExpect(status().isCreated());
        String body = transformBody("REQ-WEB-REPLAY", a, "2.000", 0, uid("C"), "2.000", "0");

        mockMvc.perform(post("/api/lots/transforms")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/lots/transforms")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    @DisplayName("隔离设置传播到子批次，解除后放行；影响面接口同步反映")
    void quarantineAndReleaseOverHttp() throws Exception {
        String a = uid("A");
        String c = uid("C");
        mockMvc.perform(post("/api/lots").contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(a, "KG", "10"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/lots/transforms").contentType(MediaType.APPLICATION_JSON)
                .content(transformBody("REQ-WEB-Q", a, "4.000", 0, c, "4.000", "0")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lots/quarantine").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lotId":"%s","action":"SET","reason":"抽检异常"}""".formatted(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotStatus").value("QUARANTINED"))
                .andExpect(jsonPath("$.affectedLotIds.length()").value(2));

        mockMvc.perform(get("/api/lots/" + c))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.activeQuarantineSources[0]").value(a));

        mockMvc.perform(get("/api/lots/" + a + "/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(true))
                .andExpect(jsonPath("$.affectedLots.length()").value(2));

        mockMvc.perform(post("/api/lots/quarantine").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lotId":"%s","action":"RELEASE","reason":"复检合格"}""".formatted(a)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/lots/" + c))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(0));
    }

    @Test
    @DisplayName("非法 action 返回 400")
    void invalidQuarantineActionReturns400() throws Exception {
        String a = uid("A");
        mockMvc.perform(post("/api/lots").contentType(MediaType.APPLICATION_JSON)
                .content(createLotJson(a, "KG", "10"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/lots/quarantine").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lotId":"%s","action":"HOLD"}""".formatted(a)))
                .andExpect(status().isBadRequest());
    }

    private String transformBody(String requestId, String parent, String parentQty,
                                 long version, String child, String childQty, String loss)
            throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("requestId", requestId);
        req.put("unit", "KG");
        req.put("allowedLoss", loss);
        req.put("parents", List.of(
                Map.of("lotId", parent, "expectedVersion", version, "quantity", parentQty)));
        req.put("children", List.of(Map.of("lotId", child, "quantity", childQty)));
        return objectMapper.writeValueAsString(req);
    }
}
