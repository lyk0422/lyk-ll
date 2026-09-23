package com.example.starter.rosbridge.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 协议层共享 JSON 映射器（线程安全）。
 */
public final class ProtocolJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtocolJson() {
    }
}
