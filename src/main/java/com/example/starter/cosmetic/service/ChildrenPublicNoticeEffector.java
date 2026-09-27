package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.ChildrenFilingRecord;
import com.example.starter.cosmetic.domain.ChildrenFilingStatus;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.ChildrenFilingPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 儿童备案公示期满生效执行器：每条到期公示独立事务。
 *
 * <p>原子性保证：</p>
 * <ol>
 *   <li>条件 UPDATE 仅把 PUBLIC 置为 EFFECTIVE；若补正已使该修订失效，更新 0 行，
 *       本事务无效果（补正与公示期满原子且唯一）。</li>
 *   <li>生效后在同一事务内把旧有效版本置 SUPERSEDED 并切换产品有效槽，
 *       保证“变更生效与旧备案失效同事务完成”。</li>
 * </ol>
 */
@Component
public class ChildrenPublicNoticeEffector {

    private final ChildrenFilingPort filingPort;

    public ChildrenPublicNoticeEffector(ChildrenFilingPort filingPort) {
        this.filingPort = filingPort;
    }

    /**
     * 尝试让一条公示中的儿童备案修订生效。
     *
     * @return true=本次调用完成生效（含版本切换）；false=该修订已被补正失效，未生效
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean effectuate(ChildrenFilingRecord publicRecord, Instant now) {
        boolean becameEffective = filingPort.expireToEffectiveIfPublic(publicRecord.id(), now);
        if (!becameEffective) {
            return false;
        }
        Optional<ActiveSlot> slot = filingPort.findActiveSlot(publicRecord.productCode());
        if (slot.isPresent()) {
            // 实质变更新版本生效：同事务替代旧有效版本
            ActiveSlot current = slot.get();
            ChildrenFilingRecord previous = filingPort
                    .findByNoAndVersion(current.filingNo(), current.versionNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "有效槽指向的备案版本不存在: " + current.filingNo() + "#" + current.versionNo()));
            if (previous.status() != ChildrenFilingStatus.EFFECTIVE) {
                throw new IllegalStateException("版本切换时旧备案并非生效态: " + previous.status());
            }
            boolean superseded = filingPort.supersedeEffective(previous.id(), now);
            if (!superseded) {
                throw new IllegalStateException("旧有效版本替代失败，事务回滚");
            }
            filingPort.replaceSlot(publicRecord.productCode(), publicRecord.filingNo(),
                    publicRecord.versionNo(), now);
        } else {
            // 首次备案生效：占用唯一有效槽（主键冲突时整个事务回滚）
            filingPort.occupySlot(publicRecord.productCode(), publicRecord.filingNo(),
                    publicRecord.versionNo(), now);
        }
        return true;
    }
}
