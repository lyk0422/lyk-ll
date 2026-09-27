package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SupervisoryInspectionRequest;

/**
 * 幂等指纹纯函数测试：长度前缀结构化编码，合法文本含分隔符也不产生歧义。
 */
class IdempotencyFingerprintTest {

    private final Idempotency.Executor executor = new Idempotency.Executor(null, null);

    @Test
    @DisplayName("同参数指纹稳定一致")
    void sameFieldsSameFingerprint() {
        IssueCertificateRequest a = new IssueCertificateRequest(
                "req-1", 7L, Instant.parse("2026-09-26T01:00:00Z"), null);
        IssueCertificateRequest b = new IssueCertificateRequest(
                "req-1", 7L, Instant.parse("2026-09-26T01:00:00Z"), null);
        assertEquals(executor.fingerprint("req-1", a), executor.fingerprint("req-1", b));
    }

    @Test
    @DisplayName("含分隔符的单字段文本与拆成两个字段的结构指纹不同")
    void delimiterInsideTextIsUnambiguous() {
        // 同一 requestId：一个清单项名称含分隔符 vs 两个清单项名称拼接，朴素拼接会碰撞
        SupervisoryInspectionRequest single = new SupervisoryInspectionRequest(
                "k", 1L, "张三", "李四",
                List.of(new CheckItem("压杠|安全带", true)));
        SupervisoryInspectionRequest split = new SupervisoryInspectionRequest(
                "k", 1L, "张三", "李四",
                List.of(new CheckItem("压杠", true), new CheckItem("安全带", true)));
        assertNotEquals(executor.fingerprint("k", single), executor.fingerprint("k", split));
    }

    @Test
    @DisplayName("版本、集合、布尔与精确时刻任一不同即产生不同指纹")
    void everyBusinessFieldContributes() {
        Instant t = Instant.parse("2026-09-26T01:00:00Z");
        String base = executor.fingerprint("k",
                new IssueCertificateRequest("k", 1L, t, null));
        assertNotEquals(base, executor.fingerprint("k",
                new IssueCertificateRequest("k", 2L, t, null)));
        assertNotEquals(base, executor.fingerprint("k",
                new IssueCertificateRequest("k", 1L, t.plusSeconds(1), null)));
        assertNotEquals(base, executor.fingerprint("k",
                new IssueCertificateRequest("k", 1L, t,
                        t.plusSeconds(365L * 24L * 3600L))));
        assertNotEquals(base, executor.fingerprint("other",
                new IssueCertificateRequest("other", 1L, t, null)));
    }

    @Test
    @DisplayName("中文多字节文本按UTF-8字节长度编码，不同内容不同指纹")
    void utf8LengthPrefix() {
        SupervisoryInspectionRequest a = new SupervisoryInspectionRequest(
                "k", 1L, "张三", "李四", List.of(new CheckItem("压杠", true)));
        SupervisoryInspectionRequest b = new SupervisoryInspectionRequest(
                "k", 1L, "张三", "王五", List.of(new CheckItem("压杠", true)));
        assertNotEquals(executor.fingerprint("k", a), executor.fingerprint("k", b));
    }
}
