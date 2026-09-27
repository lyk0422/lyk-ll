package com.example.starter.tank;

import com.example.starter.web.VersionConflictException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 储罐域共享校验：三位小数千克规范化与乐观锁版本裁决。
 */
final class TankSupport {

    private TankSupport() {
    }

    /**
     * 千克数值须不超过三位小数；合法值统一规范为三位小数标度，
     * 整数或小数文本数值等价（10、10.0、10.000）按规范值处理。
     */
    static BigDecimal kg(BigDecimal value, String field) {
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(field + " must have at most 3 decimal places: " + value);
        }
        return value.setScale(3, RoundingMode.UNNECESSARY);
    }

    /**
     * 乐观锁版本裁决：expectedVersion 与当前版本不一致抛 409。
     */
    static void requireVersion(Tank tank, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != tank.version()) {
            throw new VersionConflictException(expectedVersion == null ? -1 : expectedVersion,
                    tank.version());
        }
    }
}
