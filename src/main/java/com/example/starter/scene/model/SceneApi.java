package com.example.starter.scene.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Planning Scene 服务对外 HTTP 接口使用的请求/响应模型集合。
 */
public final class SceneApi {

    private SceneApi() {
    }

    /** 批量操作类型。 */
    public enum OperationType {
        /** 新增或修改世界中的碰撞对象。 */
        OBJECT_UPSERT,
        /** 删除碰撞对象（附着中的对象会先自动 detach）。 */
        OBJECT_DELETE,
        /** 重命名碰撞对象（附着关系同步迁移到新 ID）。 */
        OBJECT_RENAME,
        /** 更新单个关节角度（单位：弧度，必须位于关节上下限内）。 */
        JOINT_UPDATE,
        /** 将世界中的对象附着到指定机器人 Link。 */
        ATTACH,
        /** 将附着中的对象释放回世界。 */
        DETACH
    }

    /** 位姿：平移单位米，姿态为四元数 (x,y,z,w)；frameId 为存在的坐标系（机器人 Link 或 world）。 */
    public record PoseData(
            @NotBlank String frameId,
            double x,
            double y,
            double z,
            double qx,
            double qy,
            double qz,
            double qw) {
    }

    /** 机器人模型中的关节定义：name 唯一，角度（弧度）取值范围 [min,max]。 */
    public record JointSpec(
            @NotBlank String name,
            double min,
            double max,
            double initialValue) {
    }

    /** 机器人模型初始化请求：Link 名集合、关节定义与初始机器人姿态。 */
    public record RobotModelRequest(
            @NotEmpty List<@NotBlank String> links,
            @NotEmpty @Valid List<JointSpec> joints,
            @Valid PoseData initialPose) {
    }

    /** 单个批量操作；不同 type 使用其中不同字段。 */
    public record OperationRequest(
            @NotNull OperationType type,
            String objectId,
            String newObjectId,
            String frameId,
            String shape,
            Double sizeX,
            Double sizeY,
            Double sizeZ,
            Double x,
            Double y,
            Double z,
            Double qx,
            Double qy,
            Double qz,
            Double qw,
            String jointName,
            Double jointValue,
            String linkName) {
    }

    /** 一次场景修改请求；requestId 为幂等键，expectedVersion 为乐观版本号。 */
    public record ChangeRequest(
            @NotBlank String requestId,
            @PositiveOrZero long expectedVersion,
            @NotEmpty @Valid List<OperationRequest> operations,
            @Valid PoseData robotPose) {
    }

    /** 碰撞对象快照；attachedLink 为 null 表示位于世界，非 null 表示附着在该机器人 Link 上。 */
    public record ObjectSnapshot(
            String objectId,
            String frameId,
            String shape,
            double sizeX,
            double sizeY,
            double sizeZ,
            double x,
            double y,
            double z,
            double qx,
            double qy,
            double qz,
            double qw,
            String attachedLink) {
    }

    /** 关节状态快照，value 单位弧度。 */
    public record JointSnapshot(String name, double value) {
    }

    /** 某一版本的不可变 Planning Scene 快照。 */
    public record SnapshotResponse(
            long version,
            PoseData robotPose,
            List<JointSnapshot> joints,
            List<ObjectSnapshot> objects) {
    }

    /** 修改提交结果；replayed=true 表示命中幂等重放，未产生新版本。 */
    public record CommitResult(
            String requestId,
            long version,
            boolean committed,
            boolean replayed) {
    }
}
