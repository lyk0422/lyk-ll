package com.example.starter.planningscene.dto;

import java.util.List;

/**
 * 场景快照响应：版本号、机器人根姿态、关节列表与对象列表（含世界对象和附着物）。
 */
public record SceneViewResponse(
        long version,
        PoseDto robotPose,
        List<JointView> joints,
        List<ObjectView> objects) {

    /** 关节视图。 */
    public record JointView(String name, double position, double lowerBound, double upperBound) {
    }

    /** 对象视图；frameId/linkId 按归属集合二选一非空。 */
    public record ObjectView(String objectId, String type, String frameId, String linkId,
                             String geometry, PoseDto pose, List<String> touchLinks) {
    }
}
