package com.sparta.ticket.performance.domain.performance;

/** 공연·회차의 운영 상태. 공개 상태와 별개이며, 취소는 되돌리지 않는다. */
public enum OperationStatus {
    ACTIVE,
    CANCELED
}
