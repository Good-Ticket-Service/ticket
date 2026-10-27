package com.sparta.ticket.fakepg.application.service.approval;

import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;

public record ConfirmResult(PgTransaction transaction, ConfirmOutcome outcome) {}
