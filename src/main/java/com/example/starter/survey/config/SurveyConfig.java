package com.example.starter.survey.config;

import com.example.starter.survey.support.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 默认 Bean：系统 UTC 时钟；测试可注入固定/偏移时钟。 */
@Configuration
public class SurveyConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public TimeProvider timeProvider(Clock clock) {
        return new TimeProvider(clock);
    }
}
