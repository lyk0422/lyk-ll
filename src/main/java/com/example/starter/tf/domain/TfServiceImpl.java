package com.example.starter.tf.domain;

import com.example.starter.tf.math.Transform3d;
import com.example.starter.tf.persistence.EdgeRow;
import com.example.starter.tf.persistence.EvictionOutcome;
import com.example.starter.tf.persistence.SampleRow;
import com.example.starter.tf.persistence.TfRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link TfService} 的默认实现。
 *
 * <p>并发语义：进程内公平 {@link ReentrantReadWriteLock} 严格包住数据库事务——
 * 已开始的历史查询持有读锁期间，任何写入（含缓存淘汰）都不能提交，查询观察到一致快照；
 * 查询之间可并行。
 *
 * <p>数据时间窗口以每条动态边的最新样本为锚点：早于 {@code latest - windowNanos}
 * 的样本在写入后与显式淘汰时被移除，淘汰水位 {@code evicted_upto_nanos} 单调推进。
 */
@Service
public class TfServiceImpl implements TfService {

    private final TfRepository repository;
    private final NanoClock clock;
    private final long windowNanos;
    private final TransactionTemplate readWriteTx;
    private final TransactionTemplate readOnlyTx;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public TfServiceImpl(TfRepository repository,
                         NanoClock clock,
                         PlatformTransactionManager transactionManager,
                         @Value("${tf.cache.window-nanos:10000000000}") long windowNanos) {
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("tf.cache.window-nanos 必须为正数");
        }
        this.repository = repository;
        this.clock = clock;
        this.windowNanos = windowNanos;
        this.readWriteTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.readOnlyTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void registerFrame(String frame) {
        lock.writeLock().lock();
        try {
            readWriteTx.executeWithoutResult(status -> ensureFrame(frame));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void writeStatic(String parentFrame, String childFrame, String source, Transform3d pose) {
        if (parentFrame.equals(childFrame)) {
            throw new TfException.CycleDetected(List.of(childFrame, childFrame));
        }
        Transform3d normalized = pose.normalized();
        lock.writeLock().lock();
        try {
            readWriteTx.executeWithoutResult(status -> doWriteStatic(parentFrame, childFrame, source, normalized));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void writeDynamic(String parentFrame, String childFrame, String source,
                             long stampNanos, Transform3d pose) {
        if (parentFrame.equals(childFrame)) {
            throw new TfException.CycleDetected(List.of(childFrame, childFrame));
        }
        Transform3d normalized = pose.normalized();
        lock.writeLock().lock();
        try {
            readWriteTx.executeWithoutResult(status ->
                    doWriteDynamic(parentFrame, childFrame, source, stampNanos, normalized));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public LookupResult lookup(String source, String target, long stampNanos) {
        lock.readLock().lock();
        try {
            return readOnlyTx.execute(status -> doLookup(source, target, stampNanos));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int evictExpired() {
        lock.writeLock().lock();
        try {
            Integer deleted = readWriteTx.execute(status -> doEvictExpired());
            return deleted == null ? 0 : deleted;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void doWriteStatic(String parentFrame, String childFrame, String source, Transform3d normalized) {
        ensureFrame(parentFrame);
        ensureFrame(childFrame);
        long now = clock.nowNanos();

        EdgeRow edge = obtainEdge(parentFrame, childFrame, EdgeType.STATIC, source, now);
        SampleRow stored = repository.findStaticPose(edge.id())
                .orElse(null);
        if (stored == null) {
            // 并发下另一个事务刚建边但尚未写入位姿的窗口由写锁串行化排除；此处仅防御性兜底
            repository.insertStaticPose(edge.id(), source, toRow(0L, source, normalized), now);
            return;
        }
        if (!samePose(stored, normalized)) {
            throw new TfException.ReplayConflict(
                    "静态边 " + parentFrame + " -> " + childFrame + " 已存在内容不同的变换");
        }
        // 同参重放：幂等，不重复采样
    }

    private void doWriteDynamic(String parentFrame, String childFrame, String source,
                                long stampNanos, Transform3d normalized) {
        ensureFrame(parentFrame);
        ensureFrame(childFrame);
        long now = clock.nowNanos();

        EdgeRow edge = obtainEdge(parentFrame, childFrame, EdgeType.DYNAMIC, source, now);
        long edgeId = edge.id();

        Optional<SampleRow> sameStamp = repository.findDynamicSample(edgeId, stampNanos);
        if (sameStamp.isPresent()) {
            SampleRow stored = sameStamp.get();
            if (stored.source().equals(source) && samePose(stored, normalized)) {
                // 同父子、同时间戳、同来源且同参：幂等，不重复采样
                return;
            }
            throw new TfException.ReplayConflict("边 " + parentFrame + " -> " + childFrame
                    + " 在时刻 " + stampNanos + " 已存在来源或内容不同的样本");
        }

        try {
            repository.insertDynamicSample(edgeId, toRow(stampNanos, source, normalized), now);
        } catch (DuplicateKeyException concurrent) {
            // 唯一键竞争：以已落库样本为准重新判定幂等/冲突
            SampleRow stored = repository.findDynamicSample(edgeId, stampNanos)
                    .orElseThrow(() -> concurrent);
            if (stored.source().equals(source) && samePose(stored, normalized)) {
                return;
            }
            throw new TfException.ReplayConflict("边 " + parentFrame + " -> " + childFrame
                    + " 在时刻 " + stampNanos + " 已存在来源或内容不同的样本");
        }
        repository.touchFirstSample(edgeId, stampNanos);
        evictEdge(edgeId);
    }

    /**
     * 取得 childFrame 的唯一入边：不存在则做环检测后创建；并发撞 child 唯一键时重读并校验。
     */
    private EdgeRow obtainEdge(String parentFrame, String childFrame, EdgeType edgeType,
                               String source, long now) {
        Optional<EdgeRow> existing = repository.findEdgeByChild(childFrame);
        if (existing.isPresent()) {
            EdgeRow edge = existing.get();
            if (edge.edgeType() != edgeType || !edge.parentFrame().equals(parentFrame)) {
                throw new TfException.ReplayConflict(
                        "子坐标系 " + childFrame + " 已存在不同类型或不同父坐标系的边");
            }
            return edge;
        }

        rejectCycle(parentFrame, childFrame, snapshotParentIndex());
        try {
            long edgeId = repository.insertEdge(parentFrame, childFrame, edgeType, source, now);
            return repository.findEdgeByChild(childFrame)
                    .orElseGet(() -> new EdgeRow(edgeId, parentFrame, childFrame, edgeType, source, now, null, null));
        } catch (DuplicateKeyException concurrentCreate) {
            EdgeRow raced = repository.findEdgeByChild(childFrame).orElseThrow(() -> concurrentCreate);
            if (raced.edgeType() != edgeType || !raced.parentFrame().equals(parentFrame)) {
                throw new TfException.ReplayConflict(
                        "子坐标系 " + childFrame + " 已存在不同类型或不同父坐标系的边");
            }
            return raced;
        }
    }

    private LookupResult doLookup(String source, String target, long stampNanos) {
        if (!repository.frameExists(source)) {
            throw new TfException.FrameNotFound(source);
        }
        if (!repository.frameExists(target)) {
            throw new TfException.FrameNotFound(target);
        }
        if (source.equals(target)) {
            return new LookupResult(List.of(source), Transform3d.identity(), false);
        }

        List<EdgeRow> edges = repository.findAllEdges();
        Map<String, EdgeRow> byChild = new HashMap<>();
        for (EdgeRow edge : edges) {
            byChild.put(edge.childFrame(), edge);
        }

        List<String> nodePath = findNodePath(source, target, byChild);
        Transform3d composed = Transform3d.identity();
        boolean interpolated = false;
        for (int i = 0; i < nodePath.size() - 1; i++) {
            String from = nodePath.get(i);
            String to = nodePath.get(i + 1);
            EdgeRow upward = byChild.get(from);
            Transform3d local;
            boolean localInterpolated;
            if (upward != null && upward.parentFrame().equals(to)) {
                // from 为子、to 为父：存储的正是 T_to_from，直接使用
                Evaluated evaluated = evaluate(upward, stampNanos);
                local = evaluated.transform();
                localInterpolated = evaluated.interpolated();
            } else {
                EdgeRow downward = byChild.get(to);
                if (downward == null || !downward.parentFrame().equals(from)) {
                    throw new TfException.NoTransformPath(source, target);
                }
                // from 为父、to 为子：存储 T_from_to，需要其逆 T_to_from
                Evaluated evaluated = evaluate(downward, stampNanos);
                local = evaluated.transform().inverse();
                localInterpolated = evaluated.interpolated();
            }
            // T_target_source = T_{v(i+1) vi} * ... * T_{v1 v0}，每跳左乘
            composed = local.multiply(composed);
            interpolated |= localInterpolated;
        }
        return new LookupResult(List.copyOf(nodePath), composed, interpolated);
    }

    private int doEvictExpired() {
        int total = 0;
        for (EdgeRow edge : repository.findAllEdges()) {
            if (edge.edgeType() == EdgeType.DYNAMIC) {
                total += evictEdge(edge.id());
            }
        }
        return total;
    }

    /**
     * 基于当前最新样本滑动窗口淘汰一条动态边的窗口外样本，推进淘汰水位。
     *
     * @return 实际删除条数
     */
    private int evictEdge(long edgeId) {
        Optional<SampleRow> latest = repository.findLastSample(edgeId);
        if (latest.isEmpty()) {
            return 0;
        }
        long cutoff = Math.subtractExact(latest.get().stampNanos(), windowNanos);
        Optional<EvictionOutcome> outcome = repository.deleteSamplesOlderThan(edgeId, cutoff);
        if (outcome.isPresent()) {
            repository.updateEvictedUpto(edgeId, outcome.get().maxStampNanos());
            return outcome.get().deletedCount();
        }
        return 0;
    }

    /**
     * 计算 source 到 target 的无向森林节点链路；不同树抛出 {@link TfException.NoTransformPath}。
     */
    private List<String> findNodePath(String source, String target, Map<String, EdgeRow> byChild) {
        LinkedHashMap<String, String> sourceAncestors = ancestorChain(source, byChild);
        LinkedHashMap<String, String> targetAncestors = ancestorChain(target, byChild);

        String lca = null;
        for (String frame : sourceAncestors.keySet()) {
            if (targetAncestors.containsKey(frame)) {
                lca = frame;
                break;
            }
        }
        if (lca == null) {
            throw new TfException.NoTransformPath(source, target);
        }

        List<String> path = new ArrayList<>();
        for (String frame : sourceAncestors.keySet()) {
            path.add(frame);
            if (frame.equals(lca)) {
                break;
            }
        }
        List<String> tail = new ArrayList<>();
        for (String frame : targetAncestors.keySet()) {
            if (frame.equals(lca)) {
                break;
            }
            tail.add(frame);
        }
        java.util.Collections.reverse(tail);
        path.addAll(tail);
        return path;
    }

    /**
     * 返回从 frame 沿父指针到根的有序链（frame 在前，根在后），保持遍历顺序。
     */
    private LinkedHashMap<String, String> ancestorChain(String frame, Map<String, EdgeRow> byChild) {
        LinkedHashMap<String, String> chain = new LinkedHashMap<>();
        String current = frame;
        Set<String> guard = new HashSet<>();
        while (current != null) {
            if (!guard.add(current)) {
                // 理论上不可达：写入侧已拒绝环
                throw new IllegalStateException("坐标系森林中检测到环，经过: " + current);
            }
            chain.put(current, current);
            EdgeRow edge = byChild.get(current);
            current = edge == null ? null : edge.parentFrame();
        }
        return chain;
    }

    /** 取边在 stampNanos 时刻的位姿：静态全时刻有效，动态在相邻样本间插值。 */
    private Evaluated evaluate(EdgeRow edge, long stampNanos) {
        String edgeLabel = edge.parentFrame() + " -> " + edge.childFrame();
        if (edge.edgeType() == EdgeType.STATIC) {
            Transform3d pose = repository.findStaticPose(edge.id())
                    .map(SampleRow::toTransform)
                    .orElseThrow(() -> new IllegalStateException("静态边缺少位姿: edgeId=" + edge.id()));
            return new Evaluated(pose, false);
        }

        SampleRow before = repository.findSampleAtOrBefore(edge.id(), stampNanos).orElse(null);
        SampleRow after = repository.findSampleAtOrAfter(edge.id(), stampNanos).orElse(null);

        if (before == null) {
            // 没有不晚于查询时刻的保留样本
            long firstSample = edge.firstSampleNanos() == null ? stampNanos : edge.firstSampleNanos();
            if (stampNanos < firstSample) {
                // 早于历史上最早的采样
                throw new TfException.BeforeEarliestSample(edgeLabel, stampNanos, firstSample);
            }
            // 样本曾处于 [最早采样, 查询时刻] 区间但已被窗口淘汰
            long retainedFrom = after == null ? firstSample : after.stampNanos();
            throw new TfException.CacheWindowExceeded(edgeLabel, stampNanos, retainedFrom);
        }
        if (after == null) {
            throw new TfException.AfterLatestSample(edgeLabel, stampNanos, before.stampNanos());
        }
        if (after.stampNanos() == before.stampNanos()) {
            // 查询时刻恰好命中样本点，直接取样本
            return new Evaluated(after.toTransform(), false);
        }
        double alpha = (double) (stampNanos - before.stampNanos())
                / (double) (after.stampNanos() - before.stampNanos());
        Transform3d pose = Transform3d.interpolate(before.toTransform(), after.toTransform(), alpha);
        return new Evaluated(pose, true);
    }

    /**
     * 单条边在某时刻的求值结果。
     *
     * @param transform    边存储方向（parent <- child）上的位姿
     * @param interpolated 是否在两个不同时间戳样本之间发生了插值
     */
    private record Evaluated(Transform3d transform, boolean interpolated) {
    }

    /** 新边 parentFrame -&gt; childFrame 成环当且仅当 parentFrame 是 childFrame 的现有后代。 */
    private void rejectCycle(String parentFrame, String childFrame, Map<String, String> parentIndex) {
        List<String> upFromParent = new ArrayList<>();
        String current = parentFrame;
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            upFromParent.add(current);
            if (current.equals(childFrame)) {
                // 候选环：childFrame 沿现有父链下行到 parentFrame，再经新边回到 childFrame
                List<String> cycle = new ArrayList<>();
                for (int i = upFromParent.size() - 1; i >= 0; i--) {
                    cycle.add(upFromParent.get(i));
                }
                cycle.add(childFrame);
                throw new TfException.CycleDetected(cycle);
            }
            current = parentIndex.get(current);
        }
    }

    private Map<String, String> snapshotParentIndex() {
        Map<String, String> parentIndex = new HashMap<>();
        for (EdgeRow edge : repository.findAllEdges()) {
            parentIndex.put(edge.childFrame(), edge.parentFrame());
        }
        return parentIndex;
    }

    private void ensureFrame(String frame) {
        try {
            repository.insertFrameIfAbsent(frame, clock.nowNanos());
        } catch (DuplicateKeyException alreadyExists) {
            // 并发注册，幂等忽略
        }
    }

    private static SampleRow toRow(long stampNanos, String source, Transform3d t) {
        return new SampleRow(stampNanos, source,
                t.tx(), t.ty(), t.tz(), t.qx(), t.qy(), t.qz(), t.qw());
    }

    private static boolean samePose(SampleRow row, Transform3d t) {
        return row.tx() == t.tx() && row.ty() == t.ty() && row.tz() == t.tz()
                && row.qx() == t.qx() && row.qy() == t.qy()
                && row.qz() == t.qz() && row.qw() == t.qw();
    }
}
