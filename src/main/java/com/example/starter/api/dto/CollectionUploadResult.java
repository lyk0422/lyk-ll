package com.example.starter.api.dto;

/**
 * 采集包批量上传结果。
 *
 * @param received         本批收到包数
 * @param appended         实际追加包数（不含同内容重放跳过）
 * @param skippedDuplicates 同会话同序号同内容重放跳过的包数
 * @param consumed         本次入账（前缀消费）的包数
 * @param watermark        上传后的水位线
 * @param version          上传后的会话版本
 * @param cardStatus       上传后工序卡状态
 * @param late             true 表示归档后迟到登记（只记证据，不复活卡片）
 */
public record CollectionUploadResult(
        String sessionNo,
        int received,
        int appended,
        int skippedDuplicates,
        int consumed,
        int watermark,
        int version,
        String cardStatus,
        boolean late) {
}
