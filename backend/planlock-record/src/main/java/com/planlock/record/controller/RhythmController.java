package com.planlock.record.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.record.dto.RhythmSummary;
import com.planlock.record.service.RhythmService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class RhythmController {
    private final RhythmService service;
    @GetMapping("/rhythm")
    public ApiResponse<RhythmSummary> rhythm(@RequestParam(defaultValue = "62") int days,
                                             @RequestParam(defaultValue = "UTC") String tz) {
        return ApiResponse.ok(service.rhythm(UserContext.userId(), days, tz));
    }
}
