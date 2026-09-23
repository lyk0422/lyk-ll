package com.example.starter.signing;

import java.util.List;

import com.example.starter.signing.SigningRepository.EventRow;

/**
 * 事件哈希链：eventHash = SHA-256(previousHash || canonicalEvent)，
 * 其中 canonicalEvent = "PKG|packageId|seq|eventType|payload"，首事件的 previousHash 为 64 个 0。
 */
public final class EventChain {

    /** 创世前哈希：64 个 0。 */
    public static final String GENESIS_HASH = "0".repeat(64);

    private EventChain() {
    }

    public static String canonicalEvent(String packageId, int seq, String eventType, String payload) {
        return "PKG|" + packageId + "|" + seq + "|" + eventType + "|" + payload;
    }

    public static String hash(String prevHash, String packageId, int seq, String eventType, String payload) {
        return Hashing.sha256Hex(prevHash + canonicalEvent(packageId, seq, eventType, payload));
    }

    /**
     * 校验事件序列的哈希链完整性；任一环节被篡改（序号、类型、负载、前后哈希）均返回 false。
     */
    public static boolean verify(String packageId, List<EventRow> events) {
        String prev = GENESIS_HASH;
        int expectedSeq = 1;
        for (EventRow event : events) {
            if (event.seq() != expectedSeq || !event.prevHash().equals(prev)) {
                return false;
            }
            String recomputed = hash(prev, packageId, event.seq(), event.eventType(), event.payload());
            if (!recomputed.equals(event.eventHash())) {
                return false;
            }
            prev = event.eventHash();
            expectedSeq++;
        }
        return true;
    }
}
