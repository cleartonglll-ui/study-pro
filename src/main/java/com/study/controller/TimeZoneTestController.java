package com.study.controller;

import com.study.context.TimeZoneContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/timezone")
public class TimeZoneTestController {

    /**
     * 测试时区上下文功能
     */
    @GetMapping("/test")
    public Map<String, Object> testTimeZone() {
        Map<String, Object> result = new HashMap<>();
        
        // 获取当前时区
        ZoneId zoneId = TimeZoneContext.getTimeZone();
        
        // 获取当地时间
        LocalDateTime localDateTime = TimeZoneContext.getLocalDateTime();
        
        // 获取系统时间（用于对比）
        LocalDateTime systemDateTime = LocalDateTime.now();
        
        result.put("currentTimeZone", zoneId.getId());
        result.put("localDateTime", localDateTime);
        result.put("systemDateTime", systemDateTime);
        result.put("message", "时区上下文测试成功");
        
        return result;
    }
}
