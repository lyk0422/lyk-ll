package com.example.starter.compiler.service;

import com.example.starter.compiler.api.CompileRequest;
import com.example.starter.compiler.api.CompileResult;
import com.example.starter.compiler.api.DependencyInfo;
import com.example.starter.compiler.cache.CacheEntry;
import com.example.starter.compiler.cache.CacheKey;
import com.example.starter.compiler.cache.CacheKeyFactory;
import com.example.starter.compiler.cache.CompileCacheStore;
import com.example.starter.compiler.config.CompilerProperties;
import com.example.starter.compiler.core.CompilerException;
import com.example.starter.compiler.core.ErrorCode;
import com.example.starter.compiler.urdf.UrdfCanonicalizer;
import com.example.starter.compiler.urdf.UrdfValidator;
import com.example.starter.compiler.xacro.ExpansionResult;
import com.example.starter.compiler.xacro.XacroExpander;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 编译服务编排：路径解析 -> 受限展开 -> 依赖感知缓存 -> 校验 -> 规范化 -> 落缓存。
 *
 * <p>缓存读取发生在展开之后（依赖摘要只有展开后才完整）；缓存命中跳过校验与规范化，
 * 因为缓存中只有曾经成功（校验通过）的结果。任何校验错误都在写缓存之前抛出。
 */
@Service
public class DefaultRobotCompilerService implements RobotCompilerService {

    private final CompilerProperties properties;
    private final XacroExpander expander;
    private final UrdfValidator validator;
    private final UrdfCanonicalizer canonicalizer;
    private final CacheKeyFactory cacheKeyFactory;
    private final CompileCacheStore cacheStore;
    private final ObjectMapper objectMapper;

    public DefaultRobotCompilerService(CompilerProperties properties,
                                       XacroExpander expander,
                                       UrdfValidator validator,
                                       UrdfCanonicalizer canonicalizer,
                                       CacheKeyFactory cacheKeyFactory,
                                       CompileCacheStore cacheStore,
                                       ObjectMapper objectMapper) {
        this.properties = properties;
        this.expander = expander;
        this.validator = validator;
        this.canonicalizer = canonicalizer;
        this.cacheKeyFactory = cacheKeyFactory;
        this.cacheStore = cacheStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompileResult compile(CompileRequest request) {
        Path workspaceRoot = Path.of(properties.workspace()).toAbsolutePath().normalize();
        Path entryFile = resolveEntry(workspaceRoot, request.entry());

        Map<String, String> parameters = normalizeParameters(request.parameters());
        ExpansionResult expansion = expander.expand(workspaceRoot, entryFile, parameters);

        String entryPath = relativize(workspaceRoot, entryFile);
        CacheKey cacheKey = cacheKeyFactory.create(entryPath, expansion.dependencies(), parameters);

        Optional<CacheEntry> cached = cacheStore.find(cacheKey.keyHash());
        if (cached.isPresent()) {
            return toResult(request.requestId(), cached.get(), true);
        }

        // 未命中：先校验展开结果，再规范化。任一步失败都不会写入成功缓存。
        validator.validate(expansion.urdf());
        byte[] canonicalBytes = canonicalizer.canonicalize(expansion.urdf());
        String canonicalUrdf = new String(canonicalBytes, StandardCharsets.UTF_8);
        String urdfSha256 = sha256(canonicalBytes);

        CacheEntry entry = new CacheEntry(
                cacheKey.keyHash(),
                canonicalUrdf,
                urdfSha256,
                toJson(expansion.dependencies()),
                toJson(expansion.expansionChain()));
        CacheEntry stored = cacheStore.saveIfAbsent(entry);
        return toResult(request.requestId(), stored, false);
    }

    /**
     * 参数表必须非 null，且不接受 null 键或 null 值（空字符串作为合法字面值）。
     */
    private Map<String, String> normalizeParameters(Map<String, String> raw) {
        if (raw == null) {
            return Map.of();
        }
        raw.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new CompilerException(ErrorCode.BAD_REQUEST, "parameters 不允许 null 或空白键值");
            }
        });
        return raw;
    }

    /**
     * 入口路径必须是相对路径且规范化后仍位于工作目录内。
     */
    private Path resolveEntry(Path workspaceRoot, String entry) {
        if (entry == null || entry.isBlank()) {
            throw new CompilerException(ErrorCode.BAD_REQUEST, "entry 不能为空");
        }
        Path relative = Path.of(entry);
        if (relative.isAbsolute()) {
            throw new CompilerException(ErrorCode.PATH_OUTSIDE_WORKSPACE, "entry 必须是相对工作目录的路径");
        }
        Path resolved = workspaceRoot.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new CompilerException(ErrorCode.PATH_OUTSIDE_WORKSPACE, "entry 超出受控工作目录: " + entry);
        }
        return resolved;
    }

    private CompileResult toResult(String requestId, CacheEntry entry, boolean cacheHit) {
        return new CompileResult(
                requestId,
                entry.urdf(),
                entry.urdfSha256(),
                fromJson(entry.dependenciesJson(), new TypeReference<List<DependencyInfo>>() { }),
                fromJson(entry.chainJson(), new TypeReference<List<String>>() { }),
                cacheHit);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存元数据序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存元数据反序列化失败", e);
        }
    }

    private String relativize(Path workspaceRoot, Path file) {
        return workspaceRoot.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
