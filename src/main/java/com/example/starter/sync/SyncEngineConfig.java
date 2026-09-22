package com.example.starter.sync;

import com.example.starter.sync.engine.SyncEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 同步引擎装配：在线服务共享单例引擎，事件重建另建独立引擎实例重放。
 */
@Configuration
public class SyncEngineConfig {

    /**
     * 在线同步引擎单例。
     */
    @Bean
    public SyncEngine syncEngine(SyncProperties properties) {
        return new SyncEngine(properties);
    }
}
