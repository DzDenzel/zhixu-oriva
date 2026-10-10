package com.planlock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PlanLock Backend 入口（V0.1）。
 *
 * <p>单应用 + 业务域分包（common / account / plan / strategy / record / sync），
 * 模块只依赖 common，不互相调用。契约见 Obsidian/04-技术实现/接口设计.md。
 */
@SpringBootApplication
@MapperScan("com.planlock")
public class PlanLockApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanLockApplication.class, args);
    }
}
