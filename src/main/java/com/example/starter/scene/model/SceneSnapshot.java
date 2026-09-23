package com.example.starter.scene.model;

import java.util.List;

/**
 * 场景快照视图，既可表示当前场景也可表示任意历史版本。
 *
 * @param version     快照版本号
 * @param requestId   产生该版本的请求 ID（版本 0 为 __initial__）
 * @param createdAt   提交时间（Unix 毫秒，UTC）
 * @param robotPose   该版本的机器人基座位姿
 * @param joints      该版本全部关节位置（弧度）
 * @param worldObjects 该版本位于世界集合的碰撞对象
 * @param attachments 该版本附着物（对象 ID -> Link 名）
 */
public record SceneSnapshot(
        long version,
        String requestId,
        long createdAt,
        RobotPose robotPose,
        List<JointStateView> joints,
        List<ObjectView> worldObjects,
        List<AttachmentView> attachments) {

    /**
     * 关节状态视图。
     */
    public record JointStateView(String jointName, double position) {
    }

    /**
     * 世界碰撞对象视图。
     */
    public record ObjectView(String objectId, String frameId, String payload) {
    }

    /**
     * 附着物视图。
     */
    public record AttachmentView(String objectId, String linkName) {
    }
}
