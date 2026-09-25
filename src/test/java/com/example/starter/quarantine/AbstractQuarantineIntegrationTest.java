package com.example.starter.quarantine;

import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.DepartCmd;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import com.example.starter.quarantine.api.ApiDtos.StockCmd;
import com.example.starter.quarantine.service.IssueExecutor;
import com.example.starter.quarantine.service.QuarantineService;
import com.example.starter.quarantine.support.MutableClock;
import com.example.starter.quarantine.support.TestClockConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类：真实 H2（MySQL 兼容模式）+ 可控时钟；每个用例前清空全部业务表，
 * 测试类结束后释放内存库对象。各子类以独立库名隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractQuarantineIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected QuarantineService service;

    @Autowired
    protected IssueExecutor issueExecutor;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbc.update("DELETE FROM arrival_report");
        jdbc.update("DELETE FROM cert_batch_hold");
        jdbc.update("DELETE FROM quarantine_certificate");
        jdbc.update("DELETE FROM quarantine_declaration");
        jdbc.update("DELETE FROM lockdown_zone");
        jdbc.update("DELETE FROM farm_stock");
        jdbc.update("DELETE FROM farm_registry");
        clock.setInstant(TestClockConfig.BASE_INSTANT);
    }

    @AfterAll
    void releaseDatabase() {
        // 使用 DB_CLOSE_DELAY=-1 的命名内存库，上下文结束后显式释放。
        jdbc.execute("DROP ALL OBJECTS");
    }

    // ---------- 合成数据与流程辅助 ----------

    protected static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    protected DeclarationView declare(String requestId, String farmId, Species species,
                                      int quantity, String earTagRange) {
        return service.declare(new DeclarationCmd(requestId, farmId, "DEST-" + farmId,
                species, quantity, earTagRange));
    }

    protected void stock(String farmId, Species species, String earTagRange, int quantity) {
        service.upsertStock(new StockCmd(farmId, species, earTagRange, quantity));
    }

    protected CertificateView issue(String requestId, String declarationRequestId) {
        return issueExecutor.execute(new IssueCmd(requestId, declarationRequestId));
    }

    protected CertificateView depart(String certNo) {
        return service.depart(new DepartCmd(uniqueId("DEP"), certNo));
    }

    protected int stockQty(String farmId, Species species, String earTagRange) {
        return service.getStock(farmId, species.name(), earTagRange).sellableQty();
    }

    protected long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
