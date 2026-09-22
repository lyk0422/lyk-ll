package com.example.starter.tf2.service;

import com.example.starter.tf2.config.Tf2CacheProperties;
import com.example.starter.tf2.domain.EdgeType;
import com.example.starter.tf2.domain.Quat;
import com.example.starter.tf2.domain.RigidTransform;
import com.example.starter.tf2.domain.Vec3;
import com.example.starter.tf2.persistence.TransformSample;
import com.example.starter.tf2.persistence.TransformSampleRepository;
import com.example.starter.tf2.web.TransformRequest;
import com.example.starter.tf2.web.TransformResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * TF2 风格坐标变换缓存核心服务。
 *
 * <p>图模型：每条边 parent -&gt; child，子坐标系最多一个父，整体为有向森林。
 * 静态边全时刻有效；动态边保存带纳秒时间戳的样本，允许乱序补录，查询时在相邻
 * 样本间做平移线性插值与旋转四元数球面插值。</p>
 *
 * <p>并发语义：进程内读写锁串行化写入；查询基于一次全量读取形成的不可变快照，
 * 写入期间的历史查询观察不到半写状态。淘汰只删除窗口外（stamp &lt; 最新样本 - 窗口）
 * 的历史样本。</p>
 */
@Service
public class Tf2CacheService {

    /** 幂等判定时平移/旋转分量的容差。 */
    private static final double EPS = 1e-9;

    private final TransformSampleRepository repository;
    private final Tf2CacheProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public Tf2CacheService(TransformSampleRepository repository, Tf2CacheProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 写入一条静态/动态变换样本。
     */
    public void publish(TransformRequest request) {
        validate(request);

        Quat rotation = new Quat(request.qx(), request.qy(), request.qz(), request.qw()).normalized();
        Vec3 translation = new Vec3(request.tx(), request.ty(), request.tz());
        EdgeType type = Boolean.TRUE.equals(request.dynamic()) ? EdgeType.DYNAMIC : EdgeType.STATIC;
        long stamp = type == EdgeType.DYNAMIC ? request.stampNanos() : 0L;

        lock.writeLock().lock();
        try {
            // 事务在锁内提交：并发写入不会在“检查与插入之间”看到彼此未提交状态
            transactionTemplate.executeWithoutResult(status -> {
                Snapshot snapshot = takeSnapshot();

                // 有向森林：子坐标系只能有一个父
                String existingParent = snapshot.childToParent.get(request.childFrame());
                if (existingParent != null && !existingParent.equals(request.parentFrame())) {
                    throw new Tf2Exception.FrameParentConflict(
                            request.childFrame(), existingParent, request.parentFrame());
                }

                // 环检测：新边 parent->child 成环当且仅当 child 已是 parent 的祖先
                rejectCycle(request.parentFrame(), request.childFrame(), snapshot);

                // 同参重放幂等 / 异参冲突；返回 true 表示本次为重放，不落库、不淘汰
                if (checkReplay(request, type, stamp, rotation, translation, snapshot)) {
                    return;
                }

                TransformSample sample = new TransformSample(
                        null,
                        request.parentFrame(),
                        request.childFrame(),
                        type,
                        stamp,
                        translation,
                        rotation,
                        request.source(),
                        Instant.now());
                try {
                    repository.insert(sample);
                } catch (DuplicateKeyException e) {
                    // 唯一约束兜底：极端并发下同参请求按幂等成功处理，不执行淘汰
                    status.setRollbackOnly();
                    return;
                }

                if (type == EdgeType.DYNAMIC) {
                    evict(request.parentFrame(), request.childFrame());
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 查询 source -&gt; target 在 stampNanos 时刻的刚体变换。
     */
    public TransformResponse lookup(String source, String target, long stampNanos) {
        lock.readLock().lock();
        try {
            // 单次全量读取形成不可变快照：并发插入/淘汰不会影响本次计算
            Snapshot snapshot = takeSnapshot();

            if (!snapshot.frames.contains(source)) {
                throw new Tf2Exception.FrameNotFound(source);
            }
            if (!snapshot.frames.contains(target)) {
                throw new Tf2Exception.FrameNotFound(target);
            }

            List<String> path = findUndirectedPath(source, target, snapshot);
            if (path == null) {
                throw new Tf2Exception.ChainDisconnected(source, target);
            }

            RigidTransform accumulated = RigidTransform.IDENTITY;
            for (int i = 0; i + 1 < path.size(); i++) {
                accumulated = accumulated.compose(
                        hopTransform(path.get(i), path.get(i + 1), stampNanos, snapshot));
            }

            return new TransformResponse(
                    source, target, stampNanos, path,
                    accumulated.translation().x(),
                    accumulated.translation().y(),
                    accumulated.translation().z(),
                    accumulated.rotation().x(),
                    accumulated.rotation().y(),
                    accumulated.rotation().z(),
                    accumulated.rotation().w());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void validate(TransformRequest request) {
        if (!finite(request.tx()) || !finite(request.ty()) || !finite(request.tz())) {
            throw new Tf2Exception.InvalidRequest("平移分量必须为有限数值");
        }
        if (!finite(request.qx()) || !finite(request.qy())
                || !finite(request.qz()) || !finite(request.qw())) {
            throw new Tf2Exception.InvalidRequest("旋转四元数分量必须为有限数值");
        }
        double qn = Math.sqrt(request.qx() * request.qx() + request.qy() * request.qy()
                + request.qz() * request.qz() + request.qw() * request.qw());
        if (qn < 1e-12) {
            throw new Tf2Exception.InvalidRequest("旋转四元数不能为零向量");
        }
        if (Boolean.TRUE.equals(request.dynamic()) && request.stampNanos() == null) {
            throw new Tf2Exception.InvalidRequest("动态变换必须提供 stampNanos");
        }
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && Double.isFinite(v);
    }

    /**
     * 若新边 parent->child 会成环，抛出携带完整候选环路径的异常。
     */
    private void rejectCycle(String parent, String child, Snapshot snapshot) {
        if (parent.equals(child)) {
            throw new Tf2Exception.CycleDetected(List.of(parent, child));
        }
        // 沿 parent 的祖先链向上走；若遇到 child 则成环
        List<String> ancestors = new ArrayList<>();
        String cur = parent;
        Set<String> guard = new HashSet<>();
        while (cur != null && guard.add(cur)) {
            ancestors.add(cur);
            if (cur.equals(child)) {
                List<String> cycle = new ArrayList<>();
                for (int i = ancestors.size() - 1; i >= 0; i--) {
                    cycle.add(ancestors.get(i));
                }
                cycle.add(child);
                throw new Tf2Exception.CycleDetected(cycle);
            }
            cur = snapshot.childToParent.get(cur);
        }
    }

    /**
     * 同父子关系下的重放/冲突判定。
     * <ul>
     *   <li>静态边：每父子关系只保留一条；位姿相同视为幂等重放，位姿不同返回 409。</li>
     *   <li>动态边：同父子+时间戳+来源已有样本时，内容相同幂等忽略，内容不同 409；
     *       不同来源占用同一时间戳时，内容相同亦幂等忽略，内容不同 409。</li>
     * </ul>
     *
     * @return true 表示本次为幂等重放，调用方不再落库
     */
    private boolean checkReplay(TransformRequest request, EdgeType type, long stamp,
                                Quat rotation, Vec3 translation, Snapshot snapshot) {
        if (type == EdgeType.STATIC) {
            if (snapshot.dynamicSamples.containsKey(request.childFrame())) {
                throw new Tf2Exception.ConflictingReplay(String.format(
                        "边类型冲突：父子=%s->%s 已存在动态样本，不能再写入静态变换",
                        request.parentFrame(), request.childFrame()));
            }
            TransformSample existing = snapshot.staticSamples.get(request.childFrame());
            if (existing == null) {
                return false;
            }
            if (samePose(existing, translation, rotation)) {
                return true;
            }
            throw new Tf2Exception.ConflictingReplay(String.format(
                    "静态边冲突：父子=%s->%s 已存在不同内容的静态变换（来源=%s）",
                    request.parentFrame(), request.childFrame(), existing.source()));
        }

        if (snapshot.staticSamples.containsKey(request.childFrame())) {
            throw new Tf2Exception.ConflictingReplay(String.format(
                    "边类型冲突：父子=%s->%s 已存在静态变换，不能再写入动态样本",
                    request.parentFrame(), request.childFrame()));
        }

        List<TransformSample> sameKey = repository.findExact(
                request.parentFrame(), request.childFrame(), type, stamp, request.source());
        if (!sameKey.isEmpty()) {
            TransformSample existing = sameKey.get(0);
            if (samePose(existing, translation, rotation)) {
                return true;
            }
            throw new Tf2Exception.ConflictingReplay(String.format(
                    "同参重放冲突：父子=%s->%s 时间戳=%d 来源=%s 已存在不同内容的样本",
                    request.parentFrame(), request.childFrame(), stamp, request.source()));
        }

        // 不同来源对同一条边同一时间戳的写入：内容相同幂等，内容不同 409
        for (TransformSample existing :
                snapshot.dynamicSamples.getOrDefault(request.childFrame(), List.of())) {
            if (existing.stampNanos() == stamp) {
                if (samePose(existing, translation, rotation)) {
                    return true;
                }
                throw new Tf2Exception.ConflictingReplay(String.format(
                        "时刻冲突：父子=%s->%s 时间戳=%d 已被来源=%s 的不同内容样本占用",
                        request.parentFrame(), request.childFrame(), stamp, existing.source()));
            }
        }
        return false;
    }

    private static boolean samePose(TransformSample existing, Vec3 translation, Quat rotation) {
        return existing.translation().nearlyEquals(translation, EPS)
                && existing.rotation().nearlyEquals(rotation, EPS);
    }

    /**
     * 淘汰该动态边窗口外历史样本：仅删除 stamp &lt; 最新样本 - windowNanos 的行。
     */
    private void evict(String parent, String child) {
        Long maxStamp = repository.findMaxStamp(parent, child);
        if (maxStamp == null) {
            return;
        }
        long cutoff = maxStamp - properties.windowNanos();
        repository.deleteSamplesBefore(parent, child, cutoff);
    }

    /**
     * 计算路径上一跳 from-&gt;to（图中存在直接边）在 stamp 时刻的变换，
     * 方向与边存储方向相反时取逆。
     */
    private RigidTransform hopTransform(String from, String to, long stamp, Snapshot snapshot) {
        String storedParent = snapshot.childToParent.get(to);
        boolean forward = from.equals(storedParent);
        String child = forward ? to : from;
        RigidTransform edgeAtTime = forward
                ? edgeAtTime(child, stamp, snapshot)
                : edgeAtTime(child, stamp, snapshot).inverse();
        return edgeAtTime;
    }

    /**
     * 取子坐标系 child 对应边在 stamp 时刻（父系下）的位姿。
     */
    private RigidTransform edgeAtTime(String child, long stamp, Snapshot snapshot) {
        TransformSample staticSample = snapshot.staticSamples.get(child);
        if (staticSample != null) {
            return new RigidTransform(staticSample.translation(), staticSample.rotation());
        }

        List<TransformSample> samples = snapshot.dynamicSamples.get(child);
        if (samples == null || samples.isEmpty()) {
            // 不可达：路径上的帧必然存在入边样本
            throw new IllegalStateException("快照缺少边样本: " + child);
        }

        long earliest = samples.get(0).stampNanos();
        long latest = samples.get(samples.size() - 1).stampNanos();
        long windowStart = latest - properties.windowNanos();
        String edge = snapshot.childToParent.get(child) + "->" + child;

        if (stamp < windowStart) {
            throw new Tf2Exception.OutsideCacheWindow(edge, stamp, windowStart);
        }
        if (stamp < earliest) {
            throw new Tf2Exception.TimeBeforeEarliest(edge, stamp, earliest);
        }
        if (stamp > latest) {
            throw new Tf2Exception.TimeAfterLatest(edge, stamp, latest);
        }

        // 样本允许乱序补录但快照已按时间升序；定位包围样本
        for (int i = 0; i < samples.size(); i++) {
            TransformSample s = samples.get(i);
            if (s.stampNanos() == stamp) {
                return new RigidTransform(s.translation(), s.rotation());
            }
            if (i + 1 < samples.size()) {
                TransformSample s1 = samples.get(i + 1);
                if (stamp > s.stampNanos() && stamp < s1.stampNanos()) {
                    double u = (double) (stamp - s.stampNanos())
                            / (double) (s1.stampNanos() - s.stampNanos());
                    return RigidTransform.interpolate(
                            new RigidTransform(s.translation(), s.rotation()),
                            new RigidTransform(s1.translation(), s1.rotation()),
                            u);
                }
            }
        }
        // 边界相等情况已在上方覆盖
        TransformSample only = samples.get(0);
        return new RigidTransform(only.translation(), only.rotation());
    }

    /**
     * 无向 BFS 求 source 到 target 的帧路径；不连通返回 null。
     */
    private List<String> findUndirectedPath(String source, String target, Snapshot snapshot) {
        if (source.equals(target)) {
            return new ArrayList<>(List.of(source));
        }
        Map<String, String> cameFrom = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(source);
        cameFrom.put(source, source);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : snapshot.neighbors(cur)) {
                if (!cameFrom.containsKey(next)) {
                    cameFrom.put(next, cur);
                    if (next.equals(target)) {
                        LinkedList<String> path = new LinkedList<>();
                        String back = target;
                        while (!back.equals(source)) {
                            path.addFirst(back);
                            back = cameFrom.get(back);
                        }
                        path.addFirst(source);
                        return path;
                    }
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private Snapshot takeSnapshot() {
        return Snapshot.from(repository.findAll());
    }

    /**
     * 某一时刻全量样本的不可变视图。
     */
    private static final class Snapshot {
        private final Map<String, String> childToParent = new HashMap<>();
        private final Map<String, TransformSample> staticSamples = new HashMap<>();
        private final Map<String, List<TransformSample>> dynamicSamples = new HashMap<>();
        private final Set<String> frames = new LinkedHashSet<>();

        private static Snapshot from(List<TransformSample> rows) {
            Snapshot s = new Snapshot();
            for (TransformSample row : rows) {
                s.frames.add(row.parentFrame());
                s.frames.add(row.childFrame());
                s.childToParent.putIfAbsent(row.childFrame(), row.parentFrame());
                if (row.type() == EdgeType.STATIC) {
                    s.staticSamples.putIfAbsent(row.childFrame(), row);
                } else {
                    s.dynamicSamples.computeIfAbsent(row.childFrame(), k -> new ArrayList<>()).add(row);
                }
            }
            s.dynamicSamples.values().forEach(list ->
                    list.sort(java.util.Comparator.comparingLong(TransformSample::stampNanos)));
            return s;
        }

        private List<String> neighbors(String frame) {
            List<String> result = new ArrayList<>();
            String parent = childToParent.get(frame);
            if (parent != null) {
                result.add(parent);
            }
            for (Map.Entry<String, String> e : childToParent.entrySet()) {
                if (e.getValue().equals(frame)) {
                    result.add(e.getKey());
                }
            }
            return result;
        }
    }
}
