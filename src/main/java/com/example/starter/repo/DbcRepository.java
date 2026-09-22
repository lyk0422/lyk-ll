package com.example.starter.repo;

import com.example.starter.dbc.MessageDefinition;

import java.util.List;

/**
 * DBC 版本定义与导入幂等记录的数据访问。
 */
public interface DbcRepository {

    /** 幂等请求记录状态。 */
    record ImportRequestRow(String requestId, String version, String contentHash, String outcome) {
    }

    /**
     * 原子写入导入请求记录；唯一键冲突时返回 null（调用方需重查判定幂等或异参冲突）。
     */
    ImportRequestRow insertRequestIfAbsent(String requestId, String version, String contentHash);

    ImportRequestRow findRequest(String requestId);

    boolean versionExists(String version);

    /**
     * 在当前事务内原子保存整个版本（版本、报文、信号、枚举）。
     */
    void saveVersion(String version, String requestId, List<MessageDefinition> messages);

    /**
     * 读取某版本全部报文定义；版本不存在返回空 Map。
     */
    java.util.Map<Long, MessageDefinition> loadVersion(String version);
}
