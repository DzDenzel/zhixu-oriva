package com.planlock.sync.dto;

import lombok.Data;

@Data
public class SyncPlanTypeView {
    private String typeKey;
    private Long userId;
    private String name;
    private String color;
    private Long version;
    private Boolean deleted;
}
