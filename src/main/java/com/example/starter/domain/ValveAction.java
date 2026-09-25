package com.example.starter.domain;

/**
 * 阀门调节方向。
 * <ul>
 *   <li>{@link #OPEN} 开大阀门（欠流单元提高流量，回水温度下降方向）；</li>
 *   <li>{@link #CLOSE} 关小阀门（过流单元降低流量）。</li>
 * </ul>
 */
public enum ValveAction {
    OPEN,
    CLOSE
}
