-- 创建数据库
CREATE DATABASE IF NOT EXISTS study_pro DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE study_pro;

-- ==================== 课堂答题模块 ====================

DROP TABLE IF EXISTS answer;
CREATE TABLE answer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL COMMENT '题目ID',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    plan_id INT NOT NULL COMMENT '课次ID',
    answer VARCHAR(1) NOT NULL COMMENT '答案(A/B/C/D)',
    is_first BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否首次作答',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_question_student_plan (question_id, student_id, plan_id),
    INDEX idx_question_id (question_id),
    INDEX idx_student_id (student_id),
    INDEX idx_plan_id (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂答题记录表';

-- ==================== 积分兑换模块 ====================

DROP TABLE IF EXISTS user_point;
CREATE TABLE user_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE COMMENT '用户ID',
    point INT NOT NULL DEFAULT 0 COMMENT '可用积分',
    frozen_point INT NOT NULL DEFAULT 0 COMMENT '冻结积分',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分表';

DROP TABLE IF EXISTS treasure_box;
CREATE TABLE treasure_box (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    box_type INT NOT NULL COMMENT '宝箱类型',
    status INT NOT NULL DEFAULT 0 COMMENT '状态(0-待确认,1-已兑换,2-已取消)',
    point_cost INT NOT NULL COMMENT '消耗积分',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宝箱兑换记录表';

-- ==================== 随机宝箱模块 ====================

DROP TABLE IF EXISTS random_treasure_box;
CREATE TABLE random_treasure_box (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    user_id BIGINT NULL COMMENT '用户ID',
    gold_amount INT NOT NULL COMMENT '金币数量',
    status INT NOT NULL DEFAULT 0 COMMENT '状态(0-待领取,1-已领取)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    receive_time DATETIME NULL COMMENT '领取时间',
    INDEX idx_activity_id (activity_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='随机宝箱记录表';

-- ==================== 初始化测试数据 ====================

-- 插入测试用户积分数据
INSERT INTO user_point (user_id, point, frozen_point) VALUES
(1001, 5000, 0),
(1002, 3000, 0),
(1003, 8000, 0),
(1004, 1500, 0),
(1005, 10000, 0)
ON DUPLICATE KEY UPDATE point = VALUES(point);

-- 查询所有表
SHOW TABLES;
