package com.example.starter.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 体积工具：题面体积单位为微升（µL），最多三位小数。
 *
 * <p>内部统一用纳升（nL）长整数存储，{@code 1 nL = 0.001 µL}，
 * 全部分装、占用、守恒计算只做整数运算，避免二进制浮点误差。</p>
 */
public final class Volume {

    /**
     * 1 微升对应的内部单位数量（千分之一微升即 1 纳升）。
     */
    public static final long SCALE = 1000L;

    private Volume() {
    }

    /**
     * 将最多三位小数的微升字符串解析为纳升整数。
     *
     * @param text       微升体积字符串
     * @param fieldName  字段名，用于错误提示
     * @return 纳升体积，恒为正
     * @throws BadRequestException 格式错误、超过三位小数或非正数
     */
    public static long parsePositiveNl(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException(fieldName + " 不能为空");
        }
        final BigDecimal value;
        try {
            value = new BigDecimal(text.trim()).stripTrailingZeros();
        } catch (NumberFormatException ex) {
            throw new BadRequestException(fieldName + " 不是合法体积: " + text);
        }
        if (value.signum() <= 0) {
            throw new BadRequestException(fieldName + " 必须为正数: " + text);
        }
        if (value.scale() > 3) {
            throw new BadRequestException(fieldName + " 最多允许三位小数: " + text);
        }
        return value.setScale(3, RoundingMode.UNNECESSARY)
                .multiply(BigDecimal.valueOf(SCALE))
                .longValueExact();
    }

    /**
     * 将纳升整数格式化为三位小数微升字符串（去除尾随零）。
     */
    public static String formatUl(long nl) {
        return BigDecimal.valueOf(nl, 3).stripTrailingZeros().toPlainString();
    }
}
