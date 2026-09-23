package com.example.starter.rosbridge.ws;

import com.example.starter.rosbridge.gateway.GatewayProperties;
import com.example.starter.rosbridge.gateway.GatewayService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 rosbridge WebSocket 端点，路径由 {@code rosbridge.path} 配置（默认 /rosbridge），
 * 允许任意来源，便于本地与测试客户端直连。
 */
@Configuration
@EnableWebSocket
public class RosbridgeWebSocketConfig implements WebSocketConfigurer {

    private final GatewayService gateway;
    private final GatewayProperties props;

    public RosbridgeWebSocketConfig(GatewayService gateway, GatewayProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RosbridgeWebSocketHandler(gateway), path())
                .setAllowedOriginPatterns("*");
    }

    private String path() {
        String p = props.path();
        return p == null || p.isBlank() ? "/rosbridge" : p;
    }
}
