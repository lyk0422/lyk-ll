package com.example.starter.costmap.domain;

/**
 * Nav2 inflation layer 风格的膨胀代价计算。
 *
 * <p>代价取值 0..254：254 致命障碍、253 内切障碍、1..252 按指数衰减、0 无影响。
 * 公式对齐 Nav2 costmap inflation_layer：
 * {@code cost = 252 * exp(-costScalingFactor * (distance - inscribedRadius))}，
 * 本实现再 +1 截断到 [1,252] 使有效膨胀区内代价恒为正。</p>
 */
public final class Inflation {

    /** 致命障碍代价。 */
    public static final int LETHAL = 254;

    /** 内切半径内代价。 */
    public static final int INSCRIBED = 253;

    private Inflation() {
    }

    /**
     * 按 Nav2 公式计算距最近致命障碍 {@code distance} 个单元格处的膨胀代价。
     *
     * <p>{@code distance <= 0} 返回 254；{@code 0 < distance <= inscribedRadius}
     * 返回 253；内切半径之外、膨胀半径之内按指数衰减并截断到 [1,252]；
     * 膨胀半径之外（含参数退化：内切半径大于等于膨胀半径时）返回 0。</p>
     */
    public static int inflateCost(double distance,
                                  double inscribedRadius,
                                  double inflationRadius,
                                  double costScalingFactor) {
        if (distance <= 0.0d) {
            return LETHAL;
        }
        if (distance <= inscribedRadius) {
            return INSCRIBED;
        }
        if (distance > inflationRadius) {
            return 0;
        }
        double value = 252.0d * Math.exp(-costScalingFactor * (distance - inscribedRadius));
        int rounded = (int) Math.round(value) + 1;
        return Math.max(1, Math.min(252, rounded));
    }
}
