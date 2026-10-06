package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.starter.service.RiskModel;

/**
 * 合成风险评估模型单元测试：得分公式与等级阈值边界。
 */
class RiskModelTest {

    @Test
    void scoreCombinesFactors() {
        // 30*1.2 + 2*15 + 40*0.5 + 2*8 = 36+30+20+16 = 102
        assertEquals(102.0, RiskModel.score(30.0, 2, 40, 2), 1e-9);
    }

    @Test
    void ageIsCappedAtSixty() {
        assertEquals(RiskModel.score(0, 0, 60, 0), RiskModel.score(0, 0, 100, 0), 1e-9);
    }

    @Test
    void levelBoundaries() {
        assertEquals(1, RiskModel.level(70.0));
        assertEquals(2, RiskModel.level(69.999));
        assertEquals(2, RiskModel.level(45.0));
        assertEquals(3, RiskModel.level(44.999));
        assertEquals(3, RiskModel.level(25.0));
        assertEquals(4, RiskModel.level(24.999));
        assertEquals(4, RiskModel.level(0.0));
    }
}
