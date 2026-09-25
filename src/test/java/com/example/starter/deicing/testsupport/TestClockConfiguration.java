package com.example.starter.deicing.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟装配：以可控时钟作为主 ServiceClock 替换系统墙钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /**
     * @return 可控时钟单例（同时满足 ServiceClock 注入点），测试类可直接注入并拨时
     */
    @Bean
    @Primary
    public MutableServiceClock mutableServiceClock() {
        return new MutableServiceClock();
    }
}
