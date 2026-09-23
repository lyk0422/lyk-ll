package com.example.starter.rosbridge.config;

import com.example.starter.rosbridge.adapter.RosAdapter;
import com.example.starter.rosbridge.adapter.SyntheticRosAdapter;
import com.example.starter.rosbridge.topic.QosNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.starter.rosbridge.ws.RosbridgeWebSocketHandler;
import com.example.starter.rosbridge.service.GatewayService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关核心 Bean 装配：可注入的 ROS 适配器（默认合成实现）、共享调度器与 WebSocket 端点。
 */
@Configuration
@EnableConfigurationProperties(RosbridgeProperties.class)
public class GatewayConfig {

    /**
     * 默认 ROS 适配器为本地合成实现，不连接真实 ROS；
     * 上层可提供自己的 {@link RosAdapter} Bean 进行替换。
     */
    @Bean
    public RosAdapter rosAdapter() {
        return new SyntheticRosAdapter();
    }

    /** QoS 规范化器。 */
    @Bean
    public QosNormalizer qosNormalizer(ObjectMapper mapper) {
        return new QosNormalizer(mapper);
    }

    /** 网关时钟，测试可替换为固定/可推进时钟。 */
    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemDefaultZone();
    }

    /** 网关共享调度器：Service 超时、断连宽限期等延迟任务。 */
    @Bean(name = "gatewayScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService gatewayScheduler() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "gateway-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** WebSocket 处理器 Bean。 */
    @Bean
    public RosbridgeWebSocketHandler rosbridgeWebSocketHandler(GatewayService gatewayService,
                                                               ObjectMapper mapper,
                                                               RosbridgeProperties properties) {
        return new RosbridgeWebSocketHandler(gatewayService, mapper, properties);
    }
}
