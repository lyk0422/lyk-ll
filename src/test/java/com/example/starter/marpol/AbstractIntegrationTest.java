package com.example.starter.marpol;

import com.example.starter.marpol.support.ServiceClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）+ Spring 上下文；每个场景前清空全部业务表，
 * 避免 DB_CLOSE_DELAY=-1 下的顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ServiceClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM inspection_review");
        jdbc.update("DELETE FROM inspection_item");
        jdbc.update("DELETE FROM inspection");
        jdbc.update("DELETE FROM emission_record");
        jdbc.update("DELETE FROM voyage");
        jdbc.update("DELETE FROM ship");
        jdbc.update("DELETE FROM special_area");
        jdbc.update("DELETE FROM land_mark");
        clock.reset();
    }
}
