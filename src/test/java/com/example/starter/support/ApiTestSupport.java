package com.example.starter.support;

import com.example.starter.StarterApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * API 测试基类：装配 MockMvc 与合成数据夹具，提供请求构造与发送辅助方法。
 */
@SpringBootTest(classes = StarterApplication.class)
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected TestFixtures fixtures;

    @BeforeEach
    void resetFixtures() {
        fixtures.reset();
    }

    /** 发送 POST 并断言状态码，返回响应 JSON。 */
    protected JsonNode post(String url, ObjectNode body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet
                        .request.MockMvcRequestBuilders.post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "响应状态码不符合预期，响应体: " + result.getResponse().getContentAsString());
        return om.readTree(result.getResponse().getContentAsString());
    }

    /** 构造出港申报请求。 */
    protected ObjectNode departureBody(String requestId, long vesselId, String departureTime,
                                       String fishingArea, String species, String quantityKg) {
        ObjectNode body = om.createObjectNode();
        body.put("requestId", requestId);
        body.put("vesselId", vesselId);
        body.put("departureTime", departureTime);
        body.put("fishingArea", fishingArea);
        ArrayNode items = body.putArray("items");
        items.addObject().put("speciesCode", species).put("quantityKg", quantityKg);
        return body;
    }

    /** 构造渔捞日志请求（明细项：鱼种、数量、作业时刻）。 */
    protected ObjectNode logBody(String requestId, String fishingArea, String[]... items) {
        ObjectNode body = om.createObjectNode();
        body.put("requestId", requestId);
        body.put("fishingArea", fishingArea);
        ArrayNode array = body.putArray("items");
        for (String[] item : items) {
            array.addObject()
                    .put("speciesCode", item[0])
                    .put("quantityKg", item[1])
                    .put("operationTime", item[2]);
        }
        return body;
    }

    /** 构造进港报告请求。 */
    protected ObjectNode arrivalBody(String requestId, String arrivalTime, String differenceNote,
                                     String species, String unloadedKg) {
        ObjectNode body = om.createObjectNode();
        body.put("requestId", requestId);
        body.put("arrivalTime", arrivalTime);
        if (differenceNote != null) {
            body.put("differenceNote", differenceNote);
        }
        ArrayNode items = body.putArray("items");
        items.addObject().put("speciesCode", species).put("unloadedKg", unloadedKg);
        return body;
    }

    /** 构造仅含 requestId 的请求。 */
    protected ObjectNode requestIdBody(String requestId) {
        ObjectNode body = om.createObjectNode();
        body.put("requestId", requestId);
        return body;
    }

    /** 走通“出港→日志→进港”前置流程，返回航次 ID。 */
    protected long departLogArrive(String requestPrefix, long vesselId, String species,
                                   String quantityKg, String operationTime) throws Exception {
        JsonNode departure = post("/api/v1/departures",
                departureBody(requestPrefix + "-dep", vesselId, "2026-03-01T08:00:00Z", "A1",
                        species, quantityKg), 200);
        long voyageId = departure.get("voyageId").asLong();
        post("/api/v1/voyages/" + voyageId + "/logs",
                logBody(requestPrefix + "-log", "A1",
                        new String[]{species, quantityKg, operationTime}), 200);
        post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody(requestPrefix + "-arr", "2026-03-05T10:00:00Z", null,
                        species, quantityKg), 200);
        return voyageId;
    }
}
