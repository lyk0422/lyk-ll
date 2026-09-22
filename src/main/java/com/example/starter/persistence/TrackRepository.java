package com.example.starter.persistence;

import com.example.starter.domain.TrackSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * 轨迹当前态表数据访问（骨架，实现随后补全）。
 */
public interface TrackRepository {

    Optional<TrackSnapshot> findByUid(String trackUid);

    /** 写路径使用：对轨迹行加行锁（SELECT ... FOR UPDATE），串行化同轨迹的读-判-写。 */
    Optional<TrackSnapshot> lockByUid(String trackUid);

    /** 全部轨迹，按创建先后（自增 id）排序。 */
    List<TrackSnapshot> findAll();

    /** 同一类别下所有非终态轨迹候选。 */
    List<TrackSnapshot> findCandidates(String category);

    void insert(TrackSnapshot snapshot);

    /**
     * 按版本号条件原子更新；affected=0 表示版本冲突。
     */
    int compareAndUpdate(String trackUid, long expectedVersion, TrackSnapshot next);

    /** 确定性分配下一个轨迹序号（T-000001 起）。 */
    long nextTrackNumber();
}
