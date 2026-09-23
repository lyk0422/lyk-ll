package com.example.starter.planningscene.domain;

import java.util.Map;

/**
 * Planning Scene 某一历史版本的不可变快照。
 *
 * @param version 快照版本号
 * @param robotPose 机器人根姿态
 * @param joints 关节名 -> 关节状态
 * @param objects 对象 ID -> 对象状态
 */
public record SceneSnapshot(long version, Pose robotPose,
                            Map<String, JointState> joints,
                            Map<String, ObjectState> objects) {

    public SceneSnapshot {
        joints = Map.copyOf(joints);
        objects = Map.copyOf(objects);
    }
}
