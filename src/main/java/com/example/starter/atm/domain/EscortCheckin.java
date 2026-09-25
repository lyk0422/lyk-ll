package com.example.starter.atm.domain;

/**
 * 押运打卡记录。两名不同押运员先后打卡；seq 按打卡时间定序（1=首人，2=次人）。
 */
public record EscortCheckin(
        Long id,
        long planId,
        long guardId,
        int seq,
        long checkinAt,
        long createdAt
) {
}
