package com.study.context;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeZoneContext {
    private static final ThreadLocal<ZoneId> TIME_ZONE_HOLDER = new ThreadLocal<>();

    /**
     * 设置时区
     */
    public static void setTimeZone(ZoneId zoneId) {
        TIME_ZONE_HOLDER.set(zoneId);
    }

    /**
     * 获取时区
     */
    public static ZoneId getTimeZone() {
        ZoneId zoneId = TIME_ZONE_HOLDER.get();
        return zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * 获取当地时间
     */
    public static LocalDateTime getLocalDateTime() {
        return LocalDateTime.now(getTimeZone());
    }

    /**
     * 获取带时区的时间
     */
    public static ZonedDateTime getZonedDateTime() {
        return ZonedDateTime.now(getTimeZone());
    }

    /**
     * 清除上下文
     */
    public static void clear() {
        TIME_ZONE_HOLDER.remove();
    }
}
