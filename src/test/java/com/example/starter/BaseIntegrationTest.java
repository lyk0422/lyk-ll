package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：RANDOM_PORT 全链路 HTTP + 独立 H2(test_dye, MODE=MySQL)。
 * 每个用例前清空业务表并重灌合成种子（3 个染化料 + R-BASE v1 已发布），杜绝顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM late_color_event");
        jdbc.update("DELETE FROM lot_judgment");
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM feeding");
        jdbc.update("DELETE FROM lot");
        jdbc.update("DELETE FROM vat_active");
        jdbc.update("DELETE FROM color_check");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM recipe_item");
        jdbc.update("DELETE FROM recipe");
        jdbc.update("DELETE FROM dye");

        jdbc.update("INSERT INTO dye (code, name, stock_g, created_at) VALUES "
                + "('D-RED','活性红 RED-3BS',5000.000,CURRENT_TIMESTAMP),"
                + "('D-YELLOW','活性黄 YELLOW-4G',5000.000,CURRENT_TIMESTAMP),"
                + "('D-BLUE','活性蓝 BLUE-RR',5000.000,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO recipe (recipe_code, version, liquor_ratio, status, created_at) "
                + "VALUES ('R-BASE',1,10.00,'PUBLISHED',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl) "
                + "SELECT id,'D-RED',2.0000 FROM recipe WHERE recipe_code='R-BASE' AND version=1");
        jdbc.update("INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl) "
                + "SELECT id,'D-YELLOW',1.5000 FROM recipe WHERE recipe_code='R-BASE' AND version=1");
        jdbc.update("INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl) "
                + "SELECT id,'D-BLUE',0.5000 FROM recipe WHERE recipe_code='R-BASE' AND version=1");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected org.springframework.http.ResponseEntity<Map> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> postOk(String path, Object body) {
        org.springframework.http.ResponseEntity<Map> resp = post(path, body);
        if (resp.getStatusCode().value() >= 400) {
            throw new AssertionError("期望成功但得到 " + resp.getStatusCode() + ": " + resp.getBody());
        }
        return resp.getBody();
    }

    protected Map<String, Object> createPublishedRecipe(String code, BigDecimal ratio,
                                                        List<List<Object>> items) {
        rest.postForEntity(url("/api/recipes"), new HttpEntity<>(Map.of(
                "recipeCode", code,
                "liquorRatio", ratio,
                "items", items.stream().map(i -> Map.of("dyeCode", i.get(0), "dosageGpl", i.get(1))).toList()
        ), jsonHeaders()), Map.class);
        return rest.postForEntity(url("/api/recipes/" + code + "/publish"),
                new HttpEntity<>(Map.of(), jsonHeaders()), Map.class).getBody();
    }

    protected Map<String, Object> schedule(String requestId, String lotNo, String vatNo,
                                           String recipeCode, int version, String fabricKg) {
        return postOk("/api/lots", Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "vatNo", vatNo,
                "recipeCode", recipeCode,
                "recipeVersion", version,
                "fabricWeightKg", new BigDecimal(fabricKg)));
    }

    /** R-BASE 在 50kg、浴比10 下目标克重：RED 1000g / YELLOW 750g / BLUE 250g。 */
    protected Map<String, Object> feedExact(String requestId, String lotNo) {
        return postOk("/api/feedings", Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
    }

    protected BigDecimal stock(String dyeCode) {
        return jdbc.queryForObject("SELECT stock_g FROM dye WHERE code = ?", BigDecimal.class, dyeCode);
    }

    /** JSON 往返后数字可能是 Double，统一归一化为 BigDecimal 再做数值比较。 */
    protected static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }

    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
