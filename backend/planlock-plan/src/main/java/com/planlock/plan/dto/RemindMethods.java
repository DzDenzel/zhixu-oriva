package com.planlock.plan.dto;

import lombok.Data;

/**
 * 提醒方式（对应 plans.remind_methods JSON）：通知 / 声音 / 多端同时提醒 / 提前多次提醒。
 */
@Data
public class RemindMethods {

    private boolean notification = true;
    private boolean sound = false;
    private boolean multiDevice = false;
    private boolean repeatMultiple = false;
}
