package com.example.starter.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求指纹：对操作类型与规范化参数 JSON 做 SHA-256，
 * 覆盖授权版本、窗口、排期集合与预期版本。
 */
@Component
public class Fingerprinter {

    private final ObjectMapper objectMapper;

    public Fingerprinter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(String operation, Object payload) {
        try {
            String canonical = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((operation + "|" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("failed to build request fingerprint", ex);
        }
    }
}
