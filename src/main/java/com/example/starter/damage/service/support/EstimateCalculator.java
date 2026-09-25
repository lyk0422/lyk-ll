package com.example.starter.damage.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.damage.api.EstimateDecision;
import com.example.starter.damage.repo.po.DamageItemPo;

/** 估价复算：残损清单 → 责任小计、最大单项、占箱值比例与修理/全损唯一判定。 */
public final class EstimateCalculator {

    /** 全损阈值比例：单项修理费 &gt; 箱值 30% 判全损，恰 30% 修理。 */
    public static final BigDecimal TOTAL_LOSS_THRESHOLD = new BigDecimal("0.30");

    private EstimateCalculator() {
    }

    /** 估价复算结果。 */
    public record Result(
            BigDecimal subtotalOwner,
            BigDecimal subtotalTerminal,
            BigDecimal totalAmount,
            BigDecimal maxItemAmount,
            BigDecimal ratio,
            EstimateDecision decision) {
    }

    public static Result calculate(List<DamageItemPo> items, BigDecimal containerValue) {
        BigDecimal owner = BigDecimal.ZERO.setScale(2);
        BigDecimal terminal = BigDecimal.ZERO.setScale(2);
        BigDecimal maxItem = BigDecimal.ZERO.setScale(2);
        for (DamageItemPo item : items) {
            BigDecimal amount = item.amount();
            if ("OWNER".equals(item.responsibility())) {
                owner = owner.add(amount);
            } else {
                terminal = terminal.add(amount);
            }
            if (amount.compareTo(maxItem) > 0) {
                maxItem = amount;
            }
        }
        BigDecimal total = owner.add(terminal);
        BigDecimal ratio = maxItem.divide(containerValue, 6, RoundingMode.HALF_UP);
        BigDecimal threshold = containerValue.multiply(TOTAL_LOSS_THRESHOLD)
                .setScale(2, RoundingMode.HALF_UP);
        EstimateDecision decision = maxItem.compareTo(threshold) > 0
                ? EstimateDecision.TOTAL_LOSS
                : EstimateDecision.REPAIR;
        return new Result(owner, terminal, total, maxItem, ratio, decision);
    }
}
