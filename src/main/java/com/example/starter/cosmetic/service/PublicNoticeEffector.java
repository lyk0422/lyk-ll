package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.FilingPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 公示期满生效执行器：每条到期公示独立事务。
 *
 * <p>原子性保证（与异议受理竞争）：</p>
 * <ol>
 *   <li>条件 UPDATE 仅把 PUBLIC 置为 EFFECTIVE；若异议分支已获胜，更新 0 行，本事务无效果。</li>
 *   <li>生效后在同一事务内把旧有效版本置 SUPERSEDED 并切换产品有效槽，
 *       保证“变更生效与旧备案失效同事务完成”。</li>
 * </ol>
 */
@Component
public class PublicNoticeEffector {

    private final FilingPort filingPort;

    public PublicNoticeEffector(FilingPort filingPort) {
        this.filingPort = filingPort;
    }

    /**
     * 尝试让一条公示备案生效。
     *
     * @return true=本次调用完成生效（含版本切换）；false=公示已被异议分支夺走，未生效
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean effectuate(FilingRecord publicRecord, Instant now) {
        boolean becameEffective = filingPort.expireToEffectiveIfPublic(publicRecord.id(), now);
        if (!becameEffective) {
            return false;
        }
        Optional<ActiveSlot> slot = filingPort.findActiveSlot(publicRecord.productCode());
        if (slot.isPresent()) {
            // 实质变更新版本生效：同事务替代旧有效版本
            ActiveSlot current = slot.get();
            FilingRecord previous = filingPort.findVersion(current.filingNo(), current.versionNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "有效槽指向的备案版本不存在: " + current.filingNo() + "#" + current.versionNo()));
            if (previous.status() != FilingStatus.EFFECTIVE) {
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
