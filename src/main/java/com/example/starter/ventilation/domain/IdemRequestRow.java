package com.example.starter.ventilation.domain;

/**
 * 幂等请求记录行，对应 idem_request 表。
 *
 * @param id           主键
 * @param requestId    请求幂等键
 * @param requestType  请求类型
 * @param zoneCode     关联分区（反馈可为空）
 * @param fingerprint  业务指纹
 * @param status       首次处理结果：SUCCESS / CONFLICT
 * @param conflictInfo 冲突说明
 * @param resultJson   首次响应快照 JSON
 * @param createdAt    创建时间 epoch 毫秒
 */
public record IdemRequestRow(long id, String requestId, String requestType, String zoneCode,
                             String fingerprint, String status, String conflictInfo,
                             String resultJson, long createdAt) {
}
