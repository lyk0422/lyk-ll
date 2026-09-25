package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 profile 冒烟测试：不使用 test 配置，直接验证本地启动入口
 * （application.yaml 默认 H2 + schema.sql 自动建表 + 合成数据初始化）可用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DefaultProfileSmokeTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void defaultProfile_bootsWithH2_andSeedsDemoStation() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/stations/ST-DEMO/distribution", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 合成换热站含 U-101/U-102/U-103 三个单元
        assertThat(response.getBody()).contains("U-101", "U-102", "U-103");
    }
}
