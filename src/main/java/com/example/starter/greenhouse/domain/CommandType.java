package com.example.starter.greenhouse.domain;

/**
 * 环控指令类型。HEATING 加热、LIGHTING 补光为耗能指令；VENTILATION 通风为零能耗指令。
 */
public enum CommandType {
    HEATING(true),
    LIGHTING(true),
    VENTILATION(false);

    private final boolean energyConsuming;

    CommandType(boolean energyConsuming) {
        this.energyConsuming = energyConsuming;
    }

    /** 是否消耗能耗预算。 */
    public boolean isEnergyConsuming() {
        return energyConsuming;
    }
}
