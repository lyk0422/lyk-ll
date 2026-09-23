package com.example.starter.robot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 编译缓存仓库：缓存键覆盖入口、全部传递依赖内容摘要与规范化参数。
 *
 * <p>SQL 同时兼容 H2（MODE=MySQL）与 MySQL 8；并发同键写入冲突时回读已有记录，
 * 保证重复或并发编译复用同一缓存结果。
 */
@Repository
public class RobotCacheRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RobotCacheRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 按缓存键读取命中的编译结果，未命中返回 null。 */
    public CachedEntry find(String cacheKey) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT urdf_text, urdf_sha256, entry_path, dependencies_json, "
                            + "expansion_chain_json, parameters_json "
                            + "FROM robot_compile_cache WHERE cache_key = ?",
                    (rs, n) -> new CachedEntry(
                            rs.getString("urdf_text"),
                            rs.getString("urdf_sha256"),
                            rs.getString("entry_path"),
                            readList(rs.getString("dependencies_json"), DependencyInfo.class),
                            readStringList(rs.getString("expansion_chain_json")),
                            readMap(rs.getString("parameters_json"))),
                    cacheKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 写入编译结果；同键并发写入触发唯一约束冲突时返回 false，由调用方回读复用。
     */
    public boolean save(String cacheKey, String entryRelative, String urdf, String urdfSha256,
                        List<DependencyInfo> dependencies, List<String> expansionChain,
                        Map<String, String> parameters) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO robot_compile_cache "
                            + "(cache_key, entry_path, urdf_text, urdf_sha256, dependencies_json, "
                            + "expansion_chain_json, parameters_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    cacheKey, entryRelative, urdf, urdfSha256,
                    writeJson(dependencies), writeJson(expansionChain), writeJson(parameters),
                    System.currentTimeMillis());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 删除全部缓存行，供测试场景间清理。 */
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM robot_compile_cache");
    }

    /** 统计缓存行数，供并发与失效测试断言。 */
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM robot_compile_cache", Long.class);
        return count == null ? 0 : count;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("缓存元数据序列化失败", e);
        }
    }

    private <T> List<T> readList(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            throw new IllegalStateException("缓存元数据反序列化失败", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("缓存元数据反序列化失败", e);
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            throw new IllegalStateException("缓存元数据反序列化失败", e);
        }
    }

    /** 缓存记录。 */
    public record CachedEntry(
            String urdf,
            String urdfSha256,
            String entryRelative,
            List<DependencyInfo> dependencies,
            List<String> expansionChain,
            Map<String, String> parameters) {
    }
}
