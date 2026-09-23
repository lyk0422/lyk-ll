package com.example.starter.audit.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 摘要与规范化 JSON 工具：键排序保证相同逻辑内容得到稳定哈希，
 * 不依赖时间戳或 Map 迭代顺序。
 */
@Component
public class DigestSupport {

    private final ObjectMapper canonicalMapper;

    public DigestSupport() {
        this.canonicalMapper = new ObjectMapper();
        this.canonicalMapper.configure(
                com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /** 按键排序的规范化 JSON 文本，用作稳定哈希输入。 */
    public String canonicalJson(Map<String, ?> data) {
        try {
            return canonicalMapper.writeValueAsString(new TreeMap<>(data));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("指标无法序列化为规范化 JSON", e);
        }
    }

    /** 将摘要前 16 字节转为确定性 long，用于播种伪随机数。 */
    public long seedFromDigest(String digest) {
        long value = 0;
        for (int i = 0; i < 16; i++) {
            value = (value << 4) ^ digest.charAt(i);
        }
        return value;
    }
}
