package com.example.starter.compiler.cache;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 基于 H2（MODE=MySQL）内存库的编译缓存存储。
 *
 * <p>只缓存校验通过的成功结果；主键即依赖感知缓存键。并发写入时首个
 * INSERT 成功，其余提交者回读已存在条目，保证同一键只对应一份结果。
 */
@Repository
public class JdbcCompileCacheStore implements CompileCacheStore {

    private static final String SELECT_SQL = """
            SELECT cache_key, urdf, urdf_sha256, dependencies_json, chain_json
            FROM robot_compile_cache WHERE cache_key = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO robot_compile_cache
                (cache_key, urdf, urdf_sha256, dependencies_json, chain_json)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCompileCacheStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CacheEntry> find(String keyHash) {
        return jdbcTemplate.query(SELECT_SQL, entryMapper(), keyHash).stream().findFirst();
    }

    @Override
    public CacheEntry saveIfAbsent(CacheEntry entry) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    entry.keyHash(),
                    entry.urdf(),
                    entry.urdfSha256(),
                    entry.dependenciesJson(),
                    entry.chainJson());
            return entry;
        } catch (DuplicateKeyException e) {
            // 并发编译时另一个线程已写入同键结果；缓存键内容寻址，回读结果与本次计算等价。
            return find(entry.keyHash())
                    .orElseThrow(() -> new IllegalStateException("缓存键并发写入后回读失败: " + entry.keyHash()));
        }
    }

    private RowMapper<CacheEntry> entryMapper() {
        return (rs, rowNum) -> new CacheEntry(
                rs.getString("cache_key"),
                rs.getString("urdf"),
                rs.getString("urdf_sha256"),
                rs.getString("dependencies_json"),
                rs.getString("chain_json"));
    }
}
