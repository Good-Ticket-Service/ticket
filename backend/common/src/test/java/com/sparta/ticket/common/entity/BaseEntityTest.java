package com.sparta.ticket.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BaseEntityTest {

    @Test
    @DisplayName("첫 논리 삭제는 삭제 시각과 삭제자를 기록한다")
    void recordsFirstDeletion() {
        BaseEntity entity = new TestEntity();

        assertThat(entity.isDeleted()).isFalse();
        assertThat(entity.getDeletedAt()).isNull();
        assertThat(entity.getDeletedBy()).isNull();

        entity.softDelete("hong01");

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
        assertThat(entity.getDeletedBy()).isEqualTo("hong01");
    }

    @Test
    @DisplayName("다른 사용자가 논리 삭제를 반복해도 최초 삭제 시각과 삭제자를 유지한다")
    void preservesFirstDeletionOnRetry() {
        BaseEntity entity = new TestEntity();
        entity.softDelete("hong01");
        LocalDateTime firstDeletedAt = entity.getDeletedAt();

        entity.softDelete("kim02");

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isEqualTo(firstDeletedAt);
        assertThat(entity.getDeletedBy()).isEqualTo("hong01");
    }

    private static class TestEntity extends BaseEntity {}
}
