package com.example.starter.lot;

import com.example.starter.lot.api.ConversionRequest;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.QuarantineRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实内嵌 Web 容器（随机端口）端到端冒烟：验证无需任何外部系统即可启动，
 * 并完成“拆并批 → 隔离传播 → 谱系查询”的一条完整合成业务链路。
 * 应用上下文由测试框架管理，用例结束即关闭。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalStartupSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM quarantine_source");
        jdbc.update("DELETE FROM quarantine_event");
        jdbc.update("DELETE FROM genealogy_edge");
        jdbc.update("DELETE FROM conversion_input");
        jdbc.update("DELETE FROM conversion_output");
        jdbc.update("DELETE FROM conversion");
        jdbc.update("DELETE FROM lot");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("端到端：启动服务并完成拆并批、隔离传播与谱系查询")
    void fullSyntheticFlowOverHttp() {
        String base = "http://localhost:" + port + "/api/lots";

        // 1) 建两个父批次。
        ResponseEntity<Map> r1 = post(base, new CreateLotRequest("A", "KG", new BigDecimal("100"), null));
        ResponseEntity<Map> r2 = post(base, new CreateLotRequest("B", "KG", new BigDecimal("100"), null));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 2) 并批：A/B 各消费 40，产出 M=79（允许损耗 1）。
        ConversionRequest merge = new ConversionRequest(
                "SMOKE-1", "KG", new BigDecimal("1.000"),
                List.of(new ConversionRequest.InputItem("A", new BigDecimal("40"), 0),
                        new ConversionRequest.InputItem("B", new BigDecimal("40"), 0)),
                List.of(new ConversionRequest.OutputItem("M", new BigDecimal("79"))));
        ResponseEntity<Map> merged = post(base + "/conversions", merge);
        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // JSON 数字反序列化进 Map 后为 Double，转 BigDecimal 按数值比较。
        assertThat(new BigDecimal(merged.getBody().get("loss").toString()))
                .isEqualByComparingTo("1.000");

        // 3) 拆批：M 消费 30，产出 M1=20 与 M2=10。
        ConversionRequest split = new ConversionRequest(
                "SMOKE-2", "KG", BigDecimal.ZERO.setScale(3),
                List.of(new ConversionRequest.InputItem("M", new BigDecimal("30"), 0)),
                List.of(new ConversionRequest.OutputItem("M1", new BigDecimal("20")),
                        new ConversionRequest.OutputItem("M2", new BigDecimal("10"))));
        assertThat(post(base + "/conversions", split).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 4) 隔离祖先 A：M、M1、M2 必须全部立即隔离。
        ResponseEntity<Map> quarantined = post(base + "/quarantine",
                new QuarantineRequest("A", QuarantineRequest.Action.QUARANTINE, "smoke"));
        assertThat(quarantined.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> affected = (List<Map<String, Object>>) quarantined.getBody().get("affected");
        assertThat(affected).extracting(m -> m.get("lotId"))
                .containsExactlyInAnyOrder("A", "M", "M1", "M2");

        // 5) 影响面与谱系查询。
        ResponseEntity<Map> impact = rest.getForEntity(base + "/quarantine/A/impact", Map.class);
        assertThat(((List<?>) impact.getBody().get("lots"))).hasSize(4);

        ResponseEntity<Map> genealogy = rest.getForEntity(base + "/M2/genealogy", Map.class);
        assertThat(genealogy.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ancestors = ((List<?>) genealogy.getBody().get("ancestors")).stream()
                .map(Object::toString).toList();
        assertThat(ancestors).containsExactlyInAnyOrder("M", "A", "B");

        // 6) 解除 A 后整条链恢复可用。
        ResponseEntity<Map> released = post(base + "/quarantine",
                new QuarantineRequest("A", QuarantineRequest.Action.RELEASE, "smoke clear"));
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> m2 = rest.getForEntity(base + "/M2", Map.class);
        assertThat(m2.getBody().get("status")).isEqualTo("AVAILABLE");
    }

    private ResponseEntity<Map> post(String url, Object body) {
        return rest.postForEntity(url, new HttpEntity<>(body), Map.class);
    }
}
