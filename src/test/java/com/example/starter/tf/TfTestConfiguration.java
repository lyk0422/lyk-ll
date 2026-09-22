package com.example.starter.tf;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试装配：用可控时钟替换系统时钟。
 */
@TestConfiguration
public class TfTestConfiguration {

    @Bean
    @Primary
    public MutableNanoClock mutableNanoClock() {
        return new MutableNanoClock();
    }
}
