from locust import FastHttpUser, task, between, events
from datetime import datetime
import json
import argparse
import sys

# 基础URL配置
BASE_URL_SUBMIT = "/answer/submit-redis"

# 配置参数
QUESTION_COUNT = -1  # -1表示无限递增
STUDENT_COUNT = 50

# 配置文件路径
CONFIG_FILE = "config.json"

# 读取配置文件的函数
def read_config():
    """
    从JSON文件读取配置
    """
    import os
    config = {}
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r') as f:
                config = json.load(f)
        except (json.JSONDecodeError, IOError):
            pass
    return config

# 答案序列 - 只提交"A"
ANSWER_SEQUENCE = ['A']
SUBMIT_COUNT = 1
STAT_QPS_PER_QUESTION = 5

# 全局计数器
class GlobalCounter:
    def __init__(self):
        self.task_counter = 0
        self.submit_success_count = 0
        self.submit_failure_count = 0
        self.submit_response_times = []
        self.error_rate_threshold = 0.04  # 4%错误率阈值
        self.stop_test = False
        self.recent_question_ids = []  # 保存最近提交的题目ID
        self.data_file = "recent_question_ids.json"  # 用于在脚本间共享数据的文件
        
        # 响应时间监控配置
        self.rt_threshold = 5000  # 响应时间阈值（毫秒）
        self.rt_duration_threshold = 10  # 持续时间阈值（秒）
        self.recent_rts = []  # 保存最近的响应时间
        self.max_recent_rts = 100  # 最多保存的响应时间数量
        self.rts_check_interval = 1  # 检查间隔（秒）
        self.last_check_time = 0  # 上次检查时间
        self.high_rt_start_time = None  # 高响应时间开始时间
    
    def save_recent_question_ids(self):
        """
        将最近提交的题目ID保存到文件
        """
        try:
            with open(self.data_file, 'w') as f:
                json.dump({'question_ids': self.recent_question_ids}, f)
        except IOError:
            pass
    
    def add_response_time(self, rt):
        """
        添加响应时间到监控列表
        """
        self.recent_rts.append((datetime.now(), rt))
        # 保持列表大小在限制范围内
        if len(self.recent_rts) > self.max_recent_rts:
            self.recent_rts.pop(0)
    
    def check_response_time_threshold(self):
        """
        检查响应时间是否超过阈值并持续足够时间
        返回True表示需要停止测试，False表示继续
        """
        now = datetime.now()
        
        # 只在检查间隔到达时执行
        if (now.timestamp() - self.last_check_time) < self.rts_check_interval:
            return False
        
        self.last_check_time = now.timestamp()
        
        # 计算最近的平均响应时间
        recent_time = now.timestamp() - self.rt_duration_threshold
        recent_rts = [rt for (time, rt) in self.recent_rts if time.timestamp() > recent_time]
        
        if not recent_rts:
            self.high_rt_start_time = None
            return False
        
        avg_rt = sum(recent_rts) / len(recent_rts)
        
        if avg_rt > self.rt_threshold:
            if self.high_rt_start_time is None:
                self.high_rt_start_time = now.timestamp()
            elif (now.timestamp() - self.high_rt_start_time) >= self.rt_duration_threshold:
                print(f"响应时间超过阈值 {self.rt_threshold}ms 并持续 {self.rt_duration_threshold}s，平均响应时间: {avg_rt:.2f}ms，停止测试")
                return True
        else:
            self.high_rt_start_time = None
        
        return False

# 创建全局计数器实例
global_counter = GlobalCounter()

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    print("=" * 80)
    print("API Locust 压测脚本 - 学生提交答案接口")
    print("=" * 80)
    print(f"测试开始时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 80)

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("=" * 80)
    print("测试结束总结 - 学生提交答案接口")
    print("=" * 80)
    print(f"测试结束时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("详细结果请查看Locust Web界面或下载测试数据")
    print("=" * 80)

class StudentSubmitUser(FastHttpUser):
    wait_time = between(0, 1)  # 任务间等待时间
    host = "http://127.0.0.1:8080"  # 主机地址
    
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.task_counter = 0
    
    @task
    def submit_answer(self):
        # 检查是否需要停止测试
        if global_counter.stop_test:
            self.environment.runner.quit()
            return
        
        # 获取下一个任务（题目ID和学生ID）
        question_id, student_id = self.get_next_submit_task()
        if not question_id or not student_id:
            return
        
        # 提交答案 - 每个questionid-student只提交一次"A"
        # 读取配置文件获取planId
        config = read_config()
        plan_id = config.get("test_plan_id", 100)  # 默认值为100
        
        payload = {
            "questionId": question_id,
            "studentId": student_id,
            "answer": "A",
            "planId": plan_id
        }
        
        with self.client.post(BASE_URL_SUBMIT, name="提交答案", json=payload, catch_response=True) as response:
            # 获取响应时间
            response_time = response.request_meta.get('response_time', 0)
            # 添加到响应时间监控列表
            global_counter.add_response_time(response_time)
            
            if response.status_code == 200:
                try:
                    result = response.json()
                    if isinstance(result, dict) and 'code' in result and result.get('code') == 200:
                        global_counter.submit_success_count += 1
                        global_counter.submit_response_times.append(response_time)
                        # 将题目ID添加到最近提交列表
                        if question_id not in global_counter.recent_question_ids:
                            global_counter.recent_question_ids.append(question_id)
                            global_counter.save_recent_question_ids()  # 保存到文件
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
                        global_counter.save_recent_question_ids()  # 保存到文件
                    response.success()
            else:
                global_counter.submit_failure_count += 1
                response.failure(f"Status {response.status_code}")
        
        # 暂时禁用错误率检测
        # self.check_error_rate()
    
    def get_next_submit_task(self):
        """
        生成提交任务，题目ID和学生ID都从1开始无上限递增
        """
        self.task_counter += 1
        global_counter.task_counter += 1
        question_id = self.task_counter  # 题目ID从1开始，无限递增
        student_id = self.task_counter  # 学生ID从1开始，无限递增
        return question_id, student_id
    
    def check_error_rate(self):
        """
        检查错误率是否超过阈值
        """
        # 暂时禁用错误率检测
        # total_submit = global_counter.submit_success_count + global_counter.submit_failure_count
        # if total_submit > 0:
        #     error_rate = global_counter.submit_failure_count / total_submit
        #     if error_rate > global_counter.error_rate_threshold:
        #         print(f"错误率 ({error_rate:.2%}) 超过阈值 ({global_counter.error_rate_threshold:.2%})，停止测试")
        #         global_counter.stop_test = True
        #         self.environment.runner.quit()
        
        # 暂时禁用响应时间检测
        # if global_counter.check_response_time_threshold():
        #     global_counter.stop_test = True
        #     self.environment.runner.quit()
