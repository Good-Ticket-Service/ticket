package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/** 지급 운영 명령의 최종 결과(Task 20-10). 지급 상태 변경과 같은 트랜잭션에 저장한다. 작업 큐로 쓰지 않는다. */
@Getter
@Entity
@Table(name = "p_payout_operation_commands")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutOperationCommand extends BaseEntity implements Persistable<String> {

    @Id
    @Column(length = 80)
    private String commandId;

    @Column(nullable = false, length = 64)
    private String inputHash;

    @Column(nullable = false)
    private Long payoutId;

    @Column(nullable = false, length = 10)
    private String action;

    @Column(nullable = false, length = 200)
    private String actor;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(nullable = false, length = 200)
    private String note;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(nullable = false, length = 80)
    private String explanation;

    @Column(nullable = false, length = 160)
    private String expectedState;

    @Column(nullable = false, length = 160)
    private String beforeState;

    @Column(nullable = false, length = 160)
    private String afterState;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    public PayoutOperationCommand(
            String commandId,
            String inputHash,
            Long payoutId,
            String action,
            String actor,
            String reason,
            String note,
            String result,
            String explanation,
            String expectedState,
            String beforeState,
            String afterState,
            LocalDateTime processedAt) {
        this.commandId = commandId;
        this.inputHash = inputHash;
        this.payoutId = payoutId;
        this.action = action;
        this.actor = actor;
        this.reason = reason;
        this.note = note;
        this.result = result;
        this.explanation = explanation;
        this.expectedState = expectedState;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.processedAt = processedAt;
    }

    @Override
    public String getId() {
        return commandId;
    }

    /** 명령은 추가만 한다. merge 대신 INSERT로 저장해 같은 ID 경쟁을 UNIQUE 충돌로 드러낸다. */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }
}
