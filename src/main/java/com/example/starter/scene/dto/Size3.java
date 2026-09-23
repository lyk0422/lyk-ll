package com.example.starter.scene.dto;

/**
 * 盒体碰撞对象的三维尺寸，单位米，各边必须为正有限数。
 *
 * @param x 尺寸 X，单位米
 * @param y 尺寸 Y，单位米
 * @param z 尺寸 Z，单位米
 */
public record Size3(double x, double y, double z) {

    /**
     * 校验各边为正有限数。
     *
     * @return 全部为正有限数时返回 true
     */
    public boolean isPositiveFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && x > 0.0 && y > 0.0 && z > 0.0;
    }
}
