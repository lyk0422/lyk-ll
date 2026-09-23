package com.example.starter.rosbridge.config;

import com.example.starter.rosbridge.ws.RosbridgeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 rosbridge WebSocket 端点，允许跨域以便本地页面/工具直连。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RosbridgeProperties properties;
    private final RosbridgeWebSocketHandler handler;

    public WebSocketConfig(RosbridgeProperties properties, RosbridgeWebSocketHandler handler) {
        this.properties = properties;
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, properties.getPath()).setAllowedOriginPatterns("*");
    }
}
