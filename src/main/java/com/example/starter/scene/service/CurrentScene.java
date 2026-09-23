package com.example.starter.scene.service;

import com.example.starter.scene.model.RobotPose;

import java.util.Map;

/**
 * 当前场景的内存只读视图，供服务层做整批校验与变更计算。
 *
 * @param version     当前版本号
 * @param pose        机器人基座位姿
 * @param joints      关节名 -> 位置（弧度）
 * @param objects     对象 ID -> 对象数据（frameId 为 null 表示处于附着状态）
 * @param attachments 对象 ID -> 附着 Link 名
 */
public record CurrentScene(
        long version,
        RobotPose pose,
        Map<String, Double> joints,
        Map<String, SceneObject> objects,
        Map<String, String> attachments) {

    /**
     * 当前碰撞对象数据。
     *
     * @param frameId        世界参考坐标系；附着期间为 null
     * @param payload        几何负载 JSON 原文，可为 null
     * @param updatedVersion 最后变化版本号
     */
    public record SceneObject(String frameId, String payload, long updatedVersion) {
    }
}
