package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 环控指令类型。加热与补光为耗能指令，下发前须校验当日剩余能耗预算；通风为零能耗指令，
 * 预算恰用尽后仍允许下发。
 */
public enum CommandType {
    /** 加热（耗能）。 */
    HEAT(true),
    /** 补光（耗能）。 */
    SUPPLEMENT_LIGHT(true),
    /** 通风（零能耗，预算耗尽后唯一允许的指令类型）。 */
    VENTILATE(false);

    private final boolean energyConsuming;

    CommandType(boolean energyConsuming) {
        this.energyConsuming = energyConsuming;
    }

    public boolean isEnergyConsuming() {
        return energyConsuming;
    }

    /**
     * 解析指令类型并约束能耗取值：耗能指令必须给出正的能耗，通风能耗一律按 0 计量。
     */
    public BigDecimal normalizeEnergy(BigDecimal requestedEnergy) {
        if (energyConsuming) {
            if (requestedEnergy == null || requestedEnergy.signum() <= 0) {
                throw new IllegalArgumentException("耗能指令必须携带正的能耗值(kWh)：" + name());
            }
            return requestedEnergy;
        }
        return BigDecimal.ZERO.setScale(3);
    }
}
