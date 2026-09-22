package com.example.starter.service;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.api.dto.DbcImportResponse;
import com.example.starter.domain.DbcVersion;
import com.example.starter.exception.ApiConflictException;
import com.example.starter.repository.DbcDefinitionRepository;
import com.example.starter.support.NanoClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * DBC 版本原子导入服务。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>导入前完成全部语义校验，非法定义直接 400，不写入任何数据；</li>
 *   <li>写入在单事务内完成，唯一约束冲突或中途异常整体回滚，不污染已有可用版本；</li>
 *   <li>同一 requestId 同参（规范化摘要相同）重放幂等，返回原版本且 replayed=true；</li>
 *   <li>同一 requestId 异参重放返回 409；版本名被其他 requestId 占用同样 409。</li>
 * </ul>
 */
@Service
public class DbcImportService {

    private final DbcDefinitionRepository definitionRepository;
    private final DefinitionValidator definitionValidator;
    private final NanoClock nanoClock;

    public DbcImportService(DbcDefinitionRepository definitionRepository,
                            DefinitionValidator definitionValidator,
                            NanoClock nanoClock) {
        this.definitionRepository = definitionRepository;
        this.definitionValidator = definitionValidator;
        this.nanoClock = nanoClock;
    }

    /**
     * 原子导入一个 DBC 版本。
     */
    @Transactional
    public DbcImportResponse importVersion(DbcImportRequest request) {
        definitionValidator.validate(request);
        String digest = CanonicalDigest.sha256(request);

        Optional<DbcVersion> byRequestId = definitionRepository.findVersionByRequestId(request.requestId());
        if (byRequestId.isPresent()) {
            DbcVersion existing = byRequestId.get();
            if (!digest.equals(existing.payloadDigest())) {
                throw new ApiConflictException("IDEMPOTENCY_CONFLICT",
                        "requestId " + request.requestId() + " 已用于不同的导入参数，拒绝异参重放");
            }
            return toResponse(existing, request, true);
        }

        if (definitionRepository.findVersionByName(request.versionName()).isPresent()) {
            throw new ApiConflictException("VERSION_EXISTS",
                    "DBC 版本名已存在: " + request.versionName());
        }

        try {
            DbcVersion created = definitionRepository.insertVersion(request, digest, nanoClock.nanoTime());
            return toResponse(created, request, false);
        } catch (DuplicateKeyException ex) {
            // 并发导入：唯一约束兜底，转换为可区分的 409，事务随异常回滚
            Optional<DbcVersion> raced = definitionRepository.findVersionByRequestId(request.requestId());
            if (raced.isPresent() && digest.equals(raced.get().payloadDigest())) {
                return toResponse(raced.get(), request, true);
            }
            throw new ApiConflictException("VERSION_EXISTS",
                    "版本名或 requestId 已被并发导入占用: " + request.versionName());
        }
    }

    private DbcImportResponse toResponse(DbcVersion version, DbcImportRequest request, boolean replayed) {
        int signalCount = request.messages().stream().mapToInt(m -> m.signals().size()).sum();
        return new DbcImportResponse(version.id(), version.name(), version.requestId(),
                replayed, request.messages().size(), signalCount);
    }
}
