from locust import FastHttpUser, task, between, events
from datetime import datetime
import json
import os

# 基础URL配置
BASE_URL_STAT_DB = "/api/answer/statistic-db/{}/{}"  # {} 分别是题目ID和plan ID

# 配置参数
QUESTION_COUNT = -1  # -1表示无限递增
STUDENT_COUNT = 1000

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

# 全局计数器
class GlobalCounter:
    def __init__(self):
        self.task_counter = 0
        self.db_stat_success_count = 0
        self.db_stat_failure_count = 0
        self.db_stat_response_times = []
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
    
    def load_recent_question_ids(self):
        """
        从文件加载最近提交的题目ID
        """
        if os.path.exists(self.data_file):
            try:
                with open(self.data_file, 'r') as f:
                    data = json.load(f)
                    if 'question_ids' in data:
                        self.recent_question_ids = data['question_ids']
            except (json.JSONDecodeError, IOError):
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
    global_counter.load_recent_question_ids()  # 启动时加载最近提交的题目ID
    print("=" * 80)
    print("API Locust 压测脚本 - 老师查询选项分布接口")
    print("=" * 80)
    print(f"测试开始时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"当前加载的题目ID数量: {len(global_counter.recent_question_ids)}")
    print("=" * 80)

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("=" * 80)
    print("测试结束总结 - 老师查询选项分布接口")
    print("=" * 80)
    print(f"测试结束时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("详细结果请查看Locust Web界面或下载测试数据")
    print("=" * 80)

class TeacherQueryUser(FastHttpUser):
    wait_time = between(0, 1)  # 任务间等待时间
    host = "http://127.0.0.1:8080"  # 主机地址
    
    def __init__(self, parent, **kwargs):
        super().__init__(parent, **kwargs)
        self.task_counter = 0
    
    @task
    def get_statistic(self):
        # 定期加载最新的题目ID
        if self.task_counter % 5 == 0:  # 每5个请求加载一次
            global_counter.load_recent_question_ids()
        
        # 获取一个最近提交的题目ID
        if global_counter.recent_question_ids:
            # 循环使用最近提交的题目ID
            question_id = global_counter.recent_question_ids[self.task_counter % len(global_counter.recent_question_ids)]
        else:
            # 如果还没有提交过题目，使用默认ID
            question_id = 100
        
        # 读取配置文件获取planId
        config = read_config()
        plan_id = config.get("test_plan_id", 23)  # 默认值为23
        
        url = BASE_URL_STAT_DB.format(question_id, plan_id)
        
        # 使用相同的name参数，忽略题目ID的差异
        with self.client.get(url, name="老师查询选项分布", catch_response=True) as response:
            # 获取响应时间
            response_time = response.request_meta.get('response_time', 0)
            # 添加到响应时间监控列表
            global_counter.add_response_time(response_time)
            
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
        
        self.task_counter += 1
        
        # 暂时禁用响应时间检测
        # if global_counter.check_response_time_threshold():
        #     global_counter.stop_test = True
        #     self.environment.runner.quit()
