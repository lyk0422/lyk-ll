package com.example.starter.portability.model;

import java.time.Instant;

/**
 * 用户资格档案（合成数据），对应 subscriber_profile 表。
 *
 * @param hasArrears 是否存在欠费
 * @param inContract 是否处于在网约定期限内
 * @param lastPortAt 上次携转完成时刻（UTC）；null 表示从未携转
 */
public record SubscriberProfile(
        String msisdn,
        boolean hasArrears,
        boolean inContract,
        Instant lastPortAt) {
}
