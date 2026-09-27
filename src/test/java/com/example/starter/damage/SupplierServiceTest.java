package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.QualificationUpdateRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Views.SupplierDetailView;
import com.example.starter.damage.dto.Views.SupplierView;
import com.example.starter.damage.service.SupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供应商资格版本测试：版本从 1 开始、成功变更加 1、expectedVersion 乐观校验、
 * 撤销与重复撤销、参数非法 400、对象不存在 404、幂等重放与同键异参 409、结构化指纹。
 */
@SpringBootTest
class SupplierServiceTest {

    @Autowired
    private SupplierService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from risk_event");
        jdbc.update("delete from award_review");
        jdbc.update("delete from bid_opening_entry");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from quotation_line");
        jdbc.update("delete from quotation");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from supplier_qual_method");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from supplier");
        jdbc.update("delete from idempotency_record");
    }

    private SupplierCreateRequest createRequest(String requestId, String supplierNo, String name) {
        return new SupplierCreateRequest(requestId, supplierNo, name,
                List.of("WELD", "SEAL"),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    private long newSupplier(String supplierNo) {
        return service.create(createRequest("sup-" + UUID.randomUUID(), supplierNo, "供应商" + supplierNo)).id();
    }

    @Test
    void createStartsAtVersionOneAndUpdateIncrements() {
        SupplierView created = service.create(createRequest("sup-c1", "SUP001", "甲修箱厂"));
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.qualification().status()).isEqualTo("ACTIVE");
        assertThat(created.qualification().methods()).containsExactlyInAnyOrder("WELD", "SEAL");

        SupplierView updated = service.updateQualification(created.id(),
                new QualificationUpdateRequest("sup-u1", 1L, List.of("SEAL", "PATCH"),
                        LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 0, 0)));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.qualification().methods()).containsExactlyInAnyOrder("SEAL", "PATCH");

        SupplierDetailView detail = service.detail(created.id());
        assertThat(detail.qualifications()).hasSize(2);
        assertThat(detail.qualifications().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(detail.qualifications().get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void updateWithStaleExpectedVersionReturns409() {
        long supplierId = newSupplier("SUP002");

        assertThatThrownBy(() -> service.updateQualification(supplierId,
                new QualificationUpdateRequest("sup-u2", 99L, List.of("WELD"),
                        LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(service.detail(supplierId).version()).isEqualTo(1);
    }

    @Test
    void revokeMarksQualificationRevokedAndDoubleRevokeRejected() {
        long supplierId = newSupplier("SUP003");

        SupplierView revoked = service.revoke(supplierId, new QualificationRevokeRequest("sup-r1", 1L));
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.qualification().status()).isEqualTo("REVOKED");

        assertThatThrownBy(() -> service.revoke(supplierId, new QualificationRevokeRequest("sup-r2", 2L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void invalidParamsReturn400() {
        assertThatThrownBy(() -> service.create(new SupplierCreateRequest("sup-b1", "SUP004", "乙厂",
                List.of("UNKNOWN"), LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.create(new SupplierCreateRequest("sup-b2", "SUP005", "丙厂",
                List.of("WELD"), LocalDateTime.of(2027, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownSupplierReturns404() {
        assertThatThrownBy(() -> service.detail(999999))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.revoke(999999, new QualificationRevokeRequest("sup-r9", 1L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void duplicateSupplierNoReturns409() {
        newSupplier("SUP006");
        assertThatThrownBy(() -> service.create(createRequest("sup-dup", "SUP006", "重名厂")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void idempotentReplayReturnsFirstResult() {
        SupplierCreateRequest request = createRequest("sup-replay", "SUP007", "丁厂");
        SupplierView first = service.create(request);
        SupplierView replay = service.create(request);

        assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject("select count(*) from supplier where supplier_no = 'SUP007'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        service.create(createRequest("sup-conflict", "SUP008", "戊厂"));

        assertThatThrownBy(() -> service.create(createRequest("sup-conflict", "SUP009", "戊厂")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void fingerprintIsStructuredAndSeparatorSafe() {
        // 朴素分隔符拼接下 "A|B"+"C" 与 "A"+"B|C" 会碰撞；结构化指纹须区分 → 同键异参 409
        service.create(createRequest("sup-sep", "A|B", "C"));

        assertThatThrownBy(() -> service.create(createRequest("sup-sep", "A", "B|C")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void failedRequestDoesNotHoldIdempotencyKey() {
        assertThatThrownBy(() -> service.create(new SupplierCreateRequest("sup-fail", "SUP010", "己厂",
                List.of("UNKNOWN"), LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0))))
                .isInstanceOf(ApiException.class);

        // 失败不占键：同键修正参数后可正常受理
        SupplierView created = service.create(createRequest("sup-fail", "SUP010", "己厂"));
        assertThat(created.supplierNo()).isEqualTo("SUP010");
    }
}
