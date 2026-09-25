package com.example.starter.crane.api;

import java.util.List;

/**
 * 位姿上报处理结果。
 *
 * @param duplicated        是否为重复读数（塔机＋时间戳已存在），重复读数不重复触发预警
 * @param warningsGenerated 本次处理新生成的越限预警编号
 * @param brakesIssued      本次处理新下发的制动指令编号
 */
public record PoseAcceptance(
        boolean duplicated,
        List<String> warningsGenerated,
        List<String> brakesIssued) {

    public static PoseAcceptance fresh(List<String> warnings, List<String> brakes) {
        return new PoseAcceptance(false, List.copyOf(warnings), List.copyOf(brakes));
    }

    public static PoseAcceptance duplicateReading() {
        return new PoseAcceptance(true, List.of(), List.of());
    }
}
