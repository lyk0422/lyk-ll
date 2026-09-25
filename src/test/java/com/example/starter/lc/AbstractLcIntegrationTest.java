package com.example.starter.lc;

import com.example.starter.lc.dto.CreateLcRequest;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.dto.SubmissionResponse;
import com.example.starter.lc.repo.LcCreditRow;
import com.example.starter.lc.repo.LcRepository;
import com.example.starter.lc.repo.SubmissionRow;
import com.example.starter.lc.repo.SubmissionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试公共基例：每个场景前清空全部业务表（合成数据），保证用例间无顺序依赖。
 */
public abstract class AbstractLcIntegrationTest {

    protected static final String LC_NO = "LC-TEST-001";

    protected static final String APPLICANT = "ACME TRADING LTD";

    protected static final String BENEFICIARY = "SHENZHEN EXPORT CO";

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected LcRepository lcRepository;

    @Autowired
    protected SubmissionRepository submissionRepository;

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM discrepancy");
        jdbcTemplate.update("DELETE FROM submission_document");
        jdbcTemplate.update("DELETE FROM doc_submission");
        jdbcTemplate.update("DELETE FROM lc_credit");
        jdbcTemplate.update("DELETE FROM idempotent_record");
    }

    protected CreateLcRequest createLcRequest(String requestId, String lcNo, BigDecimal amount,
                                              LocalDate expiry) {
        return new CreateLcRequest(requestId, lcNo, APPLICANT, BENEFICIARY, "USD",
                amount, expiry);
    }

    protected void createDefaultLc(String requestId) {
        createDefaultLc(requestId, LC_NO, new BigDecimal("10000.00"));
    }

    protected void createDefaultLc(String requestId, String lcNo, BigDecimal amount) {
        CreateLcRequest req = createLcRequest(requestId, lcNo, amount, LocalDate.of(2027, 12, 31));
        OperationResult result = service().createLc(req);
        org.assertj.core.api.Assertions.assertThat(result).isInstanceOf(OperationResult.Fresh.class);
    }

    protected LcCreditRow lcRow(String lcNo) {
        return lcRepository.find(lcNo);
    }

    protected SubmissionRow row(String batchNo) {
        return submissionRepository.find(batchNo);
    }

    protected abstract com.example.starter.lc.service.LcService service();

    protected static SubmissionResponse asSubmission(OperationResult result) {
        org.assertj.core.api.Assertions.assertThat(result).isInstanceOf(OperationResult.Fresh.class);
        Object body = ((OperationResult.Fresh) result).body();
        org.assertj.core.api.Assertions.assertThat(body).isInstanceOf(SubmissionResponse.class);
        return (SubmissionResponse) body;
    }
}
