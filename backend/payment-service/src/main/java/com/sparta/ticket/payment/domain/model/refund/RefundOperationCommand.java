package com.sparta.ticket.payment.domain.model.refund;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 최종 운영 명령 결과다. 변경과 같은 트랜잭션에 저장하며 PG 작업 큐로 사용하지 않는다. */
@Entity
@Table(name = "p_refund_operation_commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundOperationCommand extends BaseEntity implements org.springframework.data.domain.Persistable<String> {
    @Id
    @Column(length = 80)
    private String commandId;

    @Column(nullable = false, length = 64)
    private String inputHash;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long refundId;

    @Column(nullable = false, length = 10)
    private String action;

    @Column(nullable = false, length = 200)
    private String actor;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(nullable = false, length = 40)
    private String result;

    @Column(nullable = false, length = 80)
    private String explanation;

    @Column(nullable = false, length = 160)
    private String expectedState;

    @Column(nullable = false, length = 160)
    private String beforeState;

    @Column(nullable = false, length = 160)
    private String afterState;

    @Column(nullable = false, length = 40)
    private String inquiry;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    @Override
    public String getId() {
        return commandId;
    }

    @Override
    @jakarta.persistence.Transient
    public boolean isNew() {
        return true;
    }

    public RefundOperationCommand(
            String commandId,
            String inputHash,
            Long paymentId,
            Long refundId,
            String action,
            String actor,
            String reason,
            String result,
            String explanation,
            String expectedState,
            String beforeState,
            String afterState,
            String inquiry,
            LocalDateTime processedAt) {
        this.commandId = commandId;
        this.inputHash = inputHash;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.action = action;
        this.actor = actor;
        this.reason = reason;
        this.result = result;
        this.explanation = explanation;
        this.expectedState = expectedState;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.inquiry = inquiry;
        this.processedAt = processedAt;
    }
}
