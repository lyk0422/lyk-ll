package com.example.starter.costmap.service;

import com.example.starter.costmap.model.ObstacleEvent;
import com.example.starter.costmap.repo.MapVersionRepository;
import com.example.starter.costmap.repo.ObstacleRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 更新请求头落库与序号申领。加入调用方事务（REQUIRED）：
 * 调用方已对 map_version 行加锁，CAS 仅作兜底；requestId 唯一约束保证并发重放不重复落库。
 */
@Component
public class UpdateClaimTx {

    private final MapVersionRepository mapVersionRepository;
    private final ObstacleRepository obstacleRepository;

    public UpdateClaimTx(MapVersionRepository mapVersionRepository,
                         ObstacleRepository obstacleRepository) {
        this.mapVersionRepository = mapVersionRepository;
        this.obstacleRepository = obstacleRepository;
    }

    /** 并发申领与另一在途/已存在的 requestId 冲突。 */
    public static class ClaimDuplicatedException extends RuntimeException {
    }

    /**
     * 在当前事务中先落库请求头（携带候选 seq），成功后再自增版本 seq。
     * 顺序很重要：若 requestId 唯一约束先失败，seq 尚未自增，
     * 捕获冲突重判幂等/409 时不会给失败方留下序号空洞（含跨版本使用同一 requestId 的场景）。
     *
     * @return 申领到的新 seq
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public long claim(String versionId, long expectedSeq, ObstacleEvent event, String fingerprint) {
        long candidateSeq = expectedSeq + 1;
        try {
            obstacleRepository.insertUpdate(versionId, event, fingerprint, candidateSeq);
        } catch (DuplicateKeyException e) {
            throw new ClaimDuplicatedException();
        }
        long newSeq = mapVersionRepository.compareAndIncrementSeq(versionId, expectedSeq);
        if (newSeq < 0) {
            throw new IllegalStateException("seq contention during claim");
        }
        return newSeq;
    }
}
