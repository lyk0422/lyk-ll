package com.example.starter.lc.domain;

/**
 * 交单批次状态。
 * <ul>
 *   <li>{@link #UNDER_REVIEW} 审单中；</li>
 *   <li>{@link #REJECTED} 已在审单期内一次性发出拒付通知，单据状态锁定；</li>
 *   <li>{@link #DEEMED_ACCEPTED} 超过第5个银行工作日17:00未通知，视为接受；</li>
 *   <li>{@link #ACCEPTED} 已接受（含申请人放弃全部不符点后银行接受），待付款；</li>
 *   <li>{@link #PAID} 已付款，终态；</li>
 *   <li>{@link #REFUNDED} 拒付后无人放弃，已退单，终态；</li>
 *   <li>{@link #SUPERSEDED} 被受益人改单重交的新批次取代。</li>
 * </ul>
 */
public enum PresentationStatus {
    UNDER_REVIEW,
    REJECTED,
    DEEMED_ACCEPTED,
    ACCEPTED,
    PAID,
    REFUNDED,
    SUPERSEDED
}
