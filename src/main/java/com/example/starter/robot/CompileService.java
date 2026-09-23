package com.example.starter.robot;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人模型编译服务：编排展开、校验、缓存键计算与缓存复用。
 *
 * <p>缓存键为规范化载荷（入口相对路径、全部传递依赖的相对路径与内容 SHA-256、
 * 规范化有效参数）的 SHA-256；任一依赖内容或参数变化都会得到不同键。
 * 同键并发请求在进程内按键串行化并双重检查，保证复用同一缓存结果。
 * 展开或校验失败直接抛出，绝不写入成功缓存。
 */
@Service
public class CompileService {

    private final XacroExpander xacroExpander;
    private final UrdfValidator urdfValidator;
    private final RobotCacheRepository cacheRepository;
    private final RobotCompilerProperties properties;
    private final ObjectMapper canonicalMapper;
    private final ConcurrentHashMap<String, Object> compileLocks = new ConcurrentHashMap<>();

    public CompileService(XacroExpander xacroExpander,
                          UrdfValidator urdfValidator,
                          RobotCacheRepository cacheRepository,
                          RobotCompilerProperties properties) {
        this.xacroExpander = xacroExpander;
        this.urdfValidator = urdfValidator;
        this.cacheRepository = cacheRepository;
        this.properties = properties;
        this.canonicalMapper = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .build();
    }

    /** 编译入口 Xacro，失败时抛出携带 requestId 的 {@link CompileException}。 */
    public CompileResponse compile(CompileRequest request) {
        Path workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspaceRoot);
            return doCompile(workspaceRoot, request);
        } catch (CompileException e) {
            throw e.withRequestId(request.requestId());
        } catch (Exception e) {
            throw new CompileException(ErrorCode.INTERNAL_ERROR, "编译失败: " + e.getMessage())
                    .withRequestId(request.requestId());
        }
    }

    private CompileResponse doCompile(Path workspaceRoot, CompileRequest request) {
        // 第一次展开用于得到依赖清单与有效参数（包含越界、循环、未定义参数等失败判定）
        ExpansionResult expansion = xacroExpander.expand(
                workspaceRoot, request.entry(), request.parameters());
        String cacheKey = buildCacheKey(request.entry(), expansion.dependencies(), expansion.parameters());

        RobotCacheRepository.CachedEntry cached = cacheRepository.find(cacheKey);
        if (cached != null) {
            return new CompileResponse(request.requestId(), request.entry(), cached.urdf(), cached.urdfSha256(),
                    cacheKey, true, cached.dependencies(), cached.expansionChain());
        }

        Object lock = compileLocks.computeIfAbsent(cacheKey, k -> new Object());
        try {
            synchronized (lock) {
                // 双重检查：并发请求在等待锁期间可能已由其他线程完成写入
                cached = cacheRepository.find(cacheKey);
                if (cached != null) {
                    return new CompileResponse(request.requestId(), request.entry(),
                            cached.urdf(), cached.urdfSha256(), cacheKey, true,
                            cached.dependencies(), cached.expansionChain());
                }

                // 校验失败在此抛出，不会到达缓存写入
                urdfValidator.validate(expansion.urdf());
                String urdfSha256 = Digests.sha256Hex(expansion.urdf().getBytes(StandardCharsets.UTF_8));

                boolean inserted = cacheRepository.save(cacheKey, request.entry(), expansion.urdf(), urdfSha256,
                        expansion.dependencies(), expansion.expansionChain(), expansion.parameters());
                if (!inserted) {
                    // 多实例或竞态下已有同键记录：回读并复用其字节，保证同键结果唯一
                    RobotCacheRepository.CachedEntry existing = cacheRepository.find(cacheKey);
                    if (existing != null) {
                        return new CompileResponse(request.requestId(), request.entry(),
                                existing.urdf(), existing.urdfSha256(), cacheKey, true,
                                existing.dependencies(), existing.expansionChain());
                    }
                }
                return new CompileResponse(request.requestId(), request.entry(), expansion.urdf(), urdfSha256,
                        cacheKey, false, expansion.dependencies(), expansion.expansionChain());
            }
        } finally {
            compileLocks.remove(cacheKey, lock);
        }
    }

    /**
     * 构造确定性缓存键载荷：依赖按相对路径排序，参数按键排序，
     * 不含时间、临时路径等环境信息；载荷的 SHA-256 十六进制即缓存键。
     */
    String buildCacheKey(String entry, List<DependencyInfo> dependencies,
                         Map<String, String> parameters) {
        try {
            ObjectNode root = canonicalMapper.createObjectNode();
            root.put("entry", entry);

            ArrayNode dependenciesNode = root.putArray("dependencies");
            dependencies.stream()
                    .sorted(java.util.Comparator.comparing(DependencyInfo::path))
                    .forEach(dep -> dependenciesNode.addObject()
                            .put("path", dep.path())
                            .put("sha256", dep.sha256()));

            TreeMap<String, String> sortedParams = new TreeMap<>(parameters);
            root.set("parameters", canonicalMapper.valueToTree(sortedParams));

            String payload = canonicalMapper.writeValueAsString(root);
            return Digests.sha256Hex(payload);
        } catch (Exception e) {
            throw new IllegalStateException("缓存键构造失败", e);
        }
    }
}
