package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 塔机。ratedLoadTonnes 为额定载荷（吨），吊重超过该值的授权申请即拒绝。
 */
public record Crane(String id, String name, BigDecimal ratedLoadTonnes) {
}
