package com.sparta.ticket.user.application.dto;

/** 내 정보 수정 입력. null인 필드는 바꾸지 않는다. */
public record UpdateMyInfoCommand(String name, String phone) {}
