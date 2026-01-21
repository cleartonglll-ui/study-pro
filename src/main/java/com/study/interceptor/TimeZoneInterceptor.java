package com.study.interceptor;

import com.study.context.TimeZoneContext;
import com.study.service.SchoolTimeZoneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneId;

@Component
public class TimeZoneInterceptor implements HandlerInterceptor {

    @Autowired
    private SchoolTimeZoneService schoolTimeZoneService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头或参数中获取分校ID
        String schoolIdStr = request.getHeader("X-School-Id");
        if (schoolIdStr == null || schoolIdStr.isEmpty()) {
            schoolIdStr = request.getParameter("schoolId");
        }

        if (schoolIdStr != null && !schoolIdStr.isEmpty()) {
            try {
                Long schoolId = Long.parseLong(schoolIdStr);
                // 获取分校对应的时区
                ZoneId zoneId = schoolTimeZoneService.getSchoolTimeZone(schoolId);
                // 设置时区上下文
                TimeZoneContext.setTimeZone(zoneId);
            } catch (NumberFormatException e) {
                // 如果分校ID格式不正确，使用默认时区
                TimeZoneContext.setTimeZone(ZoneId.systemDefault());
            }
        } else {
            // 如果没有分校ID，使用默认时区
            TimeZoneContext.setTimeZone(ZoneId.systemDefault());
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清除时区上下文，避免内存泄漏
        TimeZoneContext.clear();
    }
}
