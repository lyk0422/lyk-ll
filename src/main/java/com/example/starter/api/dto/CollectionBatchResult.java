package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量采集结果：整批要么全部受理（含缓冲），要么整批拒绝。
 *
 * @param version         受理后的会话版本（本批成功使其 +1；迟到包不变更终态会话版本）
 * @param watermarkBefore 本批处理前水位线
 * @param watermarkAfter  本批处理后水位线（只推进到连续完整前缀）
 * @param admittedSeqs    本批中随前缀新入账的序号（升序）
 * @param bufferedSeqs    本批中被缓冲的序号（升序，缺号未齐，不入账）
 * @param lateSeqs        本批中按归档后迟到登记的序号（升序）
 * @param cardStatus      处理后工序卡状态
 * @param quarantined     本批前缀入账是否触发整批隔离（第 3 次测量仍超差）
 * @param quarantineSeq   触发隔离的包序号；未隔离为 null
 * @param packets         本批全部包的证据视图
 */
public record CollectionBatchResult(
        String sessionNo,
        int version,
        int watermarkBefore,
        int watermarkAfter,
        List<Integer> admittedSeqs,
        List<Integer> bufferedSeqs,
        List<Integer> lateSeqs,
        String cardStatus,
        boolean quarantined,
        Integer quarantineSeq,
        List<CollectionPacketView> packets) {
}
