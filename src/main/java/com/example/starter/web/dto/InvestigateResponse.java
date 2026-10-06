package com.example.starter.web.dto;

/**
 * 初查处置响应。
 *
 * @param eventId          事件主键
 * @param status           处置后事件状态
 * @param conclusion       初查结论
 * @param directiveId      关联功率指令主键；未确认干扰为 null
 * @param effectiveLimitW  处置后发射机有效限值，单位 W；未确认干扰为 null
 * @param idempotentReplay 是否为幂等重放
 */
public record InvestigateResponse(long eventId, String status, String conclusion,
                                  Long directiveId, Integer effectiveLimitW, boolean idempotentReplay) {
}
