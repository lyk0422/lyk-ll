package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 冷链运输台账视图：展示当前版本证据、缺口、越界点及温证冻结版本。
 * 缺样本以缺口形式列出，绝不当作零度读数。
 *
 * @param batchNo           受控子批评号
 * @param version           当前台账版本（登记为 1，每次成功补录/更正加 1）
 * @param qualifiedVersion  温证合格冻结的台账版本，空值表示从未合格
 * @param certificateValid  当前版本温证是否有效（冻结版本恰等于当前版本）
 * @param transportStartUtc 完整运输起点 UTC 精确时刻（冻结）
 * @param transportEndUtc   完整运输终点 UTC 精确时刻（冻结）
 * @param segments          当前版本片段及已提交读数
 * @param gaps              证据缺口描述（缺片、缺端点、间隔超 5 分钟等）
 * @param violations        温度越界点明细
 */
public record ColdChainView(
        String batchNo,
        int version,
        Integer qualifiedVersion,
        boolean certificateValid,
        Instant transportStartUtc,
        Instant transportEndUtc,
        List<ColdChainSegmentView> segments,
        List<String> gaps,
        List<ColdChainViolationView> violations
) {
}
