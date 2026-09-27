package com.example.starter.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反复潜水余量积分规则与结构化指纹的纯单元测试，不启动 Spring。
 */
class ResidualRulesUnitTest {

    private static final Instant S0 = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void firstDivePointsZero_andExitResidualIsBottomMinutesCappedAt100() {
        assertEquals(0, ResidualRules.decayedPoints(0, Duration.ofMinutes(0)));
        assertEquals(50, ResidualRules.exitResidual(0, 50));
        assertEquals(100, ResidualRules.exitResidual(60, 40), "0+40 正常加和");
        assertEquals(100, ResidualRules.exitResidual(80, 50), "80+50=130 触顶 100");
        assertTrue(ResidualRules.exceedsCap(80, 21));
        assertFalse(ResidualRules.exceedsCap(80, 20), "恰 100 合法");
    }

    @Test
    void surfaceDecay_everyFull30MinutesRemovesFivePoints_floorZero() {
        // 出水残留 50：29 分钟零头不消减
        assertEquals(50, ResidualRules.decayedPoints(50, Duration.ofMinutes(29)));
        // 恰满 30 分钟消减 5
        assertEquals(45, ResidualRules.decayedPoints(50, Duration.ofMinutes(30)));
        // 59 分钟仍只算 1 个整块
        assertEquals(45, ResidualRules.decayedPoints(50, Duration.ofMinutes(59)));
        // 恰 60 分钟 2 块消减 10
        assertEquals(40, ResidualRules.decayedPoints(50, Duration.ofMinutes(60)));
        // 120 分钟 4 块消减 20
        assertEquals(30, ResidualRules.decayedPoints(50, Duration.ofMinutes(120)));
        // 消减不为负
        assertEquals(0, ResidualRules.decayedPoints(10, Duration.ofHours(5)));
        // 较大单位（小时）与整分钟一致
        assertEquals(30, ResidualRules.decayedPoints(50, Duration.ofHours(2)));
    }

    @Test
    void allowedMinutes_ndlMinusPoints_exactlyZeroRejected() {
        assertEquals(120, ResidualRules.allowedMinutes(120, 0));
        assertEquals(20, ResidualRules.allowedMinutes(50, 30));
        assertEquals(0, ResidualRules.allowedMinutes(50, 50));
        assertTrue(ResidualRules.noAllowedTime(50, 50), "允许分钟恰 0 拒绝新方案");
        assertTrue(ResidualRules.noAllowedTime(9, 80));
        assertFalse(ResidualRules.noAllowedTime(50, 49), "允许 1 分钟仍可新方案，恰用尽合法");
    }

    @Test
    void intervalGate_exactlySixtyPasses_fiftyNineRejected() {
        assertEquals(59, ResidualRules.surfaceIntervalMinutes(S0, S0.plusSeconds(60 * 59)));
        assertEquals(60, ResidualRules.surfaceIntervalMinutes(S0, S0.plusSeconds(60 * 60)));
        assertTrue(ResidualRules.surfaceIntervalMinutes(S0, S0.plusSeconds(60 * 60))
                >= ResidualRules.MIN_SURFACE_INTERVAL_MIN);
        assertFalse(ResidualRules.surfaceIntervalMinutes(S0, S0.plusSeconds(60 * 59))
                >= ResidualRules.MIN_SURFACE_INTERVAL_MIN);
    }

    @Test
    void structuredFingerprint_stableAndKeySorted() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("requestId", "K1");
        a.put("depthM", 18);
        a.put("bottomMin", 20);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("bottomMin", 20);
        b.put("depthM", 18);
        b.put("requestId", "K1");
        assertEquals(Fingerprints.sha256Structured(a), Fingerprints.sha256Structured(b));

        Map<String, Object> changed = new LinkedHashMap<>(a);
        changed.put("bottomMin", 21);
        assertNotEquals(Fingerprints.sha256Structured(a), Fingerprints.sha256Structured(changed));
    }

    /**
     * 分隔符免疫：合法文本值内含 '|'、':'、类型标签等，长度前缀保证无法与其他参数碰撞。
     */
    @Test
    void structuredFingerprint_unambiguousEvenWithDelimitersInText() {
        // 单字段 reason，文本中伪造标签与长度前缀
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("reason", "S|3:abc");
        Map<String, Object> y = new LinkedHashMap<>();
        y.put("reason", "abc");
        assertNotEquals(Fingerprints.sha256Structured(x), Fingerprints.sha256Structured(y));

        // 利用旧的分隔符规范化编码会碰撞的两组参数，在长度前缀编码下必须不同：
        // 组1：单键 "a" 值 "b,c=1"；组2：键 "a" 值 "b" 且键 "c" 值 1
        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("a", "b,c=1");
        Map<String, Object> g2 = new LinkedHashMap<>();
        g2.put("a", "b");
        g2.put("c", 1);
        assertNotEquals(Fingerprints.sha256Structured(g1), Fingerprints.sha256Structured(g2));

        // reason 内含请求中所有真实分隔符，仍与相近文本区分
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("reason", "x|y:z{w}[v],k=v");
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("reason", "x|y:z{w}[v],k=v ");
        assertNotEquals(Fingerprints.sha256Structured(r1), Fingerprints.sha256Structured(r2));
        assertEquals(Fingerprints.sha256Structured(r1), Fingerprints.sha256Structured(r1));
    }
}
