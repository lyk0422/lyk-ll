package com.example.starter.scene.repo;

import com.example.starter.scene.model.RobotPose;
import com.example.starter.scene.model.SceneSnapshot;
import com.example.starter.scene.service.CurrentScene;

import java.util.Map;
import java.util.Set;

/**
 * Planning Scene 数据访问边界；所有方法仅操作 H2（MODE=MySQL）。
 * 写方法须运行在服务层开启的事务内，{@link #lockAndGetCurrentVersion()}
 * 获取的 scene_meta 行锁在事务提交前持续有效，用于串行化版本提交。
 */
public interface PlanningSceneRepository {

    /**
     * 读取全部机器人 Link 名称（坐标系/附着物引用合法性来源）。
     */
    Set<String> findAllLinkNames();

    /**
     * 读取全部关节模型及上下限（弧度）。
     */
    Map<String, JointLimit> findAllJointLimits();

    /**
     * 对场景单行加写锁（SELECT ... FOR UPDATE）并返回当前版本号。
     */
    long lockAndGetCurrentVersion();

    /**
     * 读取幂等台账；不存在返回 null。
     */
    IdempotentRecord findRequest(String requestId);

    /**
     * 加载当前场景全量视图。
     */
    CurrentScene loadCurrent();

    /**
     * 在当前事务内把计算完成的新版本终态写入当前表与历史表，并登记幂等记录。
     */
    void applyCommit(CommitData data);

    /**
     * 组装指定版本的不可变快照；版本不存在返回 null。
     * 纯只读查询，不获取写锁，可与写入并发执行。
     */
    SceneSnapshot loadSnapshot(long version);

    /**
     * 读取指定版本的完整内部状态（含附着中对象的负载与附着 Link），用于版本差异比较；
     * 版本不存在返回 null。纯只读，不获取写锁。
     */
    VersionState loadVersionState(long version);

    /**
     * 关节位置上下限（弧度）。
     */
    record JointLimit(double lower, double upper) {
    }

    /**
     * 幂等请求台账记录。
     *
     * @param requestHash 请求业务参数哈希
     * @param version     首次提交产生的版本号
     */
    record IdempotentRecord(String requestHash, long version) {
    }

    /**
     * 指定版本的完整内部状态，用于版本间差异比较。
     *
     * @param joints      关节名 -> 位置（弧度）
     * @param objects     对象 ID -> （frameId, payload）；frameId 为 null 表示附着中
     * @param attachments 对象 ID -> 附着 Link 名
     */
    record VersionState(
            Map<String, Double> joints,
            Map<String, ObjectState> objects,
            Map<String, String> attachments) {

        /**
         * 历史版本中的对象状态。
         */
        public record ObjectState(String frameId, String payload) {
        }
    }

    /**
     * 一次提交的完整终态数据。
     * @param newVersion  新版本号
     * @param requestId   请求 ID
     * @param requestHash 请求业务参数 SHA-256
     * @param createdAt   提交时间（Unix 毫秒，UTC）
     * @param pose        终态机器人基座位姿
     * @param joints      终态全部关节位置（弧度）
     * @param objects     终态全部碰撞对象（frameId 为 null 表示附着中）
     * @param attachments 终态附着物（对象 ID -> Link 名）
     */
    record CommitData(
            long newVersion,
            String requestId,
            String requestHash,
            long createdAt,
            RobotPose pose,
            Map<String, Double> joints,
            Map<String, CurrentScene.SceneObject> objects,
            Map<String, String> attachments) {
    }
}
