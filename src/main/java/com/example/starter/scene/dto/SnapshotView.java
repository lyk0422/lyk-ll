package com.example.starter.scene.dto;

import java.util.List;

/**
 * 某一版本的 Planning Scene 不可变快照视图。
 *
 * @param version 快照对应的场景版本号
 * @param joints 关节状态列表
 * @param worldObjects 世界碰撞对象列表
 * @param attachedObjects 附着物列表
 */
public record SnapshotView(long version,
                           List<JointState> joints,
                           List<WorldObjectView> worldObjects,
                           List<AttachedObjectView> attachedObjects) {

    /**
     * 关节状态快照项。
     *
     * @param name 关节名
     * @param position 位置，单位弧度
     * @param minLimit 位置下限，单位弧度
     * @param maxLimit 位置上限，单位弧度
     */
    public record JointState(String name, double position, double minLimit, double maxLimit) {
    }

    /**
     * 世界碰撞对象快照项。
     *
     * @param id 对象 ID
     * @param frameId 位姿参考坐标系
     * @param pose 位姿
     * @param size 盒体尺寸，单位米
     */
    public record WorldObjectView(String id, String frameId, Pose pose, Size3 size) {
    }

    /**
     * 附着物快照项。
     *
     * @param id 对象 ID
     * @param linkName 附着的机器人 Link
     * @param pose 相对 Link 的位姿
     * @param size 盒体尺寸，单位米
     */
    public record AttachedObjectView(String id, String linkName, Pose pose, Size3 size) {
    }
}
