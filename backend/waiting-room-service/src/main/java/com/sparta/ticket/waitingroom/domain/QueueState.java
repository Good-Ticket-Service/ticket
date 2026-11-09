package com.sparta.ticket.waitingroom.domain;

/** 대기열에서 회원이 있는 곳. 줄에 서 있거나({@link QueuePosition}) 입장해 있거나({@link QueueAdmission}) 둘 중 하나다. */
public sealed interface QueueState permits QueuePosition, QueueAdmission {}
