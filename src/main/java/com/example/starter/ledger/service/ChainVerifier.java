package com.example.starter.ledger.service;

import com.example.starter.ledger.po.LedgerEventPO;
import com.example.starter.ledger.repo.EventRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 事件哈希链验真：逐条复算哈希并校验前后链接，任何篡改或断链均返回失败。
 */
@Component
public class ChainVerifier {

    private final EventRepository eventRepository;

    public ChainVerifier(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * 校验电池整条事件链。
     *
     * @return 校验结果（是否通过、失败位置与原因）
     */
    public VerificationResult verify(String batteryCode) {
        List<LedgerEventPO> events = eventRepository.findByBattery(batteryCode);
        if (events.isEmpty()) {
            return new VerificationResult(false, -1, "电池无事件链: " + batteryCode);
        }
        String expectedPrev = HashChainUtil.GENESIS_PREV_HASH;
        long expectedSeq = 1L;
        for (LedgerEventPO event : events) {
            if (event.getSeq() != expectedSeq) {
                return new VerificationResult(false, event.getSeq(),
                        "链序号不连续: 期望 " + expectedSeq + " 实际 " + event.getSeq());
            }
            if (!expectedPrev.equals(event.getPrevHash())) {
                return new VerificationResult(false, event.getSeq(),
                        "前后哈希链接断裂: seq=" + event.getSeq());
            }
            String recomputed = HashChainUtil.eventHash(event.getPrevHash(), PayloadCodec.decode(event.getPayload()));
            if (!recomputed.equals(event.getEventHash())) {
                return new VerificationResult(false, event.getSeq(),
                        "事件内容与哈希不一致（疑似篡改）: seq=" + event.getSeq());
            }
            expectedPrev = event.getEventHash();
            expectedSeq++;
        }
        return VerificationResult.ok(events.get(events.size() - 1).getEventHash());
    }

    /**
     * 链校验结果。
     */
    public static final class VerificationResult {

        private final boolean valid;
        private final long failedSeq;
        private final String reason;
        private String headHash;

        private VerificationResult(boolean valid, long failedSeq, String reason) {
            this.valid = valid;
            this.failedSeq = failedSeq;
            this.reason = reason;
        }

        private static VerificationResult ok(String headHash) {
            VerificationResult result = new VerificationResult(true, -1, null);
            result.headHash = headHash;
            return result;
        }

        public boolean isValid() {
            return valid;
        }

        public long getFailedSeq() {
            return failedSeq;
        }

        public String getReason() {
            return reason;
        }

        public String getHeadHash() {
            return headHash;
        }
    }
}
