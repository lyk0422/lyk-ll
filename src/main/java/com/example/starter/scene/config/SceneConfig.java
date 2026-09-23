package com.example.starter.scene.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Planning Scene 基础 Bean 配置。 */
@Configuration
public class SceneConfig {

    /** 系统 UTC 时钟；测试可替换为固定/可控时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
