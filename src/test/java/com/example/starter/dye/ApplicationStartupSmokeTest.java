package com.example.starter.dye;

import com.example.starter.StarterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Tomcat 容器启动冒烟：验证自动建表、合成数据初始化与真实 HTTP 入口可用。
 * 使用独立的随机端口与默认 H2，不启动任何外部服务。
 */
@SpringBootTest(classes = StarterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dye_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class ApplicationStartupSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startsWithSchemaAndSyntheticData_andColorMatchWorksOverHttp() {
        // 自动建表成功且合成数据已初始化
        Integer dyeCount = jdbc.queryForObject("select count(*) from dyestuff", Integer.class);
        assertThat(dyeCount).isEqualTo(3);
        Integer recipeCount = jdbc.queryForObject(
                "select count(*) from recipe where status='PUBLISHED'", Integer.class);
        assertThat(recipeCount).isGreaterThanOrEqualTo(1);

        // 真实 HTTP 对色（恰 0.8 合格）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "SMOKE-CM-1");
        body.put("sampleCode", "SMOKE-S");
        body.put("stdL", 0);
        body.put("stdA", 0);
        body.put("stdB", 0);
        body.put("sampleL", 0.8);
        body.put("sampleA", 0);
        body.put("sampleB", 0);
        String resp = rest.postForObject("http://localhost:" + port + "/api/color-match",
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp).contains("\"passed\":true").contains("0.8000");

        // 健康端点可用
        String health = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }
}
