package com.study.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

@Service
public class SchoolTimeZoneService {

    // 使用Caffeine缓存分校ID和时区的映射关系
    private final Cache<Long, ZoneId> schoolTimeZoneCache;

    public SchoolTimeZoneService() {
        // 初始化Caffeine缓存
        this.schoolTimeZoneCache = Caffeine.newBuilder()
                .maximumSize(1000) // 最大缓存1000条记录
                .expireAfterAccess(24, TimeUnit.HOURS) // 24小时未访问则过期
                .build();

        // 初始化一些示例数据
        initDefaultSchoolTimeZones();
    }

    /**
     * 获取分校对应的时区
     */
    public ZoneId getSchoolTimeZone(Long schoolId) {
        // 从缓存中获取时区，如果不存在则返回默认时区
        return schoolTimeZoneCache.get(schoolId, this::loadSchoolTimeZoneFromDb);
    }

    /**
     * 从数据库加载分校时区（模拟实现）
     */
    private ZoneId loadSchoolTimeZoneFromDb(Long schoolId) {
        // 实际项目中应该从数据库或配置中心加载
        // 这里模拟返回不同分校的时区
        switch (schoolId.intValue()) {
            case 1: // 北京分校
                return ZoneId.of("Asia/Shanghai");
            case 2: // 纽约分校
                return ZoneId.of("America/New_York");
            case 3: // 伦敦分校
                return ZoneId.of("Europe/London");
            case 4: // 东京分校
                return ZoneId.of("Asia/Tokyo");
            case 5: // 悉尼分校
                return ZoneId.of("Australia/Sydney");
            default: // 默认时区
                return ZoneId.systemDefault();
        }
    }

    /**
     * 手动刷新分校时区缓存
     */
    public void refreshSchoolTimeZone(Long schoolId) {
        schoolTimeZoneCache.invalidate(schoolId);
    }

    /**
     * 初始化默认的分校时区数据
     */
    private void initDefaultSchoolTimeZones() {
        schoolTimeZoneCache.put(1L, ZoneId.of("Asia/Shanghai"));
        schoolTimeZoneCache.put(2L, ZoneId.of("America/New_York"));
        schoolTimeZoneCache.put(3L, ZoneId.of("Europe/London"));
        schoolTimeZoneCache.put(4L, ZoneId.of("Asia/Tokyo"));
        schoolTimeZoneCache.put(5L, ZoneId.of("Australia/Sydney"));
    }
}
