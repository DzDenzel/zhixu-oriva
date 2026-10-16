package com.planlock.migration;

/** All F37/F80 connections, including cleanup, use the same isolated test endpoint. */
final class TestDatabase {
    private TestDatabase() { }
    static String url(String schema) {
        String host = System.getenv().getOrDefault("F37_DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("F37_DB_PORT", "3306");
        if (!host.matches("[a-zA-Z0-9.-]+") || !port.matches("[0-9]{1,5}"))
            throw new IllegalArgumentException("Invalid F37 database endpoint");
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }
}
