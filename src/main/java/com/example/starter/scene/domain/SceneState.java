package com.example.starter.scene.domain;

import com.example.starter.scene.model.SceneApi;

import java.util.Map;

/**
 * 当前 Planning Scene 在内存中的工作副本（从数据库当前版本加载）。
 *
 * @param version   当前场景版本号
 * @param robotPose 机器人姿态；未设置时为 null
 * @param joints    关节名 -> 当前角度（弧度）
 * @param objects   对象 ID -> 对象状态；attachedLink 非空表示处于附着集合
 */
public record SceneState(
        long version,
        SceneApi.PoseData robotPose,
        Map<String, Double> joints,
        Map<String, SceneApi.ObjectSnapshot> objects) {
}
