package com.example.starter.dye;

import com.example.starter.StarterApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * 集成测试基类：真实 Spring 上下文 + H2(MODE=MySQL)，每个用例前清空业务表保证隔离。
 * 清空期间临时关闭参照完整性，避免外键顺序依赖。
 */
@SpringBootTest(classes = StarterApplication.class)
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
public abstract class AbstractDyeIntegrationTest {

    private static final List<String> TABLES = List.of(
            "late_color_event", "lot_judgement", "stock_movement", "weigh_record",
            "color_assessment", "vat_run", "recipe_item", "recipe", "dye_vat",
            "dyestuff", "idempotent_request");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("set referential_integrity false");
        for (String table : TABLES) {
            jdbc.update("delete from " + table);
        }
        jdbc.execute("set referential_integrity true");
    }
}
