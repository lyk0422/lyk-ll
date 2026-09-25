package com.example.starter.fermentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * H2(MODE=MySQL) 集成测试基类：每个测试前清空全部业务表，避免顺序依赖；
 * 业务时间全部由请求体显式给出（可控时钟口径），不依赖系统当前时间。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("DELETE FROM idempotent_request").update();
        jdbc.sql("DELETE FROM late_event").update();
        jdbc.sql("DELETE FROM transfer_record").update();
        jdbc.sql("DELETE FROM maturity_judgment").update();
        jdbc.sql("DELETE FROM cooling_command").update();
        jdbc.sql("DELETE FROM diacetyl_test").update();
        jdbc.sql("DELETE FROM temperature_reading").update();
        jdbc.sql("DELETE FROM curve_segment").update();
        jdbc.sql("DELETE FROM curve_version").update();
        jdbc.sql("DELETE FROM fermentation_batch").update();
        jdbc.sql("DELETE FROM fermentation_tank").update();
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
