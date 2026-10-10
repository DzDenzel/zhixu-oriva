package com.planlock.account.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * users 表实体（account 模块）。
 */
@Data
public class User {

    private Long id;
    private String phone;
    private String email;
    private String passwordHash;
    private String backupPasswordHash;
    private String securityQuestion;
    private String securityAnswerHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
