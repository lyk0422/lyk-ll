package com.example.starter.planningscene;

import com.example.starter.planningscene.dto.JointValueDto;
import com.example.starter.planningscene.dto.ObjectOperationDto;
import com.example.starter.planningscene.domain.ObjectOperationType;
import com.example.starter.planningscene.dto.PoseDto;
import com.example.starter.planningscene.dto.SceneUpdateRequest;

import java.util.List;

/**
 * 测试请求构造工具。
 */
public final class SceneTestFixtures {

    private SceneTestFixtures() {
    }

    public static PoseDto pose(double x, double y, double z) {
        return new PoseDto(x, y, z, 0.0, 0.0, 0.0, 1.0);
    }

    public static JointValueDto joint(String name, double position) {
        return new JointValueDto(name, position);
    }

    public static ObjectOperationDto addWorld(String id, String frameId, String geometry, PoseDto pose) {
        return new ObjectOperationDto(ObjectOperationType.ADD, id, null, frameId, null,
                geometry, pose, null);
    }

    public static ObjectOperationDto addAttached(String id, String linkId, String geometry,
                                                 PoseDto pose, List<String> touchLinks) {
        return new ObjectOperationDto(ObjectOperationType.ADD, id, null, null, linkId,
                geometry, pose, touchLinks);
    }

    public static ObjectOperationDto update(String id, String frameId, String linkId,
                                            String geometry, PoseDto pose) {
        return new ObjectOperationDto(ObjectOperationType.UPDATE, id, null, frameId, linkId,
                geometry, pose, null);
    }

    public static ObjectOperationDto delete(String id) {
        return new ObjectOperationDto(ObjectOperationType.DELETE, id, null, null, null,
                null, null, null);
    }

    public static ObjectOperationDto rename(String id, String newId) {
        return new ObjectOperationDto(ObjectOperationType.RENAME, id, newId, null, null,
                null, null, null);
    }

    public static ObjectOperationDto attach(String id, String linkId, List<String> touchLinks) {
        return new ObjectOperationDto(ObjectOperationType.ATTACH, id, null, null, linkId,
                null, null, touchLinks);
    }

    public static ObjectOperationDto detach(String id, String frameId) {
        return new ObjectOperationDto(ObjectOperationType.DETACH, id, null, frameId, null,
                null, null, null);
    }

    public static SceneUpdateRequest request(String requestId, long expectedVersion,
                                             PoseDto robotPose,
                                             List<JointValueDto> joints,
                                             List<ObjectOperationDto> operations) {
        return new SceneUpdateRequest(requestId, expectedVersion, robotPose, joints, operations);
    }
}
