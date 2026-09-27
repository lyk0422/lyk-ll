package com.example.starter.support;

import com.example.starter.domain.ScoreRules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反复潜水虚构积分规则的纯单元测试（不启动 Spring；不用于真实潜水减压）。
 */
class ScoreRulesUnitTest {

    private static final Instant SURFACED = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void firstDiveScoreIsZero_andSurfaceDecayEveryFullThirtyMinutes() {
        // 首次：残留 0、无出水时刻 -> 当前 0
        assertEquals(0, ScoreRules.currentScore(0, 0));
        // 残留 50：水面 29 分钟不减；恰 30 减 5；59 仍减一次；恰 60 减 10
        assertEquals(50, ScoreRules.currentScore(50, 29));
        assertEquals(45, ScoreRules.currentScore(50, 30));
        assertEquals(45, ScoreRules.currentScore(50, 59));
        assertEquals(40, ScoreRules.currentScore(50, 60));
        // 最低 0，不出现负分
        assertEquals(0, ScoreRules.currentScore(5, 60));
        assertEquals(0, ScoreRules.currentScore(0, 120));
    }

    @Test
    void surfaceInterval_exactlySixtyPasses_fiftyNineRejected() {
        assertFalse(ScoreRules.intervalPassed(59));
        assertTrue(ScoreRules.intervalPassed(60));
        assertTrue(ScoreRules.intervalPassed(61));
    }

    @Test
    void allowedMinutesIsNdlMinusCurrentScore_teamTakesShorter() {
        // 18 米 NDL=50：当前积分 0 允许 50；积分 50 允许 0（业务上 <=0 拒绝新方案）
        assertEquals(50, ScoreRules.allowedMinutes(50, 0));
        assertEquals(0, ScoreRules.allowedMinutes(50, 50));
        assertEquals(-5, ScoreRules.allowedMinutes(50, 55));
        // 两人取较短：50 与 30 -> 30
        assertEquals(30, Math.min(ScoreRules.allowedMinutes(50, 0),
                ScoreRules.allowedMinutes(30, 0)));
    }

    @Test
    void residualIsCurrentPlusBottom_exactlyOneHundredAllowed() {
        assertEquals(100, ScoreRules.residualAfterDive(60, 40));
        assertTrue(ScoreRules.residualWithinCap(100));
        assertFalse(ScoreRules.residualWithinCap(101));
    }

    @Test
    void surfaceMinutesDerivesFromLastSurfacedInstant() {
        assertEquals(60, ScoreRules.surfaceMinutes(SURFACED,
                SURFACED.plusSeconds(60L * 60)));
        // 不满整分钟向下取整
        assertEquals(29, ScoreRules.surfaceMinutes(SURFACED,
                SURFACED.plusSeconds(60L * 30 - 1)));
        // 方案时刻早于出水时刻（异常）归 0
        assertEquals(0, ScoreRules.surfaceMinutes(SURFACED, SURFACED.minusSeconds(3600)));
        assertEquals(0, ScoreRules.surfaceMinutes(null, SURFACED));
    }

    @Test
    void fingerprintDistinguishesTextContainingDelimiters() {
        // 工号/键名中含 | = , { } 等分隔符：长度定界编码必须区分这两组朴素拼接会歧义的参数
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("a", "x|y");
        a.put("b", "z");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "x");
        b.put("b", "y|z");
        assertNotEquals(Fingerprints.sha256(a), Fingerprints.sha256(b),
                "含分隔符 | 的文本不能与另一字段划分混淆");

        // 单字段文本中塞入伪造的键值结构也不能等价于真实的两字段结构
        Map<String, Object> injected = new LinkedHashMap<>();
        injected.put("k", "v,b=fake");
        Map<String, Object> realTwo = new LinkedHashMap<>();
        realTwo.put("k", "v");
        realTwo.put("b", "fake");
        assertNotEquals(Fingerprints.sha256(injected), Fingerprints.sha256(realTwo),
                "含逗号与等号的文本不能注入伪造字段");

        // requestId 参与指纹且嵌套集合同样结构化
        Map<String, Object> versions1 = Map.of("D001", 1, "D002", 2);
        Map<String, Object> versions2 = Map.of("D001", 2, "D002", 1);
        Map<String, Object> f1 = new LinkedHashMap<>();
        f1.put("op", "CONTROLLED_PLAN");
        f1.put("expectedVersions", versions1);
        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("op", "CONTROLLED_PLAN");
        f2.put("expectedVersions", versions2);
        assertNotEquals(Fingerprints.sha256(f1), Fingerprints.sha256(f2),
                "版本集合元素互换必须产生不同指纹");
    }
}
