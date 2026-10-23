package com.sparta.ticket.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 모든 Entity의 공통 Audit 컬럼 6개.
 * Note: 생성자·수정자는 AuditingEntityListener가 저장 직전에 AuditorAware에게 물어 채운다. 서비스 코드에서 직접 넣지 않는다.
 * 논리 삭제된 행을 조회에서 빼려면 각 Entity에 {@code @SQLRestriction("deleted_at IS NULL")}을 붙인다.
 * 이 클래스에 붙이면 상속 Entity 전체에 적용되지 않으므로(Hibernate는 Entity 클래스의 어노테이션만 본다) Entity마다 붙인다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    public void softDelete(String username) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = LocalDateTime.now();
        this.deletedBy = username;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
