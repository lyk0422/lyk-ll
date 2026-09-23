package com.example.starter.scene.db;

import com.example.starter.scene.domain.SceneState;
import com.example.starter.scene.model.SceneApi;

import java.util.List;
import java.util.Map;

/**
 * Planning Scene 数据访问层；所有方法均运行在 Service 层划定的数据库事务内。
 */
public interface PlanningSceneRepository {

    /** 幂等请求记录。 */
    record IdempotentRecord(String requestId, long version, String payloadHash) {
    }

    /**
     * 加锁结果。
     *
     * @param status   OK：可继续校验并提交；REPLAY：同键同参重放
     * @param version  OK 时为当前版本；REPLAY 时为历史记录版本
     */
    record WriteLock(Status status, long version) {
        public enum Status { OK, REPLAY }
    }

    /**
     * 获取 scene_meta 单行悲观写锁，并在锁内完成幂等与版本复核：
     * 同键同参返回 REPLAY；同键异参或版本过期抛出 {@link SceneConflictException}。
     */
    WriteLock acquireWriteLock(long expectedVersion, String requestId, String payloadHash);

    /**
     * 锁内落盘新状态：覆盖当前表、追加新版本快照与幂等记录。
     *
     * @return 新分配的版本号
     */
    long persistNewVersion(String requestId, String payloadHash, SceneState newState);

    /** 机器人模型是否已初始化。 */
    boolean isInitialized();

    /**
     * 初始化机器人模型并写入版本 0 的基线快照；模型已存在时抛出冲突。
     */
    long initializeRobotModel(SceneApi.RobotModelRequest request);

    /** 当前场景版本号；未初始化时为 -1。 */
    long currentVersion();

    /** 加载当前工作状态（机器人姿态、关节值、全部碰撞对象）。 */
    SceneState loadCurrent();

    /** 已存在的机器人 Link 名称集合。 */
    List<String> loadLinkNames();

    /** 已定义关节的名称 -> 关节规格（含角度上下限，单位弧度）。 */
    Map<String, SceneApi.JointSpec> loadJointDefs();

    /** 按 requestId 查询幂等记录；不存在返回 null。 */
    IdempotentRecord findIdempotent(String requestId);

    /** 某历史版本是否存在。 */
    boolean snapshotExists(long version);

    /** 读取某历史版本的不可变快照；不存在返回 null。 */
    SceneApi.SnapshotResponse loadSnapshot(long version);

    /** 读取指定版本下的对象快照（用于计算版本间变化列表）。 */
    List<SceneApi.ObjectSnapshot> loadObjectSnapshot(long version);

    /** 读取指定版本下的关节快照。 */
    List<SceneApi.JointSnapshot> loadJointSnapshot(long version);

    /** 读取指定版本下的机器人姿态；未设置返回 null。 */
    SceneApi.PoseData loadRobotPose(long version);
}
