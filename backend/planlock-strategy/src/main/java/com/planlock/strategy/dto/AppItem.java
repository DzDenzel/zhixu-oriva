package com.planlock.strategy.dto;

import lombok.Data;

/**
 * 黑/白名单应用条目。
 */
@Data
public class AppItem {

    private String name;
    private String packageName;
}
