package com.example.starter.repository;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.domain.DbcVersion;
import com.example.starter.domain.VersionDef;

import java.util.Optional;

/**
 * DBC 版本与信号定义存储。
 */
public interface DbcDefinitionRepository {

    /** 按版本名查询版本头。 */
    Optional<DbcVersion> findVersionByName(String versionName);

    /** 按导入 requestId 查询版本头（幂等重放判断）。 */
    Optional<DbcVersion> findVersionByRequestId(String requestId);

    /**
     * 原子写入一个完整版本（版本头、报文、信号、枚举）。
     * 调用方负责事务边界；任一定义写入失败时整体回滚。
     *
     * @return 新版本头
     */
    DbcVersion insertVersion(DbcImportRequest request, String payloadDigest, long createdAtNano);

    /** 加载版本下全部报文与信号定义，用于解码。 */
    Optional<VersionDef> loadVersionDef(String versionName);

    /**
     * 对报文定义行加行锁（SELECT ... FOR UPDATE），使同一版本 + CAN ID 的
     * 帧处理在事务内串行；不同 CAN ID 之间互不阻塞。
     */
    void lockMessageDef(long messageId);
}
