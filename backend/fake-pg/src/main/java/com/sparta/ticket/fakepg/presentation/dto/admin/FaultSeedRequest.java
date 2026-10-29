package com.sparta.ticket.fakepg.presentation.dto.admin;

/** 장애 확률 판단 난수의 seed. null이면 실행마다 다른 난수다. */
public record FaultSeedRequest(Long seed) {}
