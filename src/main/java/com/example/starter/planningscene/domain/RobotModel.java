package com.example.starter.planningscene.domain;

import java.util.Map;
import java.util.Set;

/**
 * 静态机器人模型：Link 坐标系集合与关节范围。模型不属于版本化场景历史，提交只能引用已声明的 Link 和关节。
 *
 * @param links 全部 Link 名（坐标系），始终包含 world
 * @param joints 关节名 -> 下限/上限数组，[0]=下限，[1]=上限
 */
public record RobotModel(Set<String> links, Map<String, double[]> joints) {

    public RobotModel {
        links = Set.copyOf(links);
        joints = Map.copyOf(joints);
    }
}
