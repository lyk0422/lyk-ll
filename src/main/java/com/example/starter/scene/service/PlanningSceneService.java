package com.example.starter.scene.service;

import com.example.starter.scene.db.PlanningSceneRepository;
import com.example.starter.scene.domain.SceneState;
import com.example.starter.scene.exception.SceneConflictException;
import com.example.starter.scene.exception.SceneNotFoundException;
import com.example.starter.scene.exception.SceneValidationException;
import com.example.starter.scene.model.SceneApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Planning Scene 应用服务。
 *
 * <p>一次修改请求在单数据库事务内完成：先对 scene_meta 加悲观行锁并复核 requestId/版本，
 * 随后在内存工作副本上顺序执行并校验整批操作，任一操作非法则抛出异常整批回滚，
 * 版本、当前态、历史快照与幂等记录均不变化；全部通过后覆盖当前态并追加新版本快照。</p>
 */
@Service
public class PlanningSceneService {

    /** 世界坐标系常量；对象位姿可引用 world 或任意已注册机器人 Link。 */
    public static final String WORLD_FRAME = "world";

    private final PlanningSceneRepository repository;

    public PlanningSceneService(PlanningSceneRepository repository) {
        this.repository = repository;
    }

    /**
     * 初始化机器人模型（版本 0 基线）。
     *
     * @return 基线版本号 0
     */
    @Transactional
    public long initializeRobotModel(SceneApi.RobotModelRequest request) {
        validateRobotModel(request);
        return repository.initializeRobotModel(request);
    }

    /** 读取当前版本快照。 */
    @Transactional(readOnly = true)
    public SceneApi.SnapshotResponse currentSnapshot() {
        ensureInitialized();
        return toSnapshot(repository.loadCurrent());
    }

    /**
     * 原子提交一批场景修改。
     *
     * <p>流程：加行锁并复核幂等键/版本 →（锁内）加载当前态与模型定义 →
     * 顺序校验并执行全部操作 → 落盘新版本。同键同参直接重放原结果，
     * 不重新执行业务校验。</p>
     */
    @Transactional
    public SceneApi.CommitResult apply(SceneApi.ChangeRequest request) {
        ensureInitialized();
        String payloadHash = canonicalHash(request);

        PlanningSceneRepository.WriteLock lock = repository.acquireWriteLock(
                request.expectedVersion(), request.requestId(), payloadHash);
        if (lock.status() == PlanningSceneRepository.WriteLock.Status.REPLAY) {
            return new SceneApi.CommitResult(request.requestId(), lock.version(), false, true);
        }

        SceneState current = repository.loadCurrent();
        Set<String> links = new TreeSet<>(repository.loadLinkNames());
        Map<String, SceneApi.JointSpec> jointDefs = repository.loadJointDefs();

        Map<String, Double> joints = new LinkedHashMap<>(current.joints());
        Map<String, SceneApi.ObjectSnapshot> objects = new LinkedHashMap<>(current.objects());

        if (request.robotPose() != null) {
            validatePose(request.robotPose(), links, "robotPose");
        }
        SceneApi.PoseData robotPose = request.robotPose() != null ? request.robotPose() : current.robotPose();

        for (int i = 0; i < request.operations().size(); i++) {
            applyOperation(i, request.operations().get(i), objects, joints, jointDefs, links);
        }

        long newVersion = current.version() + 1;
        SceneState next = new SceneState(newVersion, robotPose, joints, objects);
        repository.persistNewVersion(request.requestId(), payloadHash, next);
        return new SceneApi.CommitResult(request.requestId(), newVersion, true, false);
    }

    /** 读取任意历史版本的不可变快照；版本不存在返回 404。 */
    @Transactional(readOnly = true)
    public SceneApi.SnapshotResponse snapshot(long version) {
        SceneApi.SnapshotResponse snapshot = repository.loadSnapshot(version);
        if (snapshot == null) {
            throw new SceneNotFoundException("snapshot version " + version + " does not exist");
        }
        return snapshot;
    }

    // ------------------------------------------------------------------
    // 操作执行
    // ------------------------------------------------------------------

    private void applyOperation(int index, SceneApi.OperationRequest op,
                                Map<String, SceneApi.ObjectSnapshot> objects,
                                Map<String, Double> joints,
                                Map<String, SceneApi.JointSpec> jointDefs,
                                Set<String> links) {
        if (op.type() == null) {
            throw SceneValidationException.at(index, "operation type is required");
        }
        switch (op.type()) {
            case OBJECT_UPSERT -> objectUpsert(index, op, objects, links);
            case OBJECT_DELETE -> objectDelete(index, op, objects);
            case OBJECT_RENAME -> objectRename(index, op, objects);
            case JOINT_UPDATE -> jointUpdate(index, op, joints, jointDefs);
            case ATTACH -> attach(index, op, objects, links);
            case DETACH -> detach(index, op, objects, links);
        }
    }

    private void objectUpsert(int index, SceneApi.OperationRequest op,
                              Map<String, SceneApi.ObjectSnapshot> objects, Set<String> links) {
        String id = requireId(index, op.objectId(), "objectId");
        requireText(index, op.frameId(), "frameId");
        requireText(index, op.shape(), "shape");
        if (!links.contains(op.frameId()) && !WORLD_FRAME.equals(op.frameId())) {
            throw SceneValidationException.at(index,
                    "frame '" + op.frameId() + "' does not exist (expected 'world' or a robot link)");
        }
        requireFinite(index, "size", op.sizeX(), op.sizeY(), op.sizeZ());
        if (positive(op.sizeX()) <= 0 || positive(op.sizeY()) <= 0 || positive(op.sizeZ()) <= 0) {
            throw SceneValidationException.at(index, "object dimensions must be positive");
        }
        requireFinite(index, "position", op.x(), op.y(), op.z());
        requireFinite(index, "orientation", op.qx(), op.qy(), op.qz(), op.qw());
        SceneApi.ObjectSnapshot existing = objects.get(id);
        if (existing != null && existing.attachedLink() != null) {
            throw SceneValidationException.at(index,
                    "object '" + id + "' is attached to link '" + existing.attachedLink()
                            + "'; DETACH it before upserting into the world");
        }
        objects.put(id, new SceneApi.ObjectSnapshot(id, op.frameId(), op.shape(),
                op.sizeX(), op.sizeY(), op.sizeZ(),
                op.x(), op.y(), op.z(), op.qx(), op.qy(), op.qz(), op.qw(), null));
    }

    private void objectDelete(int index, SceneApi.OperationRequest op,
                              Map<String, SceneApi.ObjectSnapshot> objects) {
        String id = requireId(index, op.objectId(), "objectId");
        if (!objects.containsKey(id)) {
            throw SceneValidationException.at(index, "object '" + id + "' does not exist");
        }
        // 删除对象同步清除其附着引用（级联 detach），不会留下悬空引用。
        objects.remove(id);
    }

    private void objectRename(int index, SceneApi.OperationRequest op,
                              Map<String, SceneApi.ObjectSnapshot> objects) {
        String id = requireId(index, op.objectId(), "objectId");
        String newId = requireId(index, op.newObjectId(), "newObjectId");
        SceneApi.ObjectSnapshot existing = objects.get(id);
        if (existing == null) {
            throw SceneValidationException.at(index, "object '" + id + "' does not exist");
        }
        if (!id.equals(newId) && objects.containsKey(newId)) {
            throw SceneValidationException.at(index,
                    "object id '" + newId + "' already exists; object ids must be unique");
        }
        if (id.equals(newId)) {
            return;
        }
        // 重命名同步迁移附着关系：attachedLink 保持不变，仅更换主键 ID。
        objects.remove(id);
        objects.put(newId, new SceneApi.ObjectSnapshot(newId, existing.frameId(), existing.shape(),
                existing.sizeX(), existing.sizeY(), existing.sizeZ(),
                existing.x(), existing.y(), existing.z(),
                existing.qx(), existing.qy(), existing.qz(), existing.qw(),
                existing.attachedLink()));
    }

    private void jointUpdate(int index, SceneApi.OperationRequest op,
                             Map<String, Double> joints,
                             Map<String, SceneApi.JointSpec> jointDefs) {
        String name = requireId(index, op.jointName(), "jointName");
        if (op.jointValue() == null) {
            throw SceneValidationException.at(index, "jointValue is required");
        }
        requireFinite(index, "jointValue", op.jointValue());
        SceneApi.JointSpec spec = jointDefs.get(name);
        if (spec == null) {
            throw SceneValidationException.at(index, "joint '" + name + "' does not exist");
        }
        double value = op.jointValue();
        if (value < spec.min() || value > spec.max()) {
            throw SceneValidationException.at(index,
                    "joint '" + name + "' value " + value + " rad is out of range ["
                            + spec.min() + ", " + spec.max() + "]");
        }
        joints.put(name, value);
    }

    private void attach(int index, SceneApi.OperationRequest op,
                        Map<String, SceneApi.ObjectSnapshot> objects, Set<String> links) {
        String id = requireId(index, op.objectId(), "objectId");
        String link = requireId(index, op.linkName(), "linkName");
        SceneApi.ObjectSnapshot existing = objects.get(id);
        if (existing == null) {
            throw SceneValidationException.at(index, "object '" + id + "' does not exist");
        }
        if (existing.attachedLink() != null) {
            throw SceneValidationException.at(index,
                    "object '" + id + "' is already attached to link '" + existing.attachedLink()
                            + "'; an object cannot be in the world and attached sets simultaneously");
        }
        if (!links.contains(link)) {
            throw SceneValidationException.at(index, "robot link '" + link + "' does not exist");
        }
        Double nx = op.x() != null ? op.x() : existing.x();
        Double ny = op.y() != null ? op.y() : existing.y();
        Double nz = op.z() != null ? op.z() : existing.z();
        Double nqx = op.qx() != null ? op.qx() : existing.qx();
        Double nqy = op.qy() != null ? op.qy() : existing.qy();
        Double nqz = op.qz() != null ? op.qz() : existing.qz();
        Double nqw = op.qw() != null ? op.qw() : existing.qw();
        requireFinite(index, "attach pose", nx, ny, nz, nqx, nqy, nqz, nqw);
        // 附着后参考坐标系切换为被附着 Link，attachedLink 非空即表示对象离开世界集合。
        objects.put(id, new SceneApi.ObjectSnapshot(id, link, existing.shape(),
                existing.sizeX(), existing.sizeY(), existing.sizeZ(),
                nx, ny, nz, nqx, nqy, nqz, nqw, link));
    }

    private void detach(int index, SceneApi.OperationRequest op,
                        Map<String, SceneApi.ObjectSnapshot> objects, Set<String> links) {
        String id = requireId(index, op.objectId(), "objectId");
        SceneApi.ObjectSnapshot existing = objects.get(id);
        if (existing == null) {
            throw SceneValidationException.at(index, "object '" + id + "' does not exist");
        }
        if (existing.attachedLink() == null) {
            throw SceneValidationException.at(index,
                    "object '" + id + "' is not attached; cannot DETACH a world object");
        }
        String frame = op.frameId() != null ? op.frameId() : WORLD_FRAME;
        if (!links.contains(frame) && !WORLD_FRAME.equals(frame)) {
            throw SceneValidationException.at(index,
                    "frame '" + frame + "' does not exist (expected 'world' or a robot link)");
        }
        Double nx = op.x() != null ? op.x() : existing.x();
        Double ny = op.y() != null ? op.y() : existing.y();
        Double nz = op.z() != null ? op.z() : existing.z();
        Double nqx = op.qx() != null ? op.qx() : existing.qx();
        Double nqy = op.qy() != null ? op.qy() : existing.qy();
        Double nqz = op.qz() != null ? op.qz() : existing.qz();
        Double nqw = op.qw() != null ? op.qw() : existing.qw();
        requireFinite(index, "detach pose", nx, ny, nz, nqx, nqy, nqz, nqw);
        objects.put(id, new SceneApi.ObjectSnapshot(id, frame, existing.shape(),
                existing.sizeX(), existing.sizeY(), existing.sizeZ(),
                nx, ny, nz, nqx, nqy, nqz, nqw, null));
    }

    // ------------------------------------------------------------------
    // 校验辅助
    // ------------------------------------------------------------------

    private void validateRobotModel(SceneApi.RobotModelRequest request) {
        Set<String> linkSet = new TreeSet<>();
        for (String link : request.links()) {
            if (!linkSet.add(link)) {
                throw new SceneValidationException("duplicate link name: " + link);
            }
        }
        Set<String> jointSet = new TreeSet<>();
        for (SceneApi.JointSpec joint : request.joints()) {
            if (!jointSet.add(joint.name())) {
                throw new SceneValidationException("duplicate joint name: " + joint.name());
            }
            if (!Double.isFinite(joint.min()) || !Double.isFinite(joint.max())
                    || !Double.isFinite(joint.initialValue())) {
                throw new SceneValidationException(
                        "joint '" + joint.name() + "' bounds and initial value must be finite");
            }
            if (joint.min() > joint.max()) {
                throw new SceneValidationException(
                        "joint '" + joint.name() + "' min must not exceed max");
            }
            if (joint.initialValue() < joint.min() || joint.initialValue() > joint.max()) {
                throw new SceneValidationException(
                        "joint '" + joint.name() + "' initial value is out of range");
            }
        }
        if (request.initialPose() != null) {
            validatePose(request.initialPose(), linkSet, "initialPose");
        }
    }

    private void validatePose(SceneApi.PoseData pose, Set<String> links, String field) {
        if (!links.contains(pose.frameId()) && !WORLD_FRAME.equals(pose.frameId())) {
            throw new SceneValidationException(
                    field + " frame '" + pose.frameId() + "' does not exist");
        }
        if (!Double.isFinite(pose.x()) || !Double.isFinite(pose.y()) || !Double.isFinite(pose.z())
                || !Double.isFinite(pose.qx()) || !Double.isFinite(pose.qy())
                || !Double.isFinite(pose.qz()) || !Double.isFinite(pose.qw())) {
            throw new SceneValidationException(field + " contains non-finite numeric value");
        }
    }

    private String requireId(int index, String value, String field) {
        if (value == null || value.isBlank()) {
            throw SceneValidationException.at(index, field + " is required");
        }
        return value;
    }

    private void requireText(int index, String value, String field) {
        if (value == null || value.isBlank()) {
            throw SceneValidationException.at(index, field + " is required");
        }
    }

    private void requireFinite(int index, String field, Double... values) {
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw SceneValidationException.at(index, field + " must be a finite number");
            }
        }
    }

    private double positive(Double value) {
        return value == null ? Double.NaN : value;
    }

    private void ensureInitialized() {
        if (!repository.isInitialized()) {
            throw new SceneConflictException("ROBOT_MODEL_MISSING",
                    "robot model has not been initialized; POST /api/scene/robot-model first",
                    0, -1, List.of(), List.of(), false);
        }
    }

    private SceneApi.SnapshotResponse toSnapshot(SceneState state) {
        List<SceneApi.JointSnapshot> joints = new ArrayList<>();
        state.joints().forEach((name, value) -> joints.add(new SceneApi.JointSnapshot(name, value)));
        return new SceneApi.SnapshotResponse(state.version(), state.robotPose(),
                joints, new ArrayList<>(state.objects().values()));
    }

    // ------------------------------------------------------------------
    // 幂等参数规范化哈希
    // ------------------------------------------------------------------

    /**
     * 对请求参数生成与字段顺序无关、数值表示稳定的 SHA-256 十六进制摘要，
     * 作为“同键同参/同键异参”的判定依据。
     */
    static String canonicalHash(SceneApi.ChangeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("v=").append(request.expectedVersion());
        sb.append("|pose=").append(poseCanonical(request.robotPose()));
        sb.append("|ops=").append(request.operations().size());
        for (SceneApi.OperationRequest op : request.operations()) {
            sb.append('|').append(op.type())
                    .append(',').append(safe(op.objectId()))
                    .append(',').append(safe(op.newObjectId()))
                    .append(',').append(safe(op.frameId()))
                    .append(',').append(safe(op.shape()))
                    .append(',').append(num(op.sizeX())).append(',').append(num(op.sizeY()))
                    .append(',').append(num(op.sizeZ()))
                    .append(',').append(num(op.x())).append(',').append(num(op.y()))
                    .append(',').append(num(op.z()))
                    .append(',').append(num(op.qx())).append(',').append(num(op.qy()))
                    .append(',').append(num(op.qz())).append(',').append(num(op.qw()))
                    .append(',').append(safe(op.jointName())).append(',').append(num(op.jointValue()))
                    .append(',').append(safe(op.linkName()));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String poseCanonical(SceneApi.PoseData pose) {
        if (pose == null) {
            return "";
        }
        return safe(pose.frameId()) + ',' + num(pose.x()) + ',' + num(pose.y()) + ',' + num(pose.z())
                + ',' + num(pose.qx()) + ',' + num(pose.qy()) + ',' + num(pose.qz())
                + ',' + num(pose.qw());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String num(Double value) {
        // 非有限值会在业务校验阶段被拒绝；此处仅需保证哈希计算本身不抛异常且可区分
        if (value == null) {
            return "";
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        return java.math.BigDecimal.valueOf(value).toPlainString();
    }
}
