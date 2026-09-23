package com.example.starter.compiler.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcCompileCacheStore} 的真实 H2（MODE=MySQL）测试：
 * 验证主键唯一约束、回读以及并发 saveIfAbsent 时只落一行且所有提交者拿到同一结果。
 */
class JdbcCompileCacheStoreH2Test {

    private DataSource dataSource;
    private JdbcCompileCacheStore store;
    private JdbcTemplate jdbcTemplate;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:cache_store_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);
        try (Connection connection = ds.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS robot_compile_cache (
                        cache_key         VARCHAR(64)  PRIMARY KEY,
                        urdf              CLOB         NOT NULL,
                        urdf_sha256       VARCHAR(64)  NOT NULL,
                        dependencies_json CLOB         NOT NULL,
                        chain_json        CLOB         NOT NULL
                    )
                    """);
            statement.execute("TRUNCATE TABLE robot_compile_cache");
        }
        this.store = new JdbcCompileCacheStore(jdbcTemplate);
        this.executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private CacheEntry entry(String keySuffix) {
        String key = String.format("%064d", keySuffix.hashCode() & 0x7fffffff);
        return new CacheEntry(key, "<robot name='" + keySuffix + "'/>", "hash-" + keySuffix, "[]", "[]");
    }

    @Test
    void savesAndFindsEntry() {
        CacheEntry entry = entry("first");

        assertThat(store.find(entry.keyHash())).isEmpty();
        store.saveIfAbsent(entry);

        Optional<CacheEntry> found = store.find(entry.keyHash());
        assertThat(found).isPresent();
        assertThat(found.get().urdf()).isEqualTo(entry.urdf());
        assertThat(found.get().urdfSha256()).isEqualTo(entry.urdfSha256());
    }

    @Test
    void concurrentSavesWithSameKeyProduceSingleRow() throws Exception {
        CacheEntry entry = entry("concurrent");
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CacheEntry>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return store.saveIfAbsent(entry);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<CacheEntry> results = new ArrayList<>();
        for (Future<CacheEntry> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).hasSize(threads);
        assertThat(results).allSatisfy(result ->
                assertThat(result.keyHash()).isEqualTo(entry.keyHash()));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM robot_compile_cache WHERE cache_key = ?",
                Integer.class, entry.keyHash());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void differentKeysCoexist() {
        CacheEntry first = store.saveIfAbsent(entry("a"));
        CacheEntry second = store.saveIfAbsent(entry("b"));

        assertThat(first.keyHash()).isNotEqualTo(second.keyHash());
        assertThat(store.find(first.keyHash())).isPresent();
        assertThat(store.find(second.keyHash())).isPresent();
        AtomicInteger rows = new AtomicInteger(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM robot_compile_cache", Integer.class));
        assertThat(rows.get()).isEqualTo(2);
    }
}
