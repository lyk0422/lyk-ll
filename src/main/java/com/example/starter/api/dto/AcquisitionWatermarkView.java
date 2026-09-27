package com.example.starter.api.dto;

import java.util.List;

/**
 * 采集会话水位线视图。
 *
 * @param watermark    已入账连续前缀末尾序号，0=尚未入账
 * @param nextSeq      下一个可入账序号（watermark+1）
 * @param windowEnd    乱序缓冲窗口末尾（watermark+20）
 * @param bufferedSeqs 仍在缓冲中的包序号（缺号之后，不参与放行）
 */
public record AcquisitionWatermarkView(
        String sessionNo,
        int watermark,
        int nextSeq,
        int windowEnd,
        List<Integer> bufferedSeqs) {
}
