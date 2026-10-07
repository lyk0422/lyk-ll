package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：RANDOM_PORT 全链路 HTTP + 独立 H2(test_dye, MODE=MySQL)。
 * 每个用例前清空业务表并重灌合成种子（3 个染化料 + R-BASE v1 已发布），杜绝顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetDatabase() {
        clock.reset();
        jdbc.update("DELETE FROM late_color_event");
        jdbc.update("DELETE FROM lot_judgment");
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM feeding");
        jdbc.update("DELETE FROM lot_split_usage");
        jdbc.update("DELETE FROM lot_split_child");
        jdbc.update("DELETE FROM lot_split");
        jdbc.update("DELETE FROM compensation_item");
        jdbc.update("DELETE FROM compensation");
        jdbc.update("DELETE FROM lot");
        jdbc.update("DELETE FROM vat_active");
        jdbc.update("DELETE FROM color_check");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM recipe_item");
        jdbc.update("DELETE FROM recipe");
        jdbc.update("DELETE FROM dye_batch");
        jdbc.update("DELETE FROM dye");

        jdbc.update("INSERT INTO dye (code, name, stock_g, created_at) VALUES "
                + "('D-RED','活性红 RED-3BS',5000.000,CURRENT_TIMESTAMP),"
                + "('D-YELLOW','活性黄 YELLOW-4G',5000.000,CURRENT_TIMESTAMP),"
                + "('D-BLUE','活性蓝 BLUE-RR',5000.000,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO dye_batch "
                + "(batch_no, dye_code, factor_version, potency_factor, available_g, expire_at, active, version, created_at, updated_at) "
                + "VALUES "
                + "('B-RED-001','D-RED',1,1.000000,100000.000,DATEADD('YEAR',1,CURRENT_TIMESTAMP),TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "('B-YELLOW-001','D-YELLOW',1,1.000000,100000.000,DATEADD('YEAR',1,CURRENT_TIMESTAMP),TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "('B-BLUE-001','D-BLUE',1,1.000000,100000.000,DATEADD('YEAR',1,CURRENT_TIMESTAMP),TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
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

    /** 受控排缸（controlled=true），其余参数语义与普通排缸一致。 */
    protected Map<String, Object> scheduleControlled(String requestId, String lotNo, String vatNo,
                                                     String recipeCode, int version, String fabricKg) {
        return postOk("/api/lots/controlled", Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "vatNo", vatNo,
                "recipeCode", recipeCode,
                "recipeVersion", version,
                "fabricWeightKg", new BigDecimal(fabricKg)));
    }

    /** 登记染料批次；返回创建结果（factorVersion=1，version=1）。 */
    protected Map<String, Object> registerBatch(String requestId, String batchNo, String dyeCode,
                                                String factor, String availableG, String expireAt) {
        return postOk("/api/batches", Map.of(
                "requestId", requestId,
                "batchNo", batchNo,
                "dyeCode", dyeCode,
                "potencyFactor", new BigDecimal(factor),
                "availableG", new BigDecimal(availableG),
                "expireAt", expireAt,
                "expectedVersion", 1));
    }

    /** 修订批次系数：发布下一系数版本，expectedVersion 为批次聚合当前 version。 */
    protected Map<String, Object> reviseBatch(String requestId, String batchNo, String factor,
                                              String availableG, String expireAt, int expectedVersion) {
        return postOk("/api/batches/" + batchNo + "/revise", Map.of(
                "requestId", requestId,
                "batchNo", batchNo,
                "potencyFactor", new BigDecimal(factor),
                "availableG", new BigDecimal(availableG),
                "expireAt", expireAt,
                "expectedVersion", expectedVersion));
    }

    /** 为缸次办理 R-BASE 三色补偿批准：批次按 dyeCode→[batchNo, factorVersion] 映射指定。 */
    protected Map<String, Object> approveCompensation(String requestId, String lotNo, int expectedVersion,
                                                      Map<String, List<Object>> batchByDye) {
        return postOk("/api/compensations", Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "expectedVersion", expectedVersion,
                "items", batchByDye.entrySet().stream()
                        .map(e -> Map.of(
                                "dyeCode", e.getKey(),
                                "batchNo", e.getValue().get(0),
                                "factorVersion", e.getValue().get(1)))
                        .toList()));
    }

    /** 使用 R-BASE 种子批次（v1，系数 1.0）办理补偿；50kg 下补偿目标 1000/750/250g。 */
    protected Map<String, Object> approveSeedCompensation(String requestId, String lotNo) {
        return approveCompensation(requestId, lotNo, 1, new java.util.LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-001", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));
    }

    /** 受控投料：按补偿目标（默认等于种子 v1 系数 1.0 下 1000/750/250g）提交称量，携带补偿版本。 */
    protected Map<String, Object> feedControlledExact(String requestId, String lotNo, int expectedVersion) {
        return postOk("/api/feedings", Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "expectedVersion", expectedVersion,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
    }

    protected BigDecimal batchAvailable(String batchNo) {
        return jdbc.queryForObject(
                "SELECT available_g FROM dye_batch WHERE batch_no = ? ORDER BY factor_version DESC LIMIT 1",
                BigDecimal.class, batchNo);
    }

    protected Map<String, Object> compensationRow(String lotNo) {
        return jdbc.queryForMap(
                "SELECT c.* FROM compensation c JOIN lot l ON l.id = c.lot_id WHERE l.lot_no = ?",
                lotNo);
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
