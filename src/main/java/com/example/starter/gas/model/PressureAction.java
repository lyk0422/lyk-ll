package com.example.starter.gas.model;

/**
 * 管网压力联动动作。
 * HOLD：压力合格不调节；RAISE：低于下限需升压；LOWER：高于上限需泄压。
 */
public enum PressureAction {
    HOLD,
    RAISE,
    LOWER
}
