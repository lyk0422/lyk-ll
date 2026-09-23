package com.example.starter.slam.config;

import com.example.starter.slam.opt.PoseGraphOptimizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

/**
 * SLAM 后端 Spring 配置：优化器 Bean 可被测试替换，优化任务在独立线程池异步执行。
 */
@Configuration
@EnableConfigurationProperties(SlamProperties.class)
public class SlamConfig {

    /**
     * 默认确定性优化器；测试或上层装配可声明自己的 PoseGraphOptimizer Bean 覆盖本默认实现。
     */
    @Bean
    @ConditionalOnMissingBean(PoseGraphOptimizer.class)
    public PoseGraphOptimizer poseGraphOptimizer() {
        return new com.example.starter.slam.opt.DeterministicPoseGraphOptimizer();
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "slamOptExecutor")
    @ConditionalOnMissingBean(name = "slamOptExecutor")
    public Executor slamOptExecutor(SlamProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getOpt().getConcurrency()));
        executor.setMaxPoolSize(Math.max(1, properties.getOpt().getConcurrency()));
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("slam-opt-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
