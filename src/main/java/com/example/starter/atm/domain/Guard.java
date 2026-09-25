package com.example.starter.atm.domain;

/**
 * 押运员（合成数据）。
 *
 * @param frozen 责任人冻结：true 表示长短款逾期升级，禁止接新押运打卡任务
 */
public record Guard(
        Long id,
        String guardNo,
        String name,
        boolean frozen,
        long createdAt
) {
}
