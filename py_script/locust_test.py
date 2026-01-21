from locust import HttpUser, task, between, events
from datetime import datetime
import os
import json

# 基础URL配置
# API路径定义（使用相对路径，完整URL由StudyUser类的host属性提供）
BASE_URL_SUBMIT = "/api/answer/submit"
BASE_URL_STAT_DB = "/api/answer/statistic/{}/18"  # 课次ID固定为18

# 配置参数
TEST_PLAN_ID = 18
QUESTION_COUNT = -1  # -1表示无限递增
STUDENT_COUNT = 1000

# 答案序列
ANSWER_SEQUENCE = ['A', 'B', 'C', 'D']
SUBMIT_COUNT = len(ANSWER_SEQUENCE)
STAT_QPS_PER_QUESTION = 5

# 全局计数器
class GlobalCounter:
    def __init__(self):
        self.task_counter = 0
        self.submit_success_count = 0
        self.submit_failure_count = 0
        self.db_stat_success_count = 0
        self.db_stat_failure_count = 0
        self.submit_response_times = []
        self.db_stat_response_times = []
        self.error_rate_threshold = 0.04  # 4%错误率阈值
        self.stop_test = False
        self.recent_question_ids = []  # 保存最近提交的题目ID

# 创建全局计数器实例
global_counter = GlobalCounter()

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    # 只打印必要的配置信息，避免与web页面重复
    print("=" * 80)
    print("API Locust 压测脚本")
    print("=" * 80)
    print(f"测试开始时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 80)

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("=" * 80)
    print("测试结束总结")
    print("=" * 80)
    print(f"测试结束时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("详细结果请查看Locust Web界面或下载测试数据")
    print("=" * 80)

class StudyUser(HttpUser):
    wait_time = between(0, 1)  # 任务间等待时间
    host = "http://127.0.0.1:8080"  # 主机地址
    
    def __init__(self, parent):
        super().__init__(parent)
        self.task_counter = 0
    
    @task(1)
    def submit_answer(self):
        # 检查是否需要停止测试
        if global_counter.stop_test:
            self.environment.runner.quit()
            return
        
        # 获取下一个任务（题目ID和学生ID）
        question_id, student_id = self.get_next_submit_task()
        if not question_id or not student_id:
            return
        
        # 提交答案
        for answer in ANSWER_SEQUENCE:
            payload = {
                "questionId": question_id,
                "studentId": student_id,
                "answer": answer,
                "planId": TEST_PLAN_ID
            }
            
            with self.client.post(BASE_URL_SUBMIT, name="提交答案", json=payload, catch_response=True) as response:
                # 获取响应时间（Locust已经计算好了）
                response_time = response.request_meta.get('response_time', 0)
                
                if response.status_code == 200:
                    try:
                        result = response.json()
                        if isinstance(result, dict) and 'code' in result and result.get('code') == 200:
                            global_counter.submit_success_count += 1
                            global_counter.submit_response_times.append(response_time)
                            # 将题目ID添加到最近提交列表
                            if question_id not in global_counter.recent_question_ids:
                                global_counter.recent_question_ids.append(question_id)
                            response.success()
                        else:
                            global_counter.submit_failure_count += 1
                            response.failure(f"ApiResponse code {result.get('code')}: {result.get('message', 'Unknown error')}")
                    except (ValueError, KeyError):
                        global_counter.submit_success_count += 1
                        global_counter.submit_response_times.append(response_time)
                        # 将题目ID添加到最近提交列表
                        if question_id not in global_counter.recent_question_ids:
                            global_counter.recent_question_ids.append(question_id)
                        response.success()
                else:
                    global_counter.submit_failure_count += 1
                    response.failure(f"Status {response.status_code}")
        
        # 检查错误率是否超过阈值
        self.check_error_rate()
    
    @task(5)
    def get_statistic(self):
        # 获取一个最近提交的题目ID
        if global_counter.recent_question_ids:
            # 循环使用最近提交的题目ID
            question_id = global_counter.recent_question_ids[self.task_counter % len(global_counter.recent_question_ids)]
        else:
            # 如果还没有提交过题目，使用默认ID
            question_id = 100
        
        url = BASE_URL_STAT_DB.format(question_id)
        
        # 使用相同的name参数，忽略题目ID的差异
        with self.client.get(url, name="老师查询选项分布", catch_response=True) as response:
            if response.status_code == 200:
                try:
                    result = response.json()
                    if isinstance(result, dict) and 'code' in result:
                        if result.get('code') == 200:
                            global_counter.db_stat_success_count += 1
                            response.success()
                        else:
                            global_counter.db_stat_failure_count += 1
                            response.failure(f"ApiResponse code {result.get('code')}: {result.get('message', 'Unknown error')}")
                    else:
                        global_counter.db_stat_success_count += 1
                        response.success()
                except (ValueError, KeyError):
                    global_counter.db_stat_success_count += 1
                    response.success()
            else:
                global_counter.db_stat_failure_count += 1
                response.failure(f"Status {response.status_code}")
    
    def get_next_submit_task(self):
        """
        生成提交任务，题目ID无限递增
        """
        self.task_counter += 1
        question_id = self.task_counter + 100  # 题目ID从101开始，无限递增
        student_id = ((self.task_counter - 1) % STUDENT_COUNT) + 100  # 学生ID从100开始，循环使用
        return question_id, student_id
    
    def check_error_rate(self):
        """
        检查错误率是否超过阈值
        """
        total_submit = global_counter.submit_success_count + global_counter.submit_failure_count
        if total_submit > 0:
            error_rate = global_counter.submit_failure_count / total_submit
            if error_rate > global_counter.error_rate_threshold:
                print(f"错误率 ({error_rate:.2%}) 超过阈值 ({global_counter.error_rate_threshold:.2%})，停止测试")
                global_counter.stop_test = True
                self.environment.runner.quit()