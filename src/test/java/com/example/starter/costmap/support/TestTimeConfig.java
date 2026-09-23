package com.example.starter.costmap.support;

import com.example.starter.costmap.time.TimeService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：以 {@link MutableTimeService} 替换系统时钟。
 */
@TestConfiguration
public class TestTimeConfig {

    @Bean
    @Primary
    public TimeService mutableTimeService() {
        return new MutableTimeService();
    }
}
