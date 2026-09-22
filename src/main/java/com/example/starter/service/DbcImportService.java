package com.example.starter.service;

import com.example.starter.dbc.ConflictException;
import com.example.starter.dbc.MessageDefinition;
import com.example.starter.repo.DbcRepository;
import com.example.starter.web.dto.ImportRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * DBC 版本原子导入服务：校验定义、维护 requestId 幂等，失败整体回滚不污染可用版本。
 *
 * <p>流程（单事务）：
 * <ol>
 *   <li>装配并校验请求定义（非法直接抛 422，事务回滚）；</li>
 *   <li>尝试以 requestId 插入幂等记录：插入成功为首次导入，继续写版本；</li>
 *   <li>插入冲突说明 requestId 已用过：同 version 同内容哈希则同参重放（成功返回），
 *       否则异参重放抛 409；</li>
 *   <li>版本名已存在（其他 requestId 导入过同名版本）抛 409。</li>
 * </ol>
 */
@Service
public class DbcImportService {

    private final DbcRepository dbcRepository;
    private final DbcDefinitionAssembler assembler;

    public DbcImportService(DbcRepository dbcRepository, DbcDefinitionAssembler assembler) {
        this.dbcRepository = dbcRepository;
        this.assembler = assembler;
    }

    /**
     * 原子导入一个 DBC 版本。
     *
     * @return 导入结果（replayed=true 表示同参重放，未重复写入）
     */
    @Transactional
    public ImportOutcome importVersion(ImportRequest request) {
        List<MessageDefinition> messages = assembler.assemble(request);
        String hash = assembler.contentHash(request, messages);

        DbcRepository.ImportRequestRow inserted =
                dbcRepository.insertRequestIfAbsent(request.requestId(), request.version(), hash);
        if (inserted == null) {
            DbcRepository.ImportRequestRow existing = dbcRepository.findRequest(request.requestId());
            if (Objects.equals(existing.version(), request.version())
                    && Objects.equals(existing.contentHash(), hash)) {
                // 同 requestId 同参重放：幂等成功，不重复写入。
                return new ImportOutcome(true, request.version(), messages.size());
            }
            throw new ConflictException(
                    "requestId " + request.requestId() + " 已用于不同的导入内容（异参重放）");
        }

        if (dbcRepository.versionExists(request.version())) {
            // 插入幂等记录成功但版本被其他请求占用：事务回滚连幂等记录一起撤销，
            // 当前可用版本不被污染；调用方修正请求后可用新 requestId 重试。
            throw new ConflictException("DBC 版本已存在且定义不同: " + request.version());
        }

        dbcRepository.saveVersion(request.version(), request.requestId(), messages);
        return new ImportOutcome(false, request.version(), messages.size());
    }

    /** 导入结果。 */
    public record ImportOutcome(boolean replayed, String version, int messageCount) {
    }
}
