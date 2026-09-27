package com.example.starter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟装配：以 {@link MutableClock} 作为 @Primary Clock 覆盖生产时钟。
 * 位于被测扫描包 com.example.starter 下，测试运行（测试类路径）时由组件扫描注册，不进入打包产物。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
