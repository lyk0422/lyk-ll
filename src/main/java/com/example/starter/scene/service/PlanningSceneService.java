package com.example.starter.scene.service;

import com.example.starter.scene.model.AttachmentCommand;
import com.example.starter.scene.model.ConflictResponse;
import com.example.starter.scene.model.JointUpdate;
import com.example.starter.scene.model.ObjectCommand;
import com.example.starter.scene.model.RobotPose;
import com.example.starter.scene.model.SceneSnapshot;
import com.example.starter.scene.model.SceneUpdateRequest;
import com.example.starter.scene.model.UpdateResult;
import com.example.starter.scene.repo.PlanningSceneRepository;
import com.example.starter.scene.repo.PlanningSceneRepository.CommitData;
import com.example.starter.scene.repo.PlanningSceneRepository.IdempotentRecord;
import com.example.starter.scene.repo.PlanningSceneRepository.JointLimit;
import com.example.starter.scene.repo.PlanningSceneRepository.VersionState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Planning Scene 核心应用服务。
 * <p>提交在单个数据库事务内完成：先对 scene_meta 加行锁串行化版本推进，
 * 再在内存工作副本上顺序执行并校验整批操作；任一操作非法直接抛异常回滚，
 * 版本号、当前表与历史表均不变化。快照读取不获取写锁，可与写入并发。
 */
@Service
public class PlanningSceneService {

    static final String REASON_VERSION_MISMATCH = "VERSION_MISMATCH";
    static final String REASON_IDEMPOTENT_MISMATCH = "IDEMPOTENT_PARAM_MISMATCH";

    private final PlanningSceneRepository repository;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final Clock clock;

    public PlanningSceneService(PlanningSceneRepository repository, ObjectMapper objectMapper,
                                Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.canonicalMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 提交一次批量场景修改。
     */
    @Transactional
    public UpdateResult submit(SceneUpdateRequest request) {
        // 行锁保证并发提交串行化：第二个事务在此等待，持锁后读到的是最新版本。
        long currentVersion = repository.lockAndGetCurrentVersion();

        String requestHash = canonicalHash(request);
        IdempotentRecord existing = repository.findRequest(request.requestId());
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw new SceneConflictException(new ConflictResponse(
                        request.requestId(), currentVersion, request.expectedVersion(),
                        List.of(), List.of(), REASON_IDEMPOTENT_MISMATCH));
            }
            // 同 requestId 同参数：返回原结果，不产生新版本。
            return new UpdateResult(request.requestId(), existing.version(), true);
        }

        if (request.expectedVersion() != currentVersion) {
            throw new SceneConflictException(new ConflictResponse(
                    request.requestId(), currentVersion, request.expectedVersion(),
                    changedObjectIds(request.expectedVersion(), currentVersion),
                    changedJointNames(request.expectedVersion(), currentVersion),
                    REASON_VERSION_MISMATCH));
        }

        CurrentScene current = repository.loadCurrent();
        Set<String> links = repository.findAllLinkNames();
        Map<String, JointLimit> limits = repository.findAllJointLimits();

        long newVersion = currentVersion + 1;
        MutableScene target = MutableScene.from(current);
        applyBatch(request, target, links, limits, newVersion);

        repository.applyCommit(new CommitData(
                newVersion, request.requestId(), requestHash,
                clock.millis(), target.pose, target.joints,
                target.objects, target.attachments));
        return new UpdateResult(request.requestId(), newVersion, false);
    }

    /**
     * 读取当前场景快照。
     */
    public SceneSnapshot currentSnapshot() {
        return repository.loadSnapshot(repository.loadCurrent().version());
    }

    /**
     * 读取任意历史版本的不可变快照；不存在抛 404。
     */
    public SceneSnapshot historicalSnapshot(long version) {
        SceneSnapshot snapshot = repository.loadSnapshot(version);
        if (snapshot == null) {
            throw new SceneNotFoundException("场景版本不存在: " + version);
        }
        return snapshot;
    }

    // ---- 批处理 -----------------------------------------------------------

    private void applyBatch(SceneUpdateRequest request, MutableScene scene,
                            Set<String> links, Map<String, JointLimit> limits,
                            long newVersion) {
        if (request.robotPose() != null) {
            RobotPose pose = request.robotPose();
            requireFinite(pose.x(), "robotPose.x");
            requireFinite(pose.y(), "robotPose.y");
            requireFinite(pose.z(), "robotPose.z");
            requireFinite(pose.qx(), "robotPose.qx");
            requireFinite(pose.qy(), "robotPose.qy");
            requireFinite(pose.qz(), "robotPose.qz");
            requireFinite(pose.qw(), "robotPose.qw");
            scene.pose = pose;
        }

        if (request.joints() != null) {
            Set<String> seen = new HashSet<>();
            for (JointUpdate update : request.joints()) {
                String name = update.jointName();
                if (!seen.add(name)) {
                    throw new SceneValidationException("批量请求中关节名重复: " + name);
                }
                JointLimit limit = limits.get(name);
                if (limit == null) {
                    throw new SceneValidationException("关节不存在: " + name);
                }
                double value = update.position();
                if (!Double.isFinite(value)) {
                    throw new SceneValidationException("关节位置必须为有限数值: " + name);
                }
                if (value < limit.lower() || value > limit.upper()) {
                    throw new SceneValidationException(String.format(
                            "关节 %s 位置 %s 超出范围 [%s, %s]",
                            name, value, limit.lower(), limit.upper()));
                }
                scene.joints.put(name, value);
            }
        }

        if (request.objects() != null) {
            for (ObjectCommand command : request.objects()) {
                applyObjectCommand(command, scene, links, newVersion);
            }
        }

        if (request.attachments() != null) {
            for (AttachmentCommand command : request.attachments()) {
                applyAttachmentCommand(command, scene, links, newVersion);
            }
        }
    }

    private void applyObjectCommand(ObjectCommand command, MutableScene scene,
                                    Set<String> links, long newVersion) {
        String id = command.objectId();
        if (id == null || id.isBlank()) {
            throw new SceneValidationException("对象操作缺少 objectId");
        }
        switch (command.op()) {
            case ADD -> {
                if (scene.objects.containsKey(id)) {
                    throw new SceneValidationException("对象 ID 已存在，无法新增: " + id);
                }
                requireFrame(command.frameId(), links, id);
                scene.objects.put(id, new CurrentScene.SceneObject(
                        command.frameId(), payloadText(command), newVersion));
            }
            case UPDATE -> {
                CurrentScene.SceneObject existing = scene.objects.get(id);
                if (existing == null) {
                    throw new SceneValidationException("待修改对象不存在: " + id);
                }
                if (scene.attachments.containsKey(id)) {
                    throw new SceneValidationException(
                            "对象处于附着状态，不能直接修改世界属性，请先 detach: " + id);
                }
                requireFrame(command.frameId(), links, id);
                scene.objects.put(id, new CurrentScene.SceneObject(
                        command.frameId(), payloadText(command), newVersion));
            }
            case DELETE -> {
                if (!scene.objects.containsKey(id)) {
                    throw new SceneValidationException("待删除对象不存在: " + id);
                }
                // 同步维护附着物引用：随对象一并删除。
                scene.objects.remove(id);
                scene.attachments.remove(id);
            }
            case RENAME -> {
                String newId = command.newObjectId();
                if (newId == null || newId.isBlank()) {
                    throw new SceneValidationException("RENAME 操作缺少 newObjectId: " + id);
                }
                if (!scene.objects.containsKey(id)) {
                    throw new SceneValidationException("待重命名对象不存在: " + id);
                }
                if (!newId.equals(id) && scene.objects.containsKey(newId)) {
                    throw new SceneValidationException(
                            "目标对象 ID 已存在，无法重命名: " + newId);
                }
                if (!newId.equals(id)) {
                    CurrentScene.SceneObject moved = scene.objects.remove(id);
                    scene.objects.put(newId, new CurrentScene.SceneObject(
                            moved.frameId(), moved.payload(), newVersion));
                    // 同步维护附着物引用。
                    String link = scene.attachments.remove(id);
                    if (link != null) {
                        scene.attachments.put(newId, link);
                    }
                }
            }
        }
    }

    private void applyAttachmentCommand(AttachmentCommand command, MutableScene scene,
                                        Set<String> links, long newVersion) {
        String id = command.objectId();
        if (id == null || id.isBlank()) {
            throw new SceneValidationException("附着物操作缺少 objectId");
        }
        CurrentScene.SceneObject object = scene.objects.get(id);
        if (object == null) {
            throw new SceneValidationException("附着物操作的对象不存在: " + id);
        }
        switch (command.op()) {
            case ATTACH -> {
                String link = command.linkName();
                if (link == null || link.isBlank()) {
                    throw new SceneValidationException("ATTACH 操作缺少 linkName: " + id);
                }
                if (!links.contains(link)) {
                    throw new SceneValidationException(
                            "附着目标 Link 不存在: " + link);
                }
                if (scene.attachments.containsKey(id)) {
                    throw new SceneValidationException(
                            "对象已处于附着状态，不能重复 attach: " + id);
                }
                scene.attachments.put(id, link);
                scene.objects.put(id, new CurrentScene.SceneObject(
                        null, object.payload(), newVersion));
            }
            case DETACH -> {
                if (!scene.attachments.containsKey(id)) {
                    throw new SceneValidationException(
                            "对象未处于附着状态，不能 detach: " + id);
                }
                requireFrame(command.frameId(), links, id);
                scene.attachments.remove(id);
                scene.objects.put(id, new CurrentScene.SceneObject(
                        command.frameId(), object.payload(), newVersion));
            }
        }
    }

    private void requireFrame(String frameId, Set<String> links, String objectId) {
        if (frameId == null || frameId.isBlank()) {
            throw new SceneValidationException(
                    "对象缺少参考坐标系 frameId: " + objectId);
        }
        if (!links.contains(frameId)) {
            throw new SceneValidationException(
                    "对象引用的坐标系不存在: " + frameId);
        }
    }

    private void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new SceneValidationException(field + " 必须为有限数值");
        }
    }

    private String payloadText(ObjectCommand command) {
        if (command.payload() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(command.payload());
        } catch (JsonProcessingException ex) {
            throw new SceneValidationException("对象 payload 不是合法 JSON: " + ex.getOriginalMessage());
        }
    }

    // ---- 冲突差异 ----------------------------------------------------------

    private List<String> changedObjectIds(long expectedVersion, long currentVersion) {
        VersionState oldState = expectedVersion >= 0
                ? repository.loadVersionState(expectedVersion) : null;
        VersionState newState = repository.loadVersionState(currentVersion);
        if (oldState == null) {
            // 期望版本根本不存在：按全部当前对象均已变化处理。
            return new ArrayList<>(new TreeSet<>(newState.objects().keySet()));
        }
        Set<String> changed = new TreeSet<>();
        Set<String> all = new HashSet<>(oldState.objects().keySet());
        all.addAll(newState.objects().keySet());
        for (String id : all) {
            VersionState.ObjectState before = oldState.objects().get(id);
            VersionState.ObjectState after = newState.objects().get(id);
            String beforeLink = oldState.attachments().get(id);
            String afterLink = newState.attachments().get(id);
            if (before == null || after == null
                    || !java.util.Objects.equals(before.frameId(), after.frameId())
                    || !java.util.Objects.equals(before.payload(), after.payload())
                    || !java.util.Objects.equals(beforeLink, afterLink)) {
                changed.add(id);
            }
        }
        return new ArrayList<>(changed);
    }

    private List<String> changedJointNames(long expectedVersion, long currentVersion) {
        VersionState oldState = expectedVersion >= 0
                ? repository.loadVersionState(expectedVersion) : null;
        VersionState newState = repository.loadVersionState(currentVersion);
        if (oldState == null) {
            return new ArrayList<>(new TreeSet<>(newState.joints().keySet()));
        }
        Set<String> changed = new TreeSet<>();
        Set<String> all = new HashSet<>(oldState.joints().keySet());
        all.addAll(newState.joints().keySet());
        for (String name : all) {
            Double before = oldState.joints().get(name);
            Double after = newState.joints().get(name);
            if (before == null || after == null || before.doubleValue() != after.doubleValue()) {
                changed.add(name);
            }
        }
        return new ArrayList<>(changed);
    }

    // ---- 幂等哈希 ----------------------------------------------------------

    private String canonicalHash(SceneUpdateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objects", canonicalObjects(request.objects()));
        body.put("joints", request.joints() == null ? null : request.joints().stream()
                .map(j -> Map.of("jointName", j.jointName(), "position", j.position()))
                .toList());
        body.put("robotPose", request.robotPose() == null ? null : poseMap(request.robotPose()));
        body.put("attachments", request.attachments() == null ? null
                : request.attachments().stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("op", a.op());
                    m.put("objectId", a.objectId());
                    m.put("linkName", a.linkName());
                    m.put("frameId", a.frameId());
                    return m;
                })
                .toList());
        try {
            String json = canonicalMapper.writeValueAsString(body);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算请求幂等哈希", ex);
        }
    }

    private List<Map<String, Object>> canonicalObjects(List<ObjectCommand> objects) {
        if (objects == null) {
            return null;
        }
        return objects.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", o.op());
            m.put("objectId", o.objectId());
            m.put("newObjectId", o.newObjectId());
            m.put("frameId", o.frameId());
            m.put("payload", canonicalPayload(o.payload()));
            return m;
        }).toList();
    }

    private Map<String, Double> poseMap(RobotPose pose) {
        Map<String, Double> m = new TreeMap<>();
        m.put("x", pose.x());
        m.put("y", pose.y());
        m.put("z", pose.z());
        m.put("qx", pose.qx());
        m.put("qy", pose.qy());
        m.put("qz", pose.qz());
        m.put("qw", pose.qw());
        return m;
    }

    /**
     * 把 payload 的 JsonNode 转成普通 Map/List 树，使序列化时按键排序，
     * 避免同一 JSON 仅字段顺序不同就被判定为幂等参数冲突。
     */
    private Object canonicalPayload(com.fasterxml.jackson.databind.JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        try {
            return canonicalMapper.treeToValue(payload, Object.class);
        } catch (JsonProcessingException ex) {
            throw new SceneValidationException("对象 payload 不是合法 JSON: " + ex.getOriginalMessage());
        }
    }

    /**
     * 事务内使用的可变工作副本；校验与变更全部先作用于副本，
     * 全部成功后一次性持久化，保证无部分写入。
     */
    private static final class MutableScene {
        private RobotPose pose;
        private final Map<String, Double> joints;
        private final Map<String, CurrentScene.SceneObject> objects;
        private final Map<String, String> attachments;

        private MutableScene(RobotPose pose, Map<String, Double> joints,
                             Map<String, CurrentScene.SceneObject> objects,
                             Map<String, String> attachments) {
            this.pose = pose;
            this.joints = joints;
            this.objects = objects;
            this.attachments = attachments;
        }

        static MutableScene from(CurrentScene current) {
            return new MutableScene(
                    current.pose(),
                    new HashMap<>(current.joints()),
                    new HashMap<>(current.objects()),
                    new HashMap<>(current.attachments()));
        }
    }
}
