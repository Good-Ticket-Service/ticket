package com.sparta.ticket.user.application.dto;

public record SignupCommand(String email, String password, String name, String phone) {}
