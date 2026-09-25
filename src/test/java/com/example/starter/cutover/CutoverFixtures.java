package com.example.starter.cutover;

import com.example.starter.cutover.dto.StepDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试数据构造工具：固定夜间窗口基准时间与合成设备。
 */
public final class CutoverFixtures {

    public static final String DEVICE_SW1 = "DEV-CORE-SW-01";
    public static final String DEVICE_SW2 = "DEV-CORE-SW-02";

    private CutoverFixtures() {
    }

    public static List<StepDefinition> steps(Object... nameAndType) {
        List<StepDefinition> list = new ArrayList<>();
        for (int i = 0; i < nameAndType.length; i += 2) {
            list.add(new StepDefinition((String) nameAndType[i],
                    StepType.valueOf((String) nameAndType[i + 1])));
        }
        return list;
    }
}
