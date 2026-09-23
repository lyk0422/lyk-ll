package com.example.starter.ros.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ROS 参数中心通用装配：可控时钟与节点校验线程池，测试可替换为固定时钟。
 */
@Configuration
public class RosConfiguration {

    @Bean
    public Clock rosClock() {
        return Clock.systemUTC();
    }

    /** 跨节点并发校验候选配置使用的有界线程池。 */
    @Bean(name = "rosNodeExecutor", destroyMethod = "shutdown")
    public ExecutorService rosNodeExecutor() {
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "ros-node-validator");
            thread.setDaemon(true);
            return thread;
        });
    }
}
