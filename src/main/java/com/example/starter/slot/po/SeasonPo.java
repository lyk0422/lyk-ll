package com.example.starter.slot.po;

/**
 * 航季协调元数据。
 *
 * @param id        主键
 * @param season    航季标识，如 S26
 * @param frozen    发布冻结标记：0 协调中，1 已发布冻结
 * @param frozenAt  发布时间（epoch 毫秒，UTC），未发布为 null
 * @param version   乐观锁版本号
 */
public record SeasonPo(
        Long id,
        String season,
        int frozen,
        Long frozenAt,
        long version) {
}
