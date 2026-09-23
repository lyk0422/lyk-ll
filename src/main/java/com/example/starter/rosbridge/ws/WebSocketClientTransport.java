package com.example.starter.rosbridge.ws;

import com.example.starter.rosbridge.gateway.ClientTransport;
import java.io.IOException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 基于 Spring {@link WebSocketSession} 的客户端传输实现。
 */
public class WebSocketClientTransport implements ClientTransport {

    private final WebSocketSession session;
    private volatile String clientId;

    public WebSocketClientTransport(WebSocketSession session) {
        this.session = session;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public boolean sendText(String text) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(text));
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    @Override
    public void close() {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException ignored) {
            // 关闭动作尽力而为
        }
    }
}
