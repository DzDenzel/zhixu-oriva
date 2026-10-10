package com.planlock.plan.dto;

import lombok.Data;

import java.util.List;

/**
 * 重复规则（对应 plans.repeat_rule JSON）。
 *
 * <ul>
 *   <li>daily：每天</li>
 *   <li>weekly：指定星期（weekdays，1=周一 … 7=周日）</li>
 *   <li>once：单次（执行一次后失效）</li>
 *   <li>custom：自定义日期（dates，yyyy-MM-dd）</li>
 * </ul>
 */
@Data
public class RepeatRule {

    private String type;
    private List<Integer> weekdays;
    private List<String> dates;
}
