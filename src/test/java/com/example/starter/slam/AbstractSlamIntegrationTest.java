package com.example.starter.slam;

import com.example.starter.slam.support.ScriptedOptimizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空全部业务表并复位图版本，避免顺序依赖。
 */
@SpringBootTest
@Import(AbstractSlamIntegrationTest.TestOptimizerConfig.class)
public abstract class AbstractSlamIntegrationTest {

    /**
     * 用脚本化优化器替换默认确定性优化器，便于注入失败与并发门闩。
     */
    @TestConfiguration
    static class TestOptimizerConfig {
        @Bean
        public ScriptedOptimizer scriptedOptimizer() {
            return new ScriptedOptimizer();
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ScriptedOptimizer optimizer;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM opt_results");
        jdbc.update("DELETE FROM opt_tasks");
        jdbc.update("DELETE FROM edges");
        jdbc.update("DELETE FROM keyframes");
        jdbc.update("UPDATE graph_meta SET current_version = 0 WHERE id = 1");
        optimizer.reset();
    }

    /**
     * 36 元素对角协方差（迹 0.06），通过默认阈值。
     */
    protected static List<Double> goodCovariance() {
        double[] values = new double[36];
        java.util.Arrays.fill(values, 0.0);
        for (int i = 0; i < 6; i++) {
            values[i * 6 + i] = 0.01;
        }
        return java.util.Arrays.stream(values).boxed().toList();
    }

    /**
     * 高不确定性协方差（迹 6.0），超出默认迹阈值 1.0。
     */
    protected static List<Double> noisyCovariance() {
        double[] values = new double[36];
        for (int i = 0; i < 6; i++) {
            values[i * 6 + i] = 1.0;
        }
        return java.util.Arrays.stream(values).boxed().toList();
    }
}
