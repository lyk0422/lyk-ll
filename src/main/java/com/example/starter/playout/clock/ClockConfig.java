package com.example.starter.playout.clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟装配；容器内唯一的合成时钟 Bean，供调度与接口默认取时使用。
 */
@Configuration
public class ClockConfig {

    @Bean
    public SyntheticClock syntheticClock() {
        return new SyntheticClock();
    }
}
