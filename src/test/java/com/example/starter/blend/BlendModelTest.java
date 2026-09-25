package com.example.starter.blend;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.blend.BlendModel.ComponentInput;
import com.example.starter.blend.BlendModel.EdgeHint;
import com.example.starter.blend.BlendModel.IndicatorSet;
import com.example.starter.blend.BlendModel.SpecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成调合模型单元测试：线性加权、恰等上限判合格、超限判不合格、卡边率提示。
 */
class BlendModelTest {

    private static final SpecLimits SPEC = new SpecLimits(
            bd("92.0"), bd("10.0"), bd("60.0"),
            bd("1.0"), bd("2.0"), bd("5.0"));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static ComponentInput tank(String code, String qty, String oct, String sul, String rvp) {
        return new ComponentInput(code, bd(qty), bd(oct), bd(sul), bd(rvp));
    }

    @Test
    void 线性加权计算成品指标() {
        IndicatorSet ind = BlendModel.blend(List.of(
                tank("TA", "500", "90.0", "8.0", "50.0"),
                tank("TB", "500", "94.0", "12.0", "60.0")));
        assertEquals(0, bd("92.000").compareTo(ind.octane()));
        assertEquals(0, bd("10.0000").compareTo(ind.sulfur()));
        assertEquals(0, bd("55.000").compareTo(ind.rvp()));
    }

    @Test
    void 指标恰等于规格上限判合格() {
        IndicatorSet exact = new IndicatorSet(bd("92.000"), bd("10.0000"), bd("60.000"));
        assertTrue(BlendModel.passes(exact, SPEC));
    }

    @Test
    void 任一指标超过上限判不合格() {
        assertFalse(BlendModel.passes(new IndicatorSet(bd("92.001"), bd("9.0"), bd("55.0")), SPEC));
        assertFalse(BlendModel.passes(new IndicatorSet(bd("91.0"), bd("10.0001"), bd("55.0")), SPEC));
        assertFalse(BlendModel.passes(new IndicatorSet(bd("91.0"), bd("9.0"), bd("60.001")), SPEC));
    }

    @Test
    void 实际值低于上限减安全边际时给出卡边率提示但不判不合格() {
        // 辛烷值 90 < 92-1=91，蒸气压 50 < 60-5=55，硫含量 8 恰等于 10-2=8 不触发
        IndicatorSet ind = new IndicatorSet(bd("90.0"), bd("8.0"), bd("50.0"));
        assertTrue(BlendModel.passes(ind, SPEC));
        List<EdgeHint> hints = BlendModel.edgeHints(ind, SPEC);
        assertEquals(2, hints.size());
        EdgeHint octane = hints.stream().filter(h -> h.indicator().equals("octane")).findFirst().orElseThrow();
        assertEquals(0, bd("97.83").compareTo(octane.edgeRatioPct()));
        EdgeHint rvp = hints.stream().filter(h -> h.indicator().equals("rvp")).findFirst().orElseThrow();
        assertEquals(0, bd("83.33").compareTo(rvp.edgeRatioPct()));
    }

    @Test
    void 实际值处于安全边际内不触发卡边提示() {
        IndicatorSet ind = new IndicatorSet(bd("91.5"), bd("9.0"), bd("58.0"));
        assertTrue(BlendModel.edgeHints(ind, SPEC).isEmpty());
    }

    @Test
    void 安全边际为零的指标不做卡边控制() {
        SpecLimits noMargin = new SpecLimits(bd("92.0"), bd("10.0"), bd("60.0"),
                bd("0"), bd("0"), bd("0"));
        IndicatorSet ind = new IndicatorSet(bd("80.0"), bd("1.0"), bd("30.0"));
        assertTrue(BlendModel.edgeHints(ind, noMargin).isEmpty());
    }
}
