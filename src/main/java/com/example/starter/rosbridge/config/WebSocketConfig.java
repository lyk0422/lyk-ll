package com.example.starter.rosbridge.config;

import com.example.starter.rosbridge.gateway.RosBridgeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 rosbridge WebSocket 端点 /rosbridge。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final com.example.starter.rosbridge.gateway.GatewayCore gatewayCore;

    public WebSocketConfig(com.example.starter.rosbridge.gateway.GatewayCore gatewayCore) {
        this.gatewayCore = gatewayCore;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RosBridgeWebSocketHandler(gatewayCore), "/rosbridge").setAllowedOrigins("*");
    }
}
