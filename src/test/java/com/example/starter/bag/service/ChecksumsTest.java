package com.example.starter.bag.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 校验工具纯单元测试：已知 SHA-256 向量、空负载与链式规则。
 */
class ChecksumsTest {

    @Test
    void sha256MatchesKnownVector() {
        // 空串 SHA-256 标准向量
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Checksums.sha256(new byte[0]));
        // "abc" SHA-256 标准向量
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Checksums.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void nullPayloadTreatedAsEmpty() {
        assertEquals(Checksums.sha256(null), Checksums.sha256(new byte[0]));
    }

    @Test
    void chainIsOrderSensitiveAndDeterministic() {
        String h1 = Checksums.chain("", "t", 0, 100L, "type",
                Checksums.sha256("a".getBytes(StandardCharsets.UTF_8)));
        String h1Again = Checksums.chain("", "t", 0, 100L, "type",
                Checksums.sha256("a".getBytes(StandardCharsets.UTF_8)));
        assertEquals(h1, h1Again);

        String h2 = Checksums.chain(h1, "t", 1, 101L, "type",
                Checksums.sha256("b".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(h1, h2);

        // 交换顺序链值必须不同（顺序敏感性）
        String swapped2 = Checksums.chain(
                Checksums.chain("", "t", 1, 101L, "type",
                        Checksums.sha256("b".getBytes(StandardCharsets.UTF_8))),
                "t", 0, 100L, "type",
                Checksums.sha256("a".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(h2, swapped2);
    }
}
