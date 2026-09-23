package com.example.starter.rosbridge.demo;

import com.example.starter.rosbridge.adapter.SyntheticRosAdapter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 本地演示入口：应用启动后在合成 ROS 适配器上为 {@code /synthetic_chatter}
 * 开启 1Hz 合成 Topic 心跳，使本地无需真实 ROS 即可订阅并观察 PUBLISH 帧。
 *
 * <p>真实接入 ROS 时用自定义 {@link com.example.starter.rosbridge.adapter.RosAdapter}
 * Bean 替换合成适配器即可，本类仅依赖合成实现，不影响协议逻辑。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoHeartbeatInitializer implements ApplicationRunner {

    /** 本地演示 Topic；与示例帧文档保持一致。 */
    public static final String DEMO_TOPIC = "/synthetic_chatter";

    private final SyntheticRosAdapter syntheticRosAdapter;

    public DemoHeartbeatInitializer(SyntheticRosAdapter syntheticRosAdapter) {
        this.syntheticRosAdapter = syntheticRosAdapter;
    }

    @Override
    public void run(ApplicationArguments args) {
        syntheticRosAdapter.enableHeartbeat(DEMO_TOPIC);
    }
}
