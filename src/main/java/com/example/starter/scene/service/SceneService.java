package com.example.starter.scene.service;

import com.example.starter.scene.dto.ApplyRequest;
import com.example.starter.scene.dto.ApplyResponse;
import com.example.starter.scene.dto.AttachOp;
import com.example.starter.scene.dto.CollisionObjectBody;
import com.example.starter.scene.dto.JointUpdate;
import com.example.starter.scene.dto.ObjectOp;
import com.example.starter.scene.dto.Pose;
import com.example.starter.scene.dto.Size3;
import com.example.starter.scene.dto.SnapshotView;
import com.example.starter.scene.exception.RequestConflictException;
import com.example.starter.scene.exception.SceneValidationException;
import com.example.starter.scene.exception.VersionConflictException;
import com.example.starter.scene.repo.AppliedRequestRow;
import com.example.starter.scene.repo.AttachedObjectRow;
import com.example.starter.scene.repo.JointRow;
import com.example.starter.scene.repo.SceneRepository;
import com.example.starter.scene.repo.WorldObjectRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Planning Scene 版本化核心服务：原子批处理、幂等提交、版本冲突检测与历史快照。
 * 写路径在进程内串行锁与数据库事务内执行，失败随事务整体回滚；
 * 历史快照读取只做单行 SELECT，不占用写锁，写入可继续提交。
 */
@Service
public class SceneService {

    /** 变更事件类别：碰撞对象（含世界与附着状态切换、重命名、删除）。 */
    public static final String EVENT_OBJECT = "OBJECT";
    /** 变更事件类别：关节位置。 */
    public static final String EVENT_JOINT = "JOINT";

    private final SceneRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ReentrantLock commitLock = new ReentrantLock();

    public SceneService(SceneRepository repository,
                        TransactionTemplate transactionTemplate,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 原子应用一批修改。任一操作非法时整批回滚，版本与历史不变。
     *
     * @param request 批量修改请求
     * @return 提交结果；同键同参幂等重放时返回首次结果
     */
    public ApplyResponse apply(ApplyRequest request) {
        validateRequestShape(request);
        String requestHash = canonicalHash(request);
        commitLock.lock();
        try {
            AppliedRequestRow existing = repository.findAppliedRequest(request.requestId()).orElse(null);
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new RequestConflictException(request.requestId());
                }
                return readJson(existing.responseJson(), ApplyResponse.class);
            }

            long baseVersion = repository.currentVersion();
            if (request.expectedVersion() != baseVersion) {
                throw staleConflict(baseVersion, request.expectedVersion());
            }

            WorkingScene working = loadWorkingScene();
            ChangeSet changes = simulate(request, working);

            long newVersion = transactionTemplate.execute(status -> {
                if (!repository.tryAdvanceVersion(baseVersion)) {
                    status.setRollbackOnly();
                    long current = repository.currentVersion();
                    throw staleConflict(current, request.expectedVersion());
                }
                persistWorkingScene(working);
                for (String objectId : changes.objects()) {
                    repository.insertEvent(baseVersion + 1L, EVENT_OBJECT, objectId);
                }
                for (String jointName : changes.joints()) {
                    repository.insertEvent(baseVersion + 1L, EVENT_JOINT, jointName);
                }
                SnapshotView snapshot = buildSnapshot(baseVersion + 1L, working);
                repository.insertSnapshot(baseVersion + 1L, writeJson(snapshot));
                ApplyResponse response = new ApplyResponse(request.requestId(), baseVersion + 1L, false);
                repository.insertAppliedRequest(new AppliedRequestRow(
                        request.requestId(), requestHash, baseVersion + 1L, writeJson(response)));
                return baseVersion + 1L;
            });

            return new ApplyResponse(request.requestId(), newVersion, false);
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 读取当前版本快照。
     */
    public SnapshotView currentSnapshot() {
        return snapshotAt(repository.currentVersion());
    }

    /**
     * 读取任意历史版本的不可变快照；版本不存在时返回 null。
     */
    public SnapshotView snapshotAt(long version) {
        return repository.findSnapshot(version)
                .map(json -> readJson(json, SnapshotView.class))
                .orElse(null);
    }

    /**
     * 启动初始化：为版本 0 写入不可变快照（幂等，已存在则跳过）。
     */
    public void initializeBaselineSnapshot() {
        commitLock.lock();
        try {
            long version = repository.currentVersion();
            if (version == 0L && repository.findSnapshot(0L).isEmpty()) {
                transactionTemplate.executeWithoutResult(status -> {
                    if (repository.currentVersion() == 0L && repository.findSnapshot(0L).isEmpty()) {
                        repository.insertSnapshot(0L, writeJson(buildSnapshot(0L, loadWorkingScene())));
                    }
                });
            }
        } finally {
            commitLock.unlock();
        }
    }

    private VersionConflictException staleConflict(long currentVersion, long expectedVersion) {
        List<String> changedObjects = expectedVersion >= 0
                ? repository.findChangedTargetsSince(expectedVersion, EVENT_OBJECT)
                : List.of();
        List<String> changedJoints = expectedVersion >= 0
                ? repository.findChangedTargetsSince(expectedVersion, EVENT_JOINT)
                : List.of();
        return new VersionConflictException(currentVersion, changedObjects, changedJoints);
    }

    private void validateRequestShape(ApplyRequest request) {
        if (request == null) {
            throw new SceneValidationException("request body is required");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new SceneValidationException("requestId is required");
        }
        if (request.expectedVersion() == null) {
            throw new SceneValidationException("expectedVersion is required");
        }
        if (request.expectedVersion() < 0L) {
            throw new SceneValidationException("expectedVersion must be non-negative");
        }
    }

    private WorkingScene loadWorkingScene() {
        Map<String, JointRow> joints = repository.findAllJoints().stream()
                .collect(Collectors.toMap(JointRow::name, row -> row, (a, b) -> a, TreeMap::new));
        Map<String, SceneObject> objects = new TreeMap<>();
        for (WorldObjectRow row : repository.findAllWorldObjects()) {
            objects.put(row.id(), new SceneObject(row.id(), SceneObject.WORLD, row.frameId(),
                    new Pose(row.x(), row.y(), row.z(), row.qx(), row.qy(), row.qz(), row.qw()),
                    new Size3(row.sx(), row.sy(), row.sz())));
        }
        for (AttachedObjectRow row : repository.findAllAttachedObjects()) {
            objects.put(row.id(), new SceneObject(row.id(), SceneObject.ATTACHED, row.linkName(),
                    new Pose(row.x(), row.y(), row.z(), row.qx(), row.qy(), row.qz(), row.qw()),
                    new Size3(row.sx(), row.sy(), row.sz())));
        }
        return new WorkingScene(joints, objects, repository.findAllFrames(), repository.findAllLinks());
    }

    private ChangeSet simulate(ApplyRequest request, WorkingScene working) {
        LinkedHashSet<String> changedObjects = new LinkedHashSet<>();
        LinkedHashSet<String> changedJoints = new LinkedHashSet<>();
        Set<String> touchedObjectIds = new LinkedHashSet<>();
        Set<String> touchedAttachIds = new LinkedHashSet<>();

        List<ObjectOp> objectOps = request.objectOps() == null ? List.of() : request.objectOps();
        for (ObjectOp op : objectOps) {
            String targetKey = simulateObjectOp(op, working, changedObjects);
            if (!touchedObjectIds.add(targetKey)) {
                throw new SceneValidationException("object id appears in multiple object operations: " + targetKey);
            }
        }

        List<AttachOp> attachOps = request.attachOps() == null ? List.of() : request.attachOps();
        for (AttachOp op : attachOps) {
            String targetKey = simulateAttachOp(op, working, changedObjects);
            if (!touchedAttachIds.add(targetKey)) {
                throw new SceneValidationException("object id appears in multiple attach operations: " + targetKey);
            }
        }

        List<JointUpdate> jointUpdates = request.jointUpdates() == null ? List.of() : request.jointUpdates();
        Set<String> touchedJoints = new LinkedHashSet<>();
        for (JointUpdate update : jointUpdates) {
            if (update == null || update.name() == null || update.name().isBlank()) {
                throw new SceneValidationException("joint update requires a joint name");
            }
            if (!touchedJoints.add(update.name())) {
                throw new SceneValidationException("joint appears multiple times in one batch: " + update.name());
            }
            JointRow joint = working.joints().get(update.name());
            if (joint == null) {
                throw new SceneValidationException("joint does not exist: " + update.name());
            }
            double position = update.position();
            if (!Double.isFinite(position)) {
                throw new SceneValidationException("joint position must be finite: " + update.name());
            }
            if (position < joint.minLimit() || position > joint.maxLimit()) {
                throw new SceneValidationException("joint position out of range [" + joint.minLimit()
                        + ", " + joint.maxLimit() + "]: " + update.name() + "=" + position);
            }
            if (position != joint.position()) {
                working.joints().put(update.name(), new JointRow(
                        joint.name(), position, joint.minLimit(), joint.maxLimit()));
                changedJoints.add(update.name());
            }
        }
        return new ChangeSet(List.copyOf(changedObjects), List.copyOf(changedJoints));
    }

    private String simulateObjectOp(ObjectOp op, WorkingScene working,
                                    LinkedHashSet<String> changedObjects) {
        if (op == null || op.type() == null || op.type().isBlank()) {
            throw new SceneValidationException("object operation requires a type");
        }
        switch (op.type()) {
            case ObjectOp.ADD -> {
                CollisionObjectBody body = requireBody(op);
                String id = requireId(body.id());
                if (working.objects().containsKey(id)) {
                    throw new SceneValidationException("object id already exists: " + id);
                }
                Pose pose = resolvePose(body);
                Size3 size = resolveSize(body);
                String frameId = resolveFrame(body.frameId(), working, true);
                working.objects().put(id, new SceneObject(id, SceneObject.WORLD, frameId, pose, size));
                changedObjects.add(id);
                return id;
            }
            case ObjectOp.MODIFY -> {
                String id = requireId(op.objectId());
                SceneObject current = working.objects().get(id);
                if (current == null) {
                    throw new SceneValidationException("object does not exist: " + id);
                }
                CollisionObjectBody body = requireBody(op);
                if (body.id() != null && !body.id().isBlank() && !body.id().equals(id)) {
                    throw new SceneValidationException("MODIFY body id does not match target id: " + id);
                }
                Pose pose = resolvePose(body);
                Size3 size = resolveSize(body);
                String reference;
                if (current.state().equals(SceneObject.ATTACHED)) {
                    if (body.frameId() != null && !body.frameId().isBlank()) {
                        throw new SceneValidationException(
                                "attached object does not reference a world frame, DETACH first: " + id);
                    }
                    reference = current.reference();
                } else {
                    reference = resolveFrame(body.frameId(), working, false);
                    if (reference == null) {
                        reference = current.reference();
                    }
                }
                SceneObject updated = new SceneObject(id, current.state(), reference, pose, size);
                if (!updated.contentEquals(current)) {
                    working.objects().put(id, updated);
                    changedObjects.add(id);
                }
                return id;
            }
            case ObjectOp.DELETE -> {
                String id = requireId(op.objectId());
                SceneObject current = working.objects().get(id);
                if (current == null) {
                    throw new SceneValidationException("object does not exist: " + id);
                }
                // 附着中的对象删除时引用随对象一并清除，保证不残留悬空附着引用。
                working.objects().remove(id);
                changedObjects.add(id);
                return id;
            }
            case ObjectOp.RENAME -> {
                String id = requireId(op.objectId());
                String newId = requireId(op.newObjectId());
                SceneObject current = working.objects().get(id);
                if (current == null) {
                    throw new SceneValidationException("object does not exist: " + id);
                }
                if (newId.equals(id)) {
                    throw new SceneValidationException("RENAME new id equals old id: " + id);
                }
                if (working.objects().containsKey(newId)) {
                    throw new SceneValidationException("rename target id already exists: " + newId);
                }
                working.objects().remove(id);
                working.objects().put(newId, new SceneObject(newId, current.state(),
                        current.reference(), current.pose(), current.size()));
                changedObjects.add(id);
                changedObjects.add(newId);
                return id;
            }
            default -> throw new SceneValidationException("unknown object operation type: " + op.type());
        }
    }

    private String simulateAttachOp(AttachOp op, WorkingScene working,
                                    LinkedHashSet<String> changedObjects) {
        if (op == null || op.type() == null || op.type().isBlank()) {
            throw new SceneValidationException("attach operation requires a type");
        }
        String id = requireId(op.objectId());
        SceneObject current = working.objects().get(id);
        if (current == null) {
            throw new SceneValidationException("object does not exist: " + id);
        }
        switch (op.type()) {
            case AttachOp.ATTACH -> {
                if (!current.state().equals(SceneObject.WORLD)) {
                    throw new SceneValidationException("object is already attached: " + id);
                }
                String linkName = requireId(op.linkName());
                if (!working.links().contains(linkName)) {
                    throw new SceneValidationException("robot link does not exist: " + linkName);
                }
                working.objects().put(id, new SceneObject(id, SceneObject.ATTACHED, linkName,
                        current.pose(), current.size()));
                changedObjects.add(id);
                return id;
            }
            case AttachOp.DETACH -> {
                if (!current.state().equals(SceneObject.ATTACHED)) {
                    throw new SceneValidationException("object is not attached: " + id);
                }
                String frameId = op.frameId() == null || op.frameId().isBlank()
                        ? "world" : op.frameId();
                if (!working.frames().contains(frameId)) {
                    throw new SceneValidationException("frame does not exist: " + frameId);
                }
                working.objects().put(id, new SceneObject(id, SceneObject.WORLD, frameId,
                        current.pose(), current.size()));
                changedObjects.add(id);
                return id;
            }
            default -> throw new SceneValidationException("unknown attach operation type: " + op.type());
        }
    }

    private CollisionObjectBody requireBody(ObjectOp op) {
        CollisionObjectBody body = op.object();
        if (body == null) {
            throw new SceneValidationException(op.type() + " requires an object body");
        }
        return body;
    }

    private String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new SceneValidationException("object id is required");
        }
        return id;
    }

    private Pose resolvePose(CollisionObjectBody body) {
        Pose pose = body.pose() == null ? Pose.identity() : body.pose();
        if (!pose.isFinite()) {
            throw new SceneValidationException("pose components must be finite for object: " + body.id());
        }
        return pose;
    }

    private Size3 resolveSize(CollisionObjectBody body) {
        Size3 size = body.size();
        if (size == null) {
            throw new SceneValidationException("object size is required: " + body.id());
        }
        if (!size.isPositiveFinite()) {
            throw new SceneValidationException(
                    "object size components must be positive finite numbers: " + body.id());
        }
        return size;
    }

    private String resolveFrame(String frameId, WorkingScene working, boolean adding) {
        if (frameId == null || frameId.isBlank()) {
            return adding ? "world" : null;
        }
        if (!working.frames().contains(frameId)) {
            throw new SceneValidationException("frame does not exist: " + frameId);
        }
        return frameId;
    }

    private void persistWorkingScene(WorkingScene working) {
        repository.deleteAllWorldObjects();
        repository.deleteAllAttachedObjects();
        for (SceneObject object : working.objects().values()) {
            if (object.state().equals(SceneObject.WORLD)) {
                repository.insertWorldObject(toWorldRow(object));
            } else {
                repository.insertAttachedObject(toAttachedRow(object));
            }
        }
        for (JointRow joint : working.joints().values()) {
            repository.updateJointPosition(joint.name(), joint.position());
        }
    }

    private WorldObjectRow toWorldRow(SceneObject object) {
        Pose pose = object.pose();
        Size3 size = object.size();
        return new WorldObjectRow(object.id(), object.reference(),
                pose.x(), pose.y(), pose.z(), pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                size.x(), size.y(), size.z());
    }

    private AttachedObjectRow toAttachedRow(SceneObject object) {
        Pose pose = object.pose();
        Size3 size = object.size();
        return new AttachedObjectRow(object.id(), object.reference(),
                pose.x(), pose.y(), pose.z(), pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                size.x(), size.y(), size.z());
    }

    private SnapshotView buildSnapshot(long version, WorkingScene working) {
        List<SnapshotView.JointState> joints = new ArrayList<>();
        for (JointRow joint : working.joints().values()) {
            joints.add(new SnapshotView.JointState(
                    joint.name(), joint.position(), joint.minLimit(), joint.maxLimit()));
        }
        List<SnapshotView.WorldObjectView> worldObjects = new ArrayList<>();
        List<SnapshotView.AttachedObjectView> attachedObjects = new ArrayList<>();
        for (SceneObject object : working.objects().values()) {
            if (object.state().equals(SceneObject.WORLD)) {
                worldObjects.add(new SnapshotView.WorldObjectView(
                        object.id(), object.reference(), object.pose(), object.size()));
            } else {
                attachedObjects.add(new SnapshotView.AttachedObjectView(
                        object.id(), object.reference(), object.pose(), object.size()));
            }
        }
        return new SnapshotView(version, List.copyOf(joints),
                List.copyOf(worldObjects), List.copyOf(attachedObjects));
    }

    private String canonicalHash(ApplyRequest request) {
        try {
            ObjectMapper sortedMapper = objectMapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            String canonical = sortedMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to build idempotency hash", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored payload", e);
        }
    }

    /**
     * 批处理期间的场景工作副本，引用状态合并为统一对象模型，保证同一对象不可能同时位于两处。
     */
    private record WorkingScene(Map<String, JointRow> joints,
                                Map<String, SceneObject> objects,
                                Set<String> frames,
                                Set<String> links) {
    }

    /**
     * 合并世界与附着集合后的统一对象视图。
     *
     * @param id 对象 ID
     * @param state WORLD 或 ATTACHED
     * @param reference WORLD 时为坐标系名，ATTACHED 时为 Link 名
     * @param pose 位姿（世界中相对坐标系，附着后相对 Link）
     * @param size 盒体尺寸
     */
    private record SceneObject(String id, String state, String reference, Pose pose, Size3 size) {

        private static final String WORLD = "WORLD";
        private static final String ATTACHED = "ATTACHED";

        private boolean contentEquals(SceneObject other) {
            return Objects.equals(id, other.id)
                    && Objects.equals(state, other.state)
                    && Objects.equals(reference, other.reference)
                    && poseEquals(pose, other.pose)
                    && sizeEquals(size, other.size);
        }

        private boolean poseEquals(Pose a, Pose b) {
            return Double.compare(a.x(), b.x()) == 0 && Double.compare(a.y(), b.y()) == 0
                    && Double.compare(a.z(), b.z()) == 0
                    && Double.compare(a.qx(), b.qx()) == 0 && Double.compare(a.qy(), b.qy()) == 0
                    && Double.compare(a.qz(), b.qz()) == 0 && Double.compare(a.qw(), b.qw()) == 0;
        }

        private boolean sizeEquals(Size3 a, Size3 b) {
            return Double.compare(a.x(), b.x()) == 0
                    && Double.compare(a.y(), b.y()) == 0
                    && Double.compare(a.z(), b.z()) == 0;
        }
    }

    /**
     * 模拟执行后收集的实际变化目标（去重、保序）。
     */
    private record ChangeSet(List<String> objects, List<String> joints) {
    }
}
