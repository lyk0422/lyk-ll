package com.example.starter.ros;

import com.example.starter.ros.bootstrap.DemoDataInitializer;
import com.example.starter.ros.node.SimulatedNodeAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ROS 参数中心集成测试基类：真实 Spring 上下文 + H2(MODE=MySQL) 内存库 + 真实 HTTP 入口。
 * 每个场景前清空全部业务表并重新装入合成 Schema/初始值，避免顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class RosIntegrationTestBase {

    private static final List<String> TABLES = List.of(
            "ros_event_ack",
            "ros_param_event",
            "ros_pending_restart",
            "ros_transaction",
            "ros_param_value",
            "ros_node_version",
            "ros_param_schema"
    );

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SimulatedNodeAdapter nodeAdapter;

    @Autowired
    private DemoDataInitializer demoDataInitializer;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void resetDatabaseAndNodes() throws Exception {
        nodeAdapter.reset();
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        // 事件游标按场景从 1 重新开始，便于断言单调序列
        jdbc.update("ALTER TABLE ros_param_event ALTER COLUMN cursor RESTART WITH 1");
        demoDataInitializer.run(null);
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
