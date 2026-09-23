package com.example.starter.rosbridge.gateway;

/**
 * WebSocket 传输与会话核心逻辑之间的边界，便于脱离真实容器做单元测试。
 */
public interface ClientTransport {

    /** 连接所属的客户端标识。 */
    String clientId();

    /** 底层会话是否仍打开。 */
    boolean isOpen();

    /** 发送一帧文本；发送失败由实现处理并返回 false。 */
    boolean sendText(String text);

    /** 关闭底层连接。 */
    void close();
}
