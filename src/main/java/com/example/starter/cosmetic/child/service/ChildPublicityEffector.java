package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 儿童备案公示期满生效执行器：每条到期修订独立事务。
 *
 * <p>原子性保证：</p>
 * <ol>
 *   <li>与补正竞争：条件 UPDATE 仅把 PUBLIC 置为 EFFECTIVE；若补正分支已使修订失效，
 *       更新 0 行，本事务无效果（唯一结果）。</li>
 *   <li>变更生效与旧备案失效同事务：生效后同事务把旧生效修订置 SUPERSEDED，
 *       恰好替代一个，否则回滚。</li>
 * </ol>
 */
@Component
public class ChildPublicityEffector {

    private final ChildFilingPort childFilingPort;

    public ChildPublicityEffector(ChildFilingPort childFilingPort) {
        this.childFilingPort = childFilingPort;
    }

    /**
     * 尝试让一条公示修订生效。
     *
     * @return true=本次调用完成生效（含旧修订替代）；false=补正分支已获胜，未生效
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean effectuate(ChildRevisionRecord publicRevision, Instant now) {
        // 先取旧生效修订（变更场景），生效动作之后该修订仍须唯一
        Optional<ChildRevisionRecord> previous =
                childFilingPort.findEffectiveRevision(publicRevision.filingNo());
        boolean becameEffective = childFilingPort.effectuateIfPublic(publicRevision.id(), now);
        if (!becameEffective) {
            return false;
        }
        if (previous.isPresent()) {
            boolean superseded = childFilingPort.supersedeIfEffective(previous.get().id(), now);
            if (!superseded) {
                throw new IllegalStateException("旧生效修订替代失败，事务回滚: "
                        + publicRevision.filingNo() + "#" + previous.get().revisionNo());
            }
        }
        childFilingPort.updateFilingStatus(publicRevision.filingNo(), ChildFilingStatus.EFFECTIVE, now);
        return true;
    }
}
