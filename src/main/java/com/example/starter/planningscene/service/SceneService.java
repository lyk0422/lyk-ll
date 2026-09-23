package com.example.starter.planningscene.service;

import com.example.starter.planningscene.domain.JointState;
import com.example.starter.planningscene.domain.ObjectState;
import com.example.starter.planningscene.domain.ObjectType;
import com.example.starter.planningscene.domain.Pose;
import com.example.starter.planningscene.domain.RobotModel;
import com.example.starter.planningscene.domain.SceneSnapshot;
import com.example.starter.planningscene.dto.CommitResponse;
import com.example.starter.planningscene.dto.JointValueDto;
import com.example.starter.planningscene.dto.ObjectOperationDto;
import com.example.starter.planningscene.dto.PoseDto;
import com.example.starter.planningscene.dto.SceneUpdateRequest;
import com.example.starter.planningscene.dto.SceneViewResponse;
import com.example.starter.planningscene.repo.SceneRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Planning Scene 版本服务：批量提交、乐观并发控制、幂等、引用维护与历史快照读取。
 *
 * <p>所有规则校验在写入前基于工作副本完成；持久化在单个数据库事务内进行，
 * 任一环节失败则整体回滚，版本号与历史行均不变。
 */
@Service
public class SceneService {

    private final SceneRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private volatile RobotModel robotModel;

    public SceneService(SceneRepository repository, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 原子提交一批场景修改。
     *
     * @param request 批量请求（含 requestId 与 expectedVersion）
     * @return 提交结果（新提交或幂等重放）
     */
    public CommitResponse commit(SceneUpdateRequest request) {
        validateRequestShape(request);
        String payloadHash = hashRequest(request);

        var existing = repository.findRequest(request.requestId());
        if (existing.isPresent()) {
            var record = existing.get();
            if (!record.payloadHash().equals(payloadHash)) {
                throw new SceneException.IdempotencyConflict(request.requestId());
            }
            return readReplayedResponse(record.responseJson());
        }

        long expected = request.expectedVersion();
        SceneSnapshot currentSnapshot = repository.loadSnapshot(repository.currentVersion())
                .orElseThrow(() -> new IllegalStateException("current scene snapshot missing"));
        if (expected < 0) {
            throw new SceneException.InvalidBatch("expectedVersion must not be negative");
        }
        if (expected > currentSnapshot.version()) {
            throw new SceneException.InvalidBatch(
                    "expectedVersion " + expected + " is greater than current version " + currentSnapshot.version());
        }

        // 基于期望版本的快照构建工作副本；规则非法时直接抛异常，不触碰数据库。
        SceneSnapshot baseSnapshot = expected == currentSnapshot.version()
                ? currentSnapshot
                : repository.loadSnapshot(expected)
                        .orElseThrow(() -> new SceneException.NotFound("version " + expected + " not found"));
        PlannedScene planned = applyBatch(baseSnapshot, request);

        try {
            return transactionTemplate.execute(status -> {
                var advanced = repository.tryAdvance(expected, planned.robotPose(),
                        List.copyOf(planned.joints().values()), List.copyOf(planned.objects().values()));
                if (advanced.isEmpty()) {
                    // 行级乐观闸门失败：自 expected 起已有他人提交，整批不写入。
                    long actualVersion = repository.currentVersion();
                    throw new SceneException.VersionConflict(actualVersion, expected,
                            diffObjectIds(baseSnapshot, repository.loadSnapshot(actualVersion).orElseThrow()),
                            diffJointNames(baseSnapshot, repository.loadSnapshot(actualVersion).orElseThrow()));
                }
                long newVersion = advanced.get();
                CommitResponse response = new CommitResponse(true, newVersion, false);
                repository.saveRequest(request.requestId(), payloadHash, 200,
                        writeJson(response), System.currentTimeMillis());
                return response;
            });
        } catch (DuplicateKeyException e) {
            // 并发下同一 requestId 的另一方先提交：重放其结果。
            var record = repository.findRequest(request.requestId()).orElseThrow(() -> e);
            if (!record.payloadHash().equals(payloadHash)) {
                throw new SceneException.IdempotencyConflict(request.requestId());
            }
            return readReplayedResponse(record.responseJson());
        }
    }

    /**
     * 读取当前版本快照。
     */
    public SceneViewResponse currentView() {
        long version = repository.currentVersion();
        return toView(repository.loadSnapshot(version)
                .orElseThrow(() -> new SceneException.NotFound("version " + version + " not found")));
    }

    /**
     * 读取任意历史版本的不可变快照；只按版本号查询历史行，不阻塞写入。
     *
     * @param version 历史版本号
     */
    public SceneViewResponse snapshot(long version) {
        if (version < 0) {
            throw new SceneException.InvalidBatch("version must not be negative");
        }
        return toView(repository.loadSnapshot(version)
                .orElseThrow(() -> new SceneException.NotFound("version " + version + " not found")));
    }

    /** 将一批操作应用到基础快照，全部规则校验通过后返回计划中的新场景。 */
    private PlannedScene applyBatch(SceneSnapshot base, SceneUpdateRequest request) {
        RobotModel model = getRobotModel();
        Map<String, ObjectState> objects = new LinkedHashMap<>(base.objects());
        Map<String, JointState> joints = new LinkedHashMap<>(base.joints());
        Pose robotPose = base.robotPose();

        if (request.robotPose() != null) {
            robotPose = toPose(request.robotPose());
        }

        if (request.jointUpdates() != null) {
            for (JointValueDto update : request.jointUpdates()) {
                String name = update.name();
                if (!model.joints().containsKey(name)) {
                    throw new SceneException.InvalidBatch("joint does not exist: " + name);
                }
                double position = update.position();
                if (!Double.isFinite(position)) {
                    throw new SceneException.InvalidBatch("joint position must be finite: " + name);
                }
                double[] bounds = model.joints().get(name);
                if (position < bounds[0] || position > bounds[1]) {
                    throw new SceneException.InvalidBatch(String.format(
                            "joint %s position %.6f out of bounds [%.6f, %.6f]",
                            name, position, bounds[0], bounds[1]));
                }
                joints.put(name, new JointState(name, position, bounds[0], bounds[1]));
            }
        }

        if (request.operations() != null) {
            for (ObjectOperationDto op : request.operations()) {
                applyObjectOperation(op, objects, model);
            }
        }

        // 最终一致性校验：所有世界对象引用的坐标系必须仍存在（Link 或未被删除的对象）。
        for (ObjectState obj : objects.values()) {
            if (obj.type() == ObjectType.WORLD && !frameExists(obj.frameId(), objects, model)) {
                throw new SceneException.InvalidBatch(
                        "object " + obj.objectId() + " references missing frame: " + obj.frameId());
            }
            if (obj.type() == ObjectType.ATTACHED && !isRobotLink(obj.linkId(), model)) {
                throw new SceneException.InvalidBatch(
                        "object " + obj.objectId() + " attached to missing link: " + obj.linkId());
            }
        }

        return new PlannedScene(robotPose, joints, objects);
    }

    /** 应用单个对象操作并就地维护工作副本与引用。 */
    private void applyObjectOperation(ObjectOperationDto op, Map<String, ObjectState> objects, RobotModel model) {
        switch (op.type()) {
            case ADD -> applyAdd(op, objects, model);
            case UPDATE -> applyUpdate(op, objects, model);
            case DELETE -> applyDelete(op, objects);
            case RENAME -> applyRename(op, objects, model);
            case ATTACH -> applyAttach(op, objects, model);
            case DETACH -> applyDetach(op, objects, model);
        }
    }

    private void applyAdd(ObjectOperationDto op, Map<String, ObjectState> objects, RobotModel model) {
        String id = requireId(op.objectId(), "objectId");
        if (objects.containsKey(id)) {
            throw new SceneException.InvalidBatch("object already exists: " + id);
        }
        boolean hasFrame = op.frameId() != null && !op.frameId().isBlank();
        boolean hasLink = op.linkId() != null && !op.linkId().isBlank();
        if (hasFrame == hasLink) {
            throw new SceneException.InvalidBatch(
                    "ADD " + id + " requires exactly one of frameId (world) or linkId (attached)");
        }
        if (op.geometry() == null || op.geometry().isBlank()) {
            throw new SceneException.InvalidBatch("ADD " + id + " requires geometry");
        }
        Pose pose = op.pose() == null ? Pose.origin() : toPose(op.pose());
        if (hasFrame) {
            ensureFrame(op.frameId(), objects, model, id);
            objects.put(id, new ObjectState(id, ObjectType.WORLD, op.frameId(), null,
                    op.geometry(), pose, List.of()));
        } else {
            ensureLink(op.linkId(), model, id);
            List<String> touch = normalizeTouchLinks(op.touchLinks(), model, id);
            objects.put(id, new ObjectState(id, ObjectType.ATTACHED, null, op.linkId(),
                    op.geometry(), pose, touch));
        }
    }

    private void applyUpdate(ObjectOperationDto op, Map<String, ObjectState> objects, RobotModel model) {
        String id = requireId(op.objectId(), "objectId");
        ObjectState existing = objects.get(id);
        if (existing == null) {
            throw new SceneException.InvalidBatch("cannot update missing object: " + id);
        }
        String geometry = op.geometry() == null || op.geometry().isBlank()
                ? existing.geometry() : op.geometry();
        Pose pose = op.pose() == null ? existing.pose() : toPose(op.pose());
        if (existing.type() == ObjectType.WORLD) {
            if (op.linkId() != null && !op.linkId().isBlank()) {
                throw new SceneException.InvalidBatch(
                        "world object " + id + " cannot set linkId on UPDATE; use ATTACH");
            }
            String frameId = op.frameId() == null || op.frameId().isBlank()
                    ? existing.frameId() : op.frameId();
            ensureFrame(frameId, objects, model, id);
            objects.put(id, new ObjectState(id, ObjectType.WORLD, frameId, null,
                    geometry, pose, List.of()));
        } else {
            if (op.frameId() != null && !op.frameId().isBlank()) {
                throw new SceneException.InvalidBatch(
                        "attached object " + id + " cannot set frameId on UPDATE; use DETACH");
            }
            String linkId = op.linkId() == null || op.linkId().isBlank()
                    ? existing.linkId() : op.linkId();
            ensureLink(linkId, model, id);
            List<String> touch = op.touchLinks() == null
                    ? existing.touchLinks() : normalizeTouchLinks(op.touchLinks(), model, id);
            objects.put(id, new ObjectState(id, ObjectType.ATTACHED, null, linkId,
                    geometry, pose, touch));
        }
    }

    /**
     * 删除对象：以该对象 ID 为坐标系的其他世界对象会被级联删除以同步维护引用；
     * 级联传递进行（A 引用 B，B 引用 C，删除 C 时一并删除）。
     */
    private void applyDelete(ObjectOperationDto op, Map<String, ObjectState> objects) {
        String id = requireId(op.objectId(), "objectId");
        if (!objects.containsKey(id)) {
            throw new SceneException.InvalidBatch("cannot delete missing object: " + id);
        }
        List<String> toRemove = new ArrayList<>();
        toRemove.add(id);
        int head = 0;
        while (head < toRemove.size()) {
            String frame = toRemove.get(head++);
            for (ObjectState obj : objects.values()) {
                if (obj.type() == ObjectType.WORLD
                        && frame.equals(obj.frameId())
                        && !toRemove.contains(obj.objectId())) {
                    toRemove.add(obj.objectId());
                }
            }
        }
        toRemove.forEach(objects::remove);
    }

    /** 重命名对象 ID，所有以旧 ID 为坐标系的世界对象同步改写引用。 */
    private void applyRename(ObjectOperationDto op, Map<String, ObjectState> objects, RobotModel model) {
        String oldId = requireId(op.objectId(), "objectId");
        String newId = requireId(op.newId(), "newId");
        if (!objects.containsKey(oldId)) {
            throw new SceneException.InvalidBatch("cannot rename missing object: " + oldId);
        }
        if (!oldId.equals(newId) && objects.containsKey(newId)) {
            throw new SceneException.InvalidBatch("rename target already exists: " + newId);
        }
        if (model.links().contains(newId)) {
            throw new SceneException.InvalidBatch("object id must not shadow a robot link/frame: " + newId);
        }
        ObjectState renamed = objects.remove(oldId);
        objects.put(newId, new ObjectState(newId, renamed.type(), renamed.frameId(),
                renamed.linkId(), renamed.geometry(), renamed.pose(), renamed.touchLinks()));
        if (!oldId.equals(newId)) {
            for (Map.Entry<String, ObjectState> entry : objects.entrySet()) {
                ObjectState obj = entry.getValue();
                if (obj.type() == ObjectType.WORLD && oldId.equals(obj.frameId())) {
                    entry.setValue(new ObjectState(obj.objectId(), ObjectType.WORLD, newId, null,
                            obj.geometry(), obj.pose(), List.of()));
                }
            }
        }
    }

    private void applyAttach(ObjectOperationDto op, Map<String, ObjectState> objects, RobotModel model) {
        String id = requireId(op.objectId(), "objectId");
        ObjectState existing = objects.get(id);
        if (existing == null) {
            throw new SceneException.InvalidBatch("cannot attach missing object: " + id);
        }
        if (existing.type() != ObjectType.WORLD) {
            throw new SceneException.InvalidBatch("object is already attached: " + id);
        }
        String linkId = op.linkId();
        ensureLink(linkId, model, id);
        Pose pose = op.pose() == null ? existing.pose() : toPose(op.pose());
        List<String> touch = normalizeTouchLinks(op.touchLinks(), model, id);
        objects.put(id, new ObjectState(id, ObjectType.ATTACHED, null, linkId,
                existing.geometry(), pose, touch));
    }

    private void applyDetach(ObjectOperationDto op, Map<String, ObjectState> objects, RobotModel model) {
        String id = requireId(op.objectId(), "objectId");
        ObjectState existing = objects.get(id);
        if (existing == null) {
            throw new SceneException.InvalidBatch("cannot detach missing object: " + id);
        }
        if (existing.type() != ObjectType.ATTACHED) {
            throw new SceneException.InvalidBatch("object is not attached: " + id);
        }
        String frameId = op.frameId();
        if (frameId == null || frameId.isBlank()) {
            throw new SceneException.InvalidBatch("detach " + id + " requires a world frameId");
        }
        ensureFrame(frameId, objects, model, id);
        Pose pose = op.pose() == null ? existing.pose() : toPose(op.pose());
        objects.put(id, new ObjectState(id, ObjectType.WORLD, frameId, null,
                existing.geometry(), pose, List.of()));
    }

    private List<String> diffObjectIds(SceneSnapshot oldSnapshot, SceneSnapshot newSnapshot) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, ObjectState> entry : newSnapshot.objects().entrySet()) {
            ObjectState old = oldSnapshot.objects().get(entry.getKey());
            if (old == null || !Objects.equals(old, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String id : oldSnapshot.objects().keySet()) {
            if (!newSnapshot.objects().containsKey(id)) {
                changed.add(id);
            }
        }
        return changed;
    }

    private List<String> diffJointNames(SceneSnapshot oldSnapshot, SceneSnapshot newSnapshot) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, JointState> entry : newSnapshot.joints().entrySet()) {
            JointState old = oldSnapshot.joints().get(entry.getKey());
            if (old == null || old.position() != entry.getValue().position()) {
                changed.add(entry.getKey());
            }
        }
        for (String name : oldSnapshot.joints().keySet()) {
            if (!newSnapshot.joints().containsKey(name)) {
                changed.add(name);
            }
        }
        return changed;
    }

    private RobotModel getRobotModel() {
        RobotModel model = robotModel;
        if (model == null) {
            synchronized (this) {
                model = robotModel;
                if (model == null) {
                    model = repository.loadRobotModel();
                    robotModel = model;
                }
            }
        }
        return model;
    }

    private void validateRequestShape(SceneUpdateRequest request) {
        if (request.expectedVersion() == null) {
            throw new SceneException.InvalidBatch("expectedVersion is required");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new SceneException.InvalidBatch("requestId is required");
        }
        if (request.robotPose() != null) {
            toPose(request.robotPose());
        }
        if (request.operations() != null) {
            for (ObjectOperationDto op : request.operations()) {
                if (op.type() == null) {
                    throw new SceneException.InvalidBatch("operation type is required");
                }
                if (op.pose() != null) {
                    toPose(op.pose());
                }
            }
        }
        if (request.jointUpdates() != null) {
            for (JointValueDto update : request.jointUpdates()) {
                if (update.name() == null || update.name().isBlank()) {
                    throw new SceneException.InvalidBatch("joint name is required");
                }
                if (update.position() == null || !Double.isFinite(update.position())) {
                    throw new SceneException.InvalidBatch(
                            "joint position must be a finite number: " + update.name());
                }
            }
        }
    }

    private static Pose toPose(PoseDto dto) {
        if (dto.x() == null || dto.y() == null || dto.z() == null
                || dto.qx() == null || dto.qy() == null || dto.qz() == null || dto.qw() == null) {
            throw new SceneException.InvalidBatch("pose components must all be provided");
        }
        if (!(Double.isFinite(dto.x()) && Double.isFinite(dto.y()) && Double.isFinite(dto.z())
                && Double.isFinite(dto.qx()) && Double.isFinite(dto.qy())
                && Double.isFinite(dto.qz()) && Double.isFinite(dto.qw()))) {
            throw new SceneException.InvalidBatch("pose components must be finite numbers");
        }
        return new Pose(dto.x(), dto.y(), dto.z(), dto.qx(), dto.qy(), dto.qz(), dto.qw());
    }

    private static boolean isRobotLink(String linkId, RobotModel model) {
        return linkId != null && !linkId.isBlank() && model.links().contains(linkId)
                && !"world".equals(linkId);
    }

    private static void ensureLink(String linkId, RobotModel model, String objectId) {
        if (!isRobotLink(linkId, model)) {
            throw new SceneException.InvalidBatch(
                    "object " + objectId + " references missing robot link: " + linkId);
        }
    }

    private static boolean frameExists(String frameId, Map<String, ObjectState> objects, RobotModel model) {
        return model.links().contains(frameId) || objects.containsKey(frameId);
    }

    private static void ensureFrame(String frameId, Map<String, ObjectState> objects,
                                    RobotModel model, String objectId) {
        if (frameId == null || frameId.isBlank() || !frameExists(frameId, objects, model)) {
            throw new SceneException.InvalidBatch(
                    "object " + objectId + " references missing frame: " + frameId);
        }
    }

    private static List<String> normalizeTouchLinks(List<String> touchLinks, RobotModel model, String objectId) {
        if (touchLinks == null || touchLinks.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String link : touchLinks) {
            if (link == null || link.isBlank() || !model.links().contains(link) || "world".equals(link)) {
                throw new SceneException.InvalidBatch(
                        "object " + objectId + " touch link does not exist: " + link);
            }
            if (!normalized.contains(link)) {
                normalized.add(link);
            }
        }
        return List.copyOf(normalized);
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new SceneException.InvalidBatch(field + " is required");
        }
        return value;
    }

    private SceneViewResponse toView(SceneSnapshot snapshot) {
        List<SceneViewResponse.JointView> jointViews = snapshot.joints().values().stream()
                .map(j -> new SceneViewResponse.JointView(j.name(), j.position(), j.lowerBound(), j.upperBound()))
                .toList();
        List<SceneViewResponse.ObjectView> objectViews = snapshot.objects().values().stream()
                .map(o -> new SceneViewResponse.ObjectView(o.objectId(), o.type().name(), o.frameId(),
                        o.linkId(), o.geometry(), toPoseDto(o.pose()), o.touchLinks()))
                .toList();
        return new SceneViewResponse(snapshot.version(), toPoseDto(snapshot.robotPose()),
                jointViews, objectViews);
    }

    private static PoseDto toPoseDto(Pose pose) {
        return new PoseDto(pose.x(), pose.y(), pose.z(), pose.qx(), pose.qy(), pose.qz(), pose.qw());
    }

    private String hashRequest(SceneUpdateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("failed to hash request", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private CommitResponse readReplayedResponse(String json) {
        try {
            CommitResponse response = objectMapper.readValue(json, CommitResponse.class);
            return new CommitResponse(response.committed(), response.version(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read stored response", e);
        }
    }

    /** 通过全部校验、等待持久化的计划场景。 */
    private record PlannedScene(Pose robotPose, Map<String, JointState> joints,
                                Map<String, ObjectState> objects) {
    }
}
