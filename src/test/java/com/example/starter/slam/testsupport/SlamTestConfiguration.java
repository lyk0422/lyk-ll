package com.example.starter.slam.testsupport;

import com.example.starter.slam.optim.PoseGraphOptimizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：用可控优化器替换默认确定性优化器，便于编排失败与运行中阻塞。
 */
@TestConfiguration
public class SlamTestConfiguration {

    @Bean
    @Primary
    public PoseGraphOptimizer controllableOptimizer() {
        return new ControllableOptimizer();
    }
}
