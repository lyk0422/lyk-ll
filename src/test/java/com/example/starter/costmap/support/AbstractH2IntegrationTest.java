package com.example.starter.costmap.support;

import com.example.starter.costmap.time.TimeService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个用例前清空业务表并复位可控时钟，保证场景间无顺序依赖。
 */
@SpringBootTest
@Import(TestTimeConfig.class)
public abstract class AbstractH2IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TimeService timeService;

    protected MutableTimeService clock() {
        return (MutableTimeService) timeService;
    }

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM snapshot_statement");
        jdbcTemplate.update("DELETE FROM costmap_snapshot");
        jdbcTemplate.update("DELETE FROM obstacle_statement");
        jdbcTemplate.update("DELETE FROM obstacle_request");
        jdbcTemplate.update("DELETE FROM costmap");
        clock().setNow(1_000L);
    }
}
