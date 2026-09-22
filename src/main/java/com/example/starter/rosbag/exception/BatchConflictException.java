package com.example.starter.rosbag.exception;

/**
 * 同一写入批次号携带了与首次写入不同的参数。
 */
public class BatchConflictException extends RosbagException {

    public BatchConflictException(String batchNo) {
        super("批次号 " + batchNo + " 已存在但参数不一致，拒绝写入");
    }
}
