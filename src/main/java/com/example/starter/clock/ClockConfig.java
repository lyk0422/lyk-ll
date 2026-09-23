package com.example.starter.clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟装配：全局注入同一个合成时钟，业务代码不直接读取系统时间。
 */
@Configuration
public class ClockConfig {

    @Bean
    public SyntheticClock syntheticClock() {
        return new SyntheticClock();
    }
}
