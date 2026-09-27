package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.port.ChildActiveSlot;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 儿童公示期满生效执行器：每条到期公示修订独立事务。
 *
 * <ol>
 *   <li>条件 UPDATE 仅把“本编号最新修订且为 PUBLIC”置 EFFECTIVE；
 *       若补正分支已生成更新修订（旧修订 VOID）或异议/其它终态，更新 0 行，本事务无效果。</li>
 *   <li>生效后在同一事务内把旧有效版本置 SUPERSEDED 并切换产品有效槽，
 *       保证“变更生效与旧备案失效同事务完成”。</li>
 * </ol>
 */
@Component
public class ChildPublicNoticeEffector {

    private final ChildFilingPort filingPort;

    public ChildPublicNoticeEffector(ChildFilingPort filingPort) {
        this.filingPort = filingPort;
    }

    /**
     * 尝试让一条到期公示修订生效。
     *
     * @return true=本次调用完成生效（含版本切换）；false=已被补正新修订取代或竞争失败
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean effectuate(ChildFilingRecord publicRecord, Instant now) {
        boolean becameEffective =
                filingPort.expireToEffectiveIfLatestPublic(publicRecord.id(), publicRecord.filingNo(), now);
        if (!becameEffective) {
            return false;
        }
        Optional<ChildActiveSlot> slot = filingPort.findActiveSlot(publicRecord.productCode());
        if (slot.isPresent()) {
            ChildActiveSlot current = slot.get();
            ChildFilingRecord previous = filingPort.findVersion(current.filingNo(), current.versionNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "儿童有效槽指向版本不存在: " + current.filingNo() + "#" + current.versionNo()));
            if (previous.status() != ChildFilingStatus.EFFECTIVE) {
                throw new IllegalStateException("版本切换时旧儿童备案并非生效态: " + previous.status());
            }
            boolean superseded = filingPort.supersedeEffective(previous.id(), now);
            if (!superseded) {
                throw new IllegalStateException("旧有效版本替代失败，事务回滚");
            }
            filingPort.replaceSlot(publicRecord.productCode(), publicRecord.filingNo(),
                    publicRecord.versionNo(), now);
        } else {
            filingPort.occupySlot(publicRecord.productCode(), publicRecord.filingNo(),
                    publicRecord.versionNo(), now);
        }
        return true;
    }
}
