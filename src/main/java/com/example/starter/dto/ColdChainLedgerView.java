package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 冷链运输台账视图：展示当前版本证据、缺口/越界点及合格冻结版本。
 *
 * @param batchNo        子批评号
 * @param status         当前版本温证状态：QUALIFIED/UNQUALIFIED
 * @param version        当前证据版本号（登记为 1，补录/更正成功加 1）
 * @param certVersion    最近一次合格冻结的证据版本；仅当等于 version 时温证有效，空值表示从未合格
 * @param certValid      温证当前是否有效（签发门禁据此放行）
 * @param transportStart 完整运输起点 UTC 精确时刻
 * @param transportEnd   完整运输终点 UTC 精确时刻
 * @param submittedAt    当前版本证据提交精确时刻（UTC）
 * @param qualifiedAt    当前版本合格冻结时刻，空值表示当前版本未合格
 * @param segments       当前版本冻结的运输片段及读数
 * @param violations     当前版本核验缺口/越界点（空列表表示证据完整合格）
 */
public record ColdChainLedgerView(
        String batchNo,
        String status,
        int version,
        Integer certVersion,
        boolean certValid,
        Instant transportStart,
        Instant transportEnd,
        Instant submittedAt,
        Instant qualifiedAt,
        List<ColdChainSegmentView> segments,
        List<ColdChainViolationView> violations
) {
}
