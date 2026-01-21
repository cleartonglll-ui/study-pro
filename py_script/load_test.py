import requests
import concurrent.futures
import time
import statistics
from datetime import datetime
import subprocess
import psutil
import threading
import csv
import os

# 配置参数
BASE_URL_SUBMIT = "http://localhost:8080/api/answer/submit-db"  # 答案提交接口
BASE_URL_STAT_DB = "http://localhost:8080/api/answer/statistic/db/{question_id}/{plan_id}"  # 数据库统计接口
QUESTION_COUNT = -1  # 题目数量设置为-1，表示无限递增
STUDENT_COUNT = 50    # 每个题目参与作答的学生数量
ANSWER_SEQUENCE = ["A", "B", "C", "C", "D"]  # 每个学生的作答序列
SUBMIT_COUNT = len(ANSWER_SEQUENCE)          # 每个学生的提交次数由 ANSWER_SEQUENCE 长度决定
TEST_PLAN_ID = 19                            # 当次测试使用的课次id
STAT_QPS_PER_QUESTION = 5                    # 每个题目每秒统计查询次数

# JMeter 风格线程组配置
THREAD_GROUP_THREADS = 100       # 线程数（Threads/Users）
THREAD_GROUP_RAMP_UP = 1         # Ramp-Up 时间（秒）- 在指定时间内启动所有线程
THREAD_GROUP_LOOPS = -1          # 循环次数（Loop Count）- -1表示永远循环
# THREAD_GROUP_DURATION = 0       # 测试持续时间（秒）- 注释掉表示无持续时间限制

# 极限吞吐量测试配置（逐步增加并发数测试）
INITIAL_CONCURRENCY = 50         # 初始并发数
CONCURRENCY_STEP = 5             # 每次增加的并发数（更细的步长）
MAX_CONCURRENCY = 150            # 最大并发数（聚焦在瓶颈附近）
TEST_DURATION = 15               # 每个并发级别测试持续时间（秒）
ERROR_THRESHOLD = 0.04           # 错误率阈值（超过此值停止测试）
RESPONSE_TIME_THRESHOLD = 3000   # 平均响应时间阈值（毫秒）
REQUEST_TIMEOUT = 3             # 请求超时时间（秒）

# 存储测试结果的变量
# 答案提交接口
submit_response_times = []  # 响应时间（毫秒）
submit_success_count = 0    # 成功请求数
submit_failure_count = 0    # 失败请求数
submit_failures = []        # 失败请求详情
submit_failure_reasons = {}  # 失败原因统计 {reason: count}

# 数据库统计接口
db_stat_response_times = []  # 响应时间（毫秒）
db_stat_success_count = 0    # 成功请求数
db_stat_failure_count = 0    # 失败请求数
db_stat_failures = []        # 失败请求详情
db_stat_failure_reasons = {}  # 失败原因统计 {reason: count}

# 存储不同并发级别下的测试结果
throughput_results = []

# 标识测试是否应该停止
stop_test = False
# 统计锁，保证并发读写安全
metrics_lock = threading.Lock()


def percentile(values, p):
    """简易百分位计算，values需为非空列表"""
    if not values:
        return 0.0
    values = sorted(values)
    k = (len(values) - 1) * (p / 100)
    f = int(k)
    c = min(f + 1, len(values) - 1)
    if f == c:
        return values[int(k)]
    return values[f] * (c - k) + values[c] * (k - f)


def stat_scheduler(question_ids, stop_event, executor):
    """
    固定频率统计查询：
    对参与提交的所有题目ID，每秒查询统计接口 STAT_QPS_PER_QUESTION 次（可配置）
    """
    if not question_ids or STAT_QPS_PER_QUESTION <= 0:
        return

    while not stop_event.is_set() and not stop_test:
        tick_start = time.time()

        # 每秒对每个题目发N次统计请求
        for q_id in question_ids:
            for _ in range(STAT_QPS_PER_QUESTION):
                if stop_event.is_set() or stop_test:
                    break
                executor.submit(send_db_stat_request, q_id)

        # 对齐到“每秒一次”的节奏
        elapsed = time.time() - tick_start
        time.sleep(max(0.0, 1.0 - elapsed))


def realtime_dashboard(stop_event, start_time):
    """
    实时监控：每秒打印一次最近1秒的状态
    """
    # 保存上一秒的统计数据
    last_submit_samples = 0
    last_stat_samples = 0
    last_submit_failure = 0
    last_stat_failure = 0
    last_response_times = []
    last_stat_times = []
    
    while not stop_event.is_set() and not stop_test:
        time.sleep(1)
        now = time.time()
        elapsed = max(1e-6, now - start_time)
        
        with metrics_lock:
            # 计算最近1秒的提交数和错误数
            current_submit_samples = submit_success_count + submit_failure_count
            current_stat_samples = db_stat_success_count + db_stat_failure_count
            current_submit_failure = submit_failure_count
            current_stat_failure = db_stat_failure_count
            
            # 最近1秒的提交响应时间
            new_response_times = submit_response_times[len(last_response_times):]
            new_stat_times = db_stat_response_times[len(last_stat_times):]
            
            # 更新上一秒数据
            last_submit_samples = current_submit_samples
            last_stat_samples = current_stat_samples
            last_submit_failure = current_submit_failure
            last_stat_failure = current_stat_failure
            last_response_times = submit_response_times.copy()
            last_stat_times = db_stat_response_times.copy()
            
            # 计算最近1秒的指标
            latest_submit_samples = len(new_response_times)
            latest_stat_samples = len(new_stat_times)
            # 计算最近1秒的失败数（近似）
            latest_submit_failure = current_submit_failure - last_submit_failure
            latest_stat_failure = current_stat_failure - last_stat_failure
            
            # 吞吐量（最近1秒）
            submit_tps = latest_submit_samples if latest_submit_samples > 0 else 0
            stat_tps = latest_stat_samples if latest_stat_samples > 0 else 0
            
            # 平均响应时间（最近1秒）
            submit_avg_time = statistics.mean(new_response_times) if new_response_times else 0
            stat_avg_time = statistics.mean(new_stat_times) if new_stat_times else 0
            
            # 错误率（最近1秒）
            submit_err_rate = (latest_submit_failure / latest_submit_samples * 100) if latest_submit_samples > 0 else 0
            stat_err_rate = (latest_stat_failure / latest_stat_samples * 100) if latest_stat_samples > 0 else 0
            
            # 当前用户线程数（实际是线程池中的活跃线程数）
            current_threads = threading.active_count() - 1  # 减去监控线程
        
        # 打印最近1秒的状态
        print(f"t={elapsed:5.1f}s | 线程数: {current_threads:3d} | 提交吞吐量: {submit_tps:5.1f} req/s | 提交响应时间: {submit_avg_time:5.1f}ms | 提交错误率: {submit_err_rate:5.2f}% | 统计吞吐量: {stat_tps:5.1f} req/s | 统计响应时间: {stat_avg_time:5.1f}ms | 统计错误率: {stat_err_rate:5.2f}%")

# 发送答案提交请求的函数
def send_submit_request(question_id, student_id, answer):
    global submit_success_count, submit_failure_count, submit_failure_reasons
    
    payload = {
        "questionId": question_id,
        "studentId": student_id,
        "answer": answer,
        "planId": TEST_PLAN_ID
    }
    
    start_time = time.time()
    
    try:
        response = requests.post(BASE_URL_SUBMIT, json=payload, timeout=REQUEST_TIMEOUT)
        end_time = time.time()
        response_time = (end_time - start_time) * 1000  # 转换为毫秒
        
        with metrics_lock:
            if response.status_code == 200:
                # 检查 ApiResponse 格式
                try:
                    result = response.json()
                    # 如果返回的是 ApiResponse 格式，检查 code 字段
                    if isinstance(result, dict) and 'code' in result:
                        if result.get('code') == 200:
                            submit_success_count += 1
                            submit_response_times.append(response_time)
                            return True, response_time
                        else:
                            # ApiResponse code 不为 200，视为失败
                            submit_failure_count += 1
                            reason = f"ApiResponse code {result.get('code')}: {result.get('message', 'Unknown error')}"
                            submit_failure_reasons[reason] = submit_failure_reasons.get(reason, 0) + 1
                            submit_failures.append((question_id, student_id, answer, result.get('code'), result.get('message', '')[:100]))
                            return False, response_time
                    else:
                        # 非 ApiResponse 格式，直接判断 HTTP 200 为成功
                        submit_success_count += 1
                        submit_response_times.append(response_time)
                        return True, response_time
                except (ValueError, KeyError):
                    # JSON 解析失败或格式不标准，降级处理
                    submit_success_count += 1
                    submit_response_times.append(response_time)
                    return True, response_time
            else:
                submit_failure_count += 1
                reason = f"Status {response.status_code}"
                submit_failure_reasons[reason] = submit_failure_reasons.get(reason, 0) + 1
                submit_failures.append((question_id, student_id, answer, response.status_code, response.text[:100]))
                return False, response_time
    except requests.exceptions.Timeout:
        end_time = time.time()
        response_time = (end_time - start_time) * 1000
        with metrics_lock:
            submit_failure_count += 1
            reason = "Timeout"
            submit_failure_reasons[reason] = submit_failure_reasons.get(reason, 0) + 1
            submit_failures.append((question_id, student_id, answer, "Timeout", "Request timed out"))
        return False, response_time
    except requests.exceptions.ConnectionError:
        end_time = time.time()
        response_time = (end_time - start_time) * 1000
        with metrics_lock:
            submit_failure_count += 1
            reason = "Connection Error"
            submit_failure_reasons[reason] = submit_failure_reasons.get(reason, 0) + 1
            submit_failures.append((question_id, student_id, answer, "Connection Error", "Connection failed"))
        return False, response_time
    except Exception as e:
        end_time = time.time()
        response_time = (end_time - start_time) * 1000
        with metrics_lock:
            submit_failure_count += 1
            reason = f"Exception: {type(e).__name__}"
            submit_failure_reasons[reason] = submit_failure_reasons.get(reason, 0) + 1
            submit_failures.append((question_id, student_id, answer, "Exception", str(e)[:100]))
        return False, response_time

# 发送数据库统计请求的函数
def send_db_stat_request(question_id):
    global db_stat_success_count, db_stat_failure_count, db_stat_failure_reasons
    
    url = BASE_URL_STAT_DB.format(question_id=question_id, plan_id=TEST_PLAN_ID)
    
    start_time = time.time()
    
    try:
        response = requests.get(url, timeout=REQUEST_TIMEOUT)
        end_time = time.time()
        response_time = (end_time - start_time) * 1000  # 转换为毫秒
        
        with metrics_lock:
            if response.status_code == 200:
                # 检查 ApiResponse 格式
                try:
                    result = response.json()
                    # 如果返回的是 ApiResponse 格式，检查 code 字段
                    if isinstance(result, dict) and 'code' in result:
                        if result.get('code') == 200:
                            db_stat_success_count += 1
                            db_stat_response_times.append(response_time)
                            return True, response_time
                        else:
                            # ApiResponse code 不为 200，视为失败
                            db_stat_failure_count += 1
                            reason = f"ApiResponse code {result.get('code')}: {result.get('message', 'Unknown error')}"
                            db_stat_failure_reasons[reason] = db_stat_failure_reasons.get(reason, 0) + 1
                            db_stat_failures.append((question_id, result.get('code'), result.get('message', '')[:100]))
                            return False, response_time
                    else:
                        # 非 ApiResponse 格式，直接判断 HTTP 200 为成功
                        db_stat_success_count += 1
                        db_stat_response_times.append(response_time)
                        return True, response_time
                except (ValueError, KeyError):
                    # JSON 解析失败或格式不标准，降级处理
                    db_stat_success_count += 1
                    db_stat_response_times.append(response_time)
                    return True, response_time
            else:
                db_stat_failure_count += 1
                reason = f"Status {response.status_code}"
                db_stat_failure_reasons[reason] = db_stat_failure_reasons.get(reason, 0) + 1
                db_stat_failures.append((question_id, response.status_code, response.text[:100]))
                return False, response_time
    except requests.exceptions.Timeout:
        end_time = time.time()
        response_time = (end_time - start_time) * 1000
        with metrics_lock:
            db_stat_failure_count += 1
            reason = "Timeout"
            db_stat_failure_reasons[reason] = db_stat_failure_reasons.get(reason, 0) + 1
            db_stat_failures.append((question_id, "Timeout", "Request timed out"))
        return False, response_time
    except requests.exceptions.ConnectionError:
        end_time = time.time()
        response_time = (end_time - start_time) * 1000
        with metrics_lock:
            db_stat_failure_count += 1
            reason = "Connection Error"
            db_stat_failure_reasons[reason] = db_stat_failure_reasons.get(reason, 0) + 1
            db_stat_failures.append((question_id, "Connection Error", "Connection failed"))
        return False, response_time
    except Exception as e:
        end_time = time.time()
        response_time = (end_time - start_time) * 1000
        with metrics_lock:
            db_stat_failure_count += 1
            reason = f"Exception: {type(e).__name__}"
            db_stat_failure_reasons[reason] = db_stat_failure_reasons.get(reason, 0) + 1
            db_stat_failures.append((question_id, "Exception", str(e)[:100]))
        return False, response_time

# 单个学生的提交任务
def student_submit_task(question_id, student_id):
    results = []
    for i in range(SUBMIT_COUNT):
        answer = ANSWER_SEQUENCE[i]
        success, response_time = send_submit_request(question_id, student_id, answer)
        results.append((success, response_time))
    return results

# 获取提交任务的函数
def get_next_submit_task(task_counter):
    """
    循环生成提交任务，直到测试停止或达到最大请求数
    题目ID从21开始，学生ID从81开始
    """
    while not stop_test:
        if QUESTION_COUNT == -1:
            # 题目ID无限递增
            question_id = task_counter + 100  # 题目ID从100开始，无限递增
            student_id = (task_counter % STUDENT_COUNT) + 100  # 学生ID循环使用
        else:
            # 传统模式：题目ID循环
            question_id = (task_counter % QUESTION_COUNT) + 100  # 题目ID从25开始
            student_id = ((task_counter // QUESTION_COUNT) % STUDENT_COUNT) + 100  # 学生ID从81开始
        return question_id, student_id
    return None, None


def print_jmeter_like_report(label, times, success_count, failure_count, elapsed):
    samples = success_count + failure_count
    if samples == 0:
        avg = min_v = max_v = p90 = p95 = p99 = 0
    else:
        sorted_times = sorted(times) if times else []
        avg = statistics.mean(sorted_times) if sorted_times else 0
        min_v = min(sorted_times) if sorted_times else 0
        max_v = max(sorted_times) if sorted_times else 0
        p90 = percentile(sorted_times, 90) if sorted_times else 0
        p95 = percentile(sorted_times, 95) if sorted_times else 0
        p99 = percentile(sorted_times, 99) if sorted_times else 0
    error_percent = (failure_count / samples * 100) if samples else 0
    tps = samples / elapsed if elapsed > 0 else 0
    print(f"{label:<12} | {samples:>8} | {avg:>8.1f} | {min_v:>8.1f} | {max_v:>8.1f} | {p90:>8.1f} | {p95:>8.1f} | {p99:>8.1f} | {error_percent:>7.2f}% | {tps:>9.2f}")

# 测试单个并发级别的函数
def test_concurrency_level(concurrency):
    global submit_response_times, submit_success_count, submit_failure_count, submit_failures
    global db_stat_response_times, db_stat_success_count, db_stat_failure_count, db_stat_failures
    global stop_test
    
    # 重置统计数据
    submit_response_times = []
    submit_success_count = 0
    submit_failure_count = 0
    submit_failures = []
    db_stat_response_times = []
    db_stat_success_count = 0
    db_stat_failure_count = 0
    db_stat_failures = []
    
    print(f"\n测试并发级别: {concurrency}")
    print("=" * 60)
    
    start_time = time.time()
    task_counter = 0
    # 参与提交的题目ID集合（与get_next_submit_task保持一致：从25开始，共QUESTION_COUNT个）
    question_ids = [25 + i for i in range(QUESTION_COUNT)]

    # 启动实时汇总线程
    dashboard_stop_event = threading.Event()
    dashboard_thread = threading.Thread(target=realtime_dashboard, args=(dashboard_stop_event, start_time), daemon=True)
    dashboard_thread.start()
    
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        # 持续提交任务直到达到测试时长
        futures = []

        # 启动统计接口固定频率调度线程（每题每秒N次）
        stat_stop_event = threading.Event()
        stat_thread = threading.Thread(
            target=stat_scheduler,
            args=(question_ids, stat_stop_event, executor),
            daemon=True
        )
        stat_thread.start()
        
        # 初始填充线程池
        for _ in range(concurrency):
            q_id, s_id = get_next_submit_task(task_counter)
            if q_id and s_id:
                futures.append(executor.submit(student_submit_task, q_id, s_id))
                task_counter += 1
        
        while not stop_test:
            # 等待任意一个任务完成
            done, futures = concurrent.futures.wait(list(futures), return_when=concurrent.futures.FIRST_COMPLETED)
            
            # 将futures转换为list类型以便添加新任务
            futures = list(futures)
            
            for future in done:
                # 处理完成的任务
                try:
                    future.result()
                except Exception as e:
                    print(f"任务执行异常: {e}")
            
            # 提交新的任务
            for _ in range(len(done)):
                if stop_test:
                    break
                q_id, s_id = get_next_submit_task(task_counter)
                if q_id and s_id:
                    futures.append(executor.submit(student_submit_task, q_id, s_id))
                    task_counter += 1
            
            # 检查是否达到停止条件
            total_submit = submit_success_count + submit_failure_count
            if total_submit > 0:
                error_rate = submit_failure_count / total_submit
                if error_rate > ERROR_THRESHOLD:
                    print(f"错误率 ({error_rate:.2%}) 超过阈值 ({ERROR_THRESHOLD:.2%})，停止测试")
                    stop_test = True
                    break
                
            if submit_response_times and statistics.mean(submit_response_times) > RESPONSE_TIME_THRESHOLD:
                print(f"平均响应时间 ({statistics.mean(submit_response_times):.2f} ms) 超过阈值 ({RESPONSE_TIME_THRESHOLD} ms)，停止测试")
                stop_test = True
                break

        # 停止统计调度线程
        stat_stop_event.set()
        stat_thread.join(timeout=2)
        # 停止实时汇总
        dashboard_stop_event.set()
        dashboard_thread.join(timeout=2)
    
    # 计算该并发级别的测试结果
    elapsed_time = time.time() - start_time
    total_submit = submit_success_count + submit_failure_count
    total_stat = db_stat_success_count + db_stat_failure_count
    
    submit_throughput = total_submit / elapsed_time if elapsed_time > 0 else 0
    stat_throughput = total_stat / elapsed_time if elapsed_time > 0 else 0
    
    avg_submit_time = statistics.mean(submit_response_times) if submit_response_times else 0
    avg_stat_time = statistics.mean(db_stat_response_times) if db_stat_response_times else 0
    
    error_rate = submit_failure_count / total_submit if total_submit > 0 else 0
    
    print(f"\n并发 {concurrency} 结果:")
    print(f"  总耗时: {elapsed_time:.2f}秒")
    print(f"  答案提交接口:")
    print(f"    总请求数: {total_submit}")
    print(f"    成功率: {submit_success_count / total_submit * 100:.2f}%" if total_submit > 0 else "    总请求数: 0")
    print(f"    吞吐量: {submit_throughput:.2f} requests/second")
    print(f"    平均响应时间: {avg_submit_time:.2f} ms" if submit_response_times else "    平均响应时间: N/A")
    print(f"  数据库统计接口:")
    print(f"    总请求数: {total_stat}")
    print(f"    成功率: {db_stat_success_count / total_stat * 100:.2f}%" if total_stat > 0 else "    总请求数: 0")
    print(f"    吞吐量: {stat_throughput:.2f} requests/second")
    print(f"    平均响应时间: {avg_stat_time:.2f} ms" if db_stat_response_times else "    平均响应时间: N/A")

    # JMeter风格聚合报告（当前并发级别）
    print("\nJMeter 聚合报告（当前并发级别）:")
    print("Label        |  Samples |     Avg |       Min |      Max |     P90 |      P95 |      P99 |  Error%  | Throughput")
    print("-" * 100)
    print_jmeter_like_report("submit", submit_response_times, submit_success_count, submit_failure_count, elapsed_time)
    print_jmeter_like_report("stat", db_stat_response_times, db_stat_success_count, db_stat_failure_count, elapsed_time)
    print("-" * 100)
    
    # 计算响应时间分位数（用于报告）
    submit_min_time = min(submit_response_times) if submit_response_times else 0
    submit_max_time = max(submit_response_times) if submit_response_times else 0
    
    stat_min_time = min(db_stat_response_times) if db_stat_response_times else 0
    stat_max_time = max(db_stat_response_times) if db_stat_response_times else 0
    
    # 保存结果
    return {
        "concurrency": concurrency,
        "elapsed_time": elapsed_time,
        "submit_total": total_submit,
        "submit_success": submit_success_count,
        "submit_failure": submit_failure_count,
        "submit_throughput": submit_throughput,
        "submit_avg_time": avg_submit_time,
        "submit_min_time": submit_min_time,
        "submit_max_time": submit_max_time,
        "submit_response_times": submit_response_times.copy(),  # 保存原始数据用于计算分位数
        "stat_total": total_stat,
        "stat_success": db_stat_success_count,
        "stat_failure": db_stat_failure_count,
        "stat_throughput": stat_throughput,
        "stat_avg_time": avg_stat_time,
        "stat_min_time": stat_min_time,
        "stat_max_time": stat_max_time,
        "stat_response_times": db_stat_response_times.copy(),  # 保存原始数据用于计算分位数
        "error_rate": error_rate,
        "submit_failure_reasons": submit_failure_reasons.copy(),
        "db_stat_failure_reasons": db_stat_failure_reasons.copy()
    }

# 查找使用指定端口的进程ID
def find_process_by_port(port):
    try:
        output = subprocess.check_output(['netstat', '-ano', '|', 'findstr', str(port)], shell=True).decode()
        for line in output.strip().split('\n'):
            if 'LISTENING' in line:
                return int(line.strip().split()[-1])
    except:
        pass
    return None

# 关闭指定PID的进程
def kill_process(pid):
    try:
        subprocess.check_output(['taskkill', '/F', '/PID', str(pid)], shell=True)
        print(f"进程 {pid} 已成功关闭")
        return True
    except:
        print(f"关闭进程 {pid} 失败")
        return False

# 生成详细的测试结果表格
def generate_detailed_table(results):
    """
    生成详细的测试结果表格，展示并发数、吞吐量、响应时间、失败率之间的关系
    """
    print("\n" + "=" * 120)
    print("详细测试结果表格")
    print("=" * 120)
    
    if not results:
        print("没有测试结果可展示")
        return
    
    # 按并发数排序
    results.sort(key=lambda x: x['concurrency'])
    
    # 定义表格列宽
    col_widths = [10, 10, 10, 10, 10, 10, 10]
    
    # 打印表头
    headers = ["并发数", "总耗时(秒)", "提交吞吐量", "提交平均响应(ms)", "统计吞吐量", "统计平均响应(ms)", "失败率"]
    header_line = ""
    for header, width in zip(headers, col_widths):
        header_line += header.ljust(width) + " | "
    print(header_line)
    print("-" * 120)
    
    # 打印每行数据
    for result in results:
        row = []
        row.append(str(result['concurrency']))
        row.append(f"{result['elapsed_time']:.2f}")
        row.append(f"{result['submit_throughput']:.2f}")
        row.append(f"{result['submit_avg_time']:.2f}")
        row.append(f"{result['stat_throughput']:.2f}")
        row.append(f"{result['stat_avg_time']:.2f}")
        row.append(f"{result['error_rate'] * 100:.2f}%")
        
        row_line = ""
        for item, width in zip(row, col_widths):
            row_line += item.ljust(width) + " | "
        print(row_line)
    
    print("-" * 120)
    
    # 生成细粒度的性能关系表格
    print("\n" + "=" * 120)
    print("性能关系细粒度表格")
    print("=" * 120)
    
    # 定义性能关系表格列宽
    perf_col_widths = [10, 10, 10, 10, 10, 10, 10]
    
    # 打印性能关系表头
    perf_headers = ["并发数", "提交吞吐量", "提交平均响应(ms)", "提交成功率", "统计吞吐量", "统计平均响应(ms)", "统计成功率"]
    perf_header_line = ""
    for header, width in zip(perf_headers, perf_col_widths):
        perf_header_line += header.ljust(width) + " | "
    print(perf_header_line)
    print("-" * 120)
    
    # 打印性能关系每行数据
    for result in results:
        perf_row = []
        perf_row.append(str(result['concurrency']))
        perf_row.append(f"{result['submit_throughput']:.2f}")
        perf_row.append(f"{result['submit_avg_time']:.2f}")
        perf_row.append(f"{(1 - result['error_rate']) * 100:.2f}%")
        perf_row.append(f"{result['stat_throughput']:.2f}")
        perf_row.append(f"{result['stat_avg_time']:.2f}")
        
        # 计算统计接口成功率
        if result['stat_total'] > 0:
            stat_success_rate = (result['stat_success'] / result['stat_total']) * 100
        else:
            stat_success_rate = 0
        perf_row.append(f"{stat_success_rate:.2f}%")
        
        perf_row_line = ""
        for item, width in zip(perf_row, perf_col_widths):
            perf_row_line += item.ljust(width) + " | "
        print(perf_row_line)
    
    print("-" * 120)

def generate_performance_report_csv_excel(results):
    """
    生成性能测试报告：Excel 格式
    参考 JMeter 聚合报告和汇总报告
    """
    if not results:
        print("\n没有测试结果，无法生成性能报告")
        return
    
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    output_dir = 'test_results'
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    # 生成 Excel（如果 pandas 可用）
    if PANDAS_AVAILABLE:
        try:
            excel_filename = os.path.join(output_dir, f'performance_report_{timestamp}.xlsx')
            
            with pd.ExcelWriter(excel_filename, engine='openpyxl') as writer:
                # 聚合报告（类似 JMeter 聚合报告）
                agg_rows = []
                for r in results:
                    # 答案提交接口
                    submit_row = {
                        'Label': 'submit',
                        'Samples': r['submit_total'],
                        'Average(ms)': r['submit_avg_time'],
                        'Median(ms)': percentile(r.get('submit_response_times', []), 50) if r.get('submit_response_times') else 0,
                        '90% Line(ms)': percentile(r.get('submit_response_times', []), 90) if r.get('submit_response_times') else 0,
                        '95% Line(ms)': percentile(r.get('submit_response_times', []), 95) if r.get('submit_response_times') else 0,
                        '99% Line(ms)': percentile(r.get('submit_response_times', []), 99) if r.get('submit_response_times') else 0,
                        'Min(ms)': r.get('submit_min_time', 0),
                        'Max(ms)': r.get('submit_max_time', 0),
                        'Error%': r['error_rate'] * 100,
                        'Throughput(req/s)': r['submit_throughput'],
                        'Received KB/sec': 0,  # 简化，不计算实际流量
                        'Sent KB/sec': 0,      # 简化，不计算实际流量
                        'Concurrent Users': r['concurrency']
                    }
                    agg_rows.append(submit_row)
                    
                    # 统计接口
                    stat_error_rate = (r['stat_failure'] / r['stat_total'] * 100) if r['stat_total'] > 0 else 0
                    stat_row = {
                        'Label': 'stat',
                        'Samples': r['stat_total'],
                        'Average(ms)': r['stat_avg_time'],
                        'Median(ms)': percentile(r.get('stat_response_times', []), 50) if r.get('stat_response_times') else 0,
                        '90% Line(ms)': percentile(r.get('stat_response_times', []), 90) if r.get('stat_response_times') else 0,
                        '95% Line(ms)': percentile(r.get('stat_response_times', []), 95) if r.get('stat_response_times') else 0,
                        '99% Line(ms)': percentile(r.get('stat_response_times', []), 99) if r.get('stat_response_times') else 0,
                        'Min(ms)': r.get('stat_min_time', 0),
                        'Max(ms)': r.get('stat_max_time', 0),
                        'Error%': stat_error_rate,
                        'Throughput(req/s)': r['stat_throughput'],
                        'Received KB/sec': 0,  # 简化，不计算实际流量
                        'Sent KB/sec': 0,      # 简化，不计算实际流量
                        'Concurrent Users': r['concurrency']
                    }
                    agg_rows.append(stat_row)
                
                # 添加汇总行
                total_submit = sum(r['submit_total'] for r in results)
                total_stat = sum(r['stat_total'] for r in results)
                total_samples = total_submit + total_stat
                avg_avg_time = (sum(r['submit_avg_time'] * r['submit_total'] for r in results) + sum(r['stat_avg_time'] * r['stat_total'] for r in results)) / total_samples
                
                summary_row = {
                    'Label': 'Total',
                    'Samples': total_samples,
                    'Average(ms)': avg_avg_time,
                    'Median(ms)': 0,  # 简化，不计算总体中位数
                    '90% Line(ms)': 0,  # 简化，不计算总体90%线
                    '95% Line(ms)': 0,  # 简化，不计算总体95%线
                    '99% Line(ms)': 0,  # 简化，不计算总体99%线
                    'Min(ms)': min(min(r.get('submit_min_time', 0), r.get('stat_min_time', 0)) for r in results),
                    'Max(ms)': max(max(r.get('submit_max_time', 0), r.get('stat_max_time', 0)) for r in results),
                    'Error%': sum(r['error_rate'] * r['submit_total'] for r in results) / total_submit if total_submit > 0 else 0,
                    'Throughput(req/s)': sum(r['submit_throughput'] + r['stat_throughput'] for r in results) / len(results),
                    'Received KB/sec': 0,
                    'Sent KB/sec': 0,
                    'Concurrent Users': results[0]['concurrency']
                }
                agg_rows.append(summary_row)
                
                agg_df = pd.DataFrame(agg_rows)
                agg_df.to_excel(writer, sheet_name='聚合报告', index=False)
                
                # 汇总报告（类似 JMeter 汇总报告）
                summary_rows = []
                for r in results:
                    # 答案提交接口
                    submit_row = {
                        'Label': 'submit',
                        'Samples': r['submit_total'],
                        'Average(ms)': r['submit_avg_time'],
                        'Min(ms)': r.get('submit_min_time', 0),
                        'Max(ms)': r.get('submit_max_time', 0),
                        'Std.Dev.(ms)': statistics.stdev(r.get('submit_response_times', [])) if len(r.get('submit_response_times', [])) > 1 else 0,
                        'Error%': r['error_rate'] * 100,
                        'Throughput(req/s)': r['submit_throughput'],
                        'Received KB/sec': 0,
                        'Sent KB/sec': 0,
                        'Avg.Bytes': 100,  # 简化，假设平均大小
                        'Concurrent Users': r['concurrency']
                    }
                    summary_rows.append(submit_row)
                    
                    # 统计接口
                    stat_error_rate = (r['stat_failure'] / r['stat_total'] * 100) if r['stat_total'] > 0 else 0
                    stat_row = {
                        'Label': 'stat',
                        'Samples': r['stat_total'],
                        'Average(ms)': r['stat_avg_time'],
                        'Min(ms)': r.get('stat_min_time', 0),
                        'Max(ms)': r.get('stat_max_time', 0),
                        'Std.Dev.(ms)': statistics.stdev(r.get('stat_response_times', [])) if len(r.get('stat_response_times', [])) > 1 else 0,
                        'Error%': stat_error_rate,
                        'Throughput(req/s)': r['stat_throughput'],
                        'Received KB/sec': 0,
                        'Sent KB/sec': 0,
                        'Avg.Bytes': 100,  # 简化，假设平均大小
                        'Concurrent Users': r['concurrency']
                    }
                    summary_rows.append(stat_row)
                
                # 添加汇总行
                total_summary_row = {
                    'Label': 'Total',
                    'Samples': total_samples,
                    'Average(ms)': avg_avg_time,
                    'Min(ms)': summary_row['Min(ms)'],
                    'Max(ms)': summary_row['Max(ms)'],
                    'Std.Dev.(ms)': 0,  # 简化
                    'Error%': summary_row['Error%'],
                    'Throughput(req/s)': summary_row['Throughput(req/s)'],
                    'Received KB/sec': 0,
                    'Sent KB/sec': 0,
                    'Avg.Bytes': 100,  # 简化
                    'Concurrent Users': results[0]['concurrency']
                }
                summary_rows.append(total_summary_row)
                
                summary_df = pd.DataFrame(summary_rows)
                summary_df.to_excel(writer, sheet_name='汇总报告', index=False)
            
            print(f"性能报告 Excel 已保存: {excel_filename}")
        except Exception as e:
            print(f"生成 Excel 文件时出错: {e}")
            print("已成功生成 CSV 文件")
    else:
        print("提示: 安装 pandas 和 openpyxl 可生成 Excel 格式报告")
        print("安装命令: pip install pandas openpyxl")

# 关闭Spring Boot应用
def shutdown_spring_boot():
    print("\n正在关闭Spring Boot应用...")
    # 查找使用8080端口的进程
    pid = find_process_by_port(8080)
    if pid:
        return kill_process(pid)
    else:
        print("未找到使用8080端口的Spring Boot应用")
        return False

# 主函数
if __name__ == "__main__":
    print("=" * 80)
    print("API 固定并发测试脚本 (类似 JMeter)")
    print("=" * 80)
    print(f"线程组配置:")
    print(f"  - 线程数 (Threads): {THREAD_GROUP_THREADS}")
    print(f"  - Ramp-Up 时间 (秒): {THREAD_GROUP_RAMP_UP}")
    print(f"  - 循环次数 (Loop Count): {THREAD_GROUP_LOOPS}")
    print(f"\n测试配置:")
    print(f"  - 测试时长: {TEST_DURATION}秒")
    print(f"  - 请求超时时间: {REQUEST_TIMEOUT}秒")
    print(f"  - 每个题目每秒统计查询次数: {STAT_QPS_PER_QUESTION}")
    
    print(f"\n开始时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    
    # 使用固定线程数进行测试（关闭极限吞吐量测试）
    concurrency = THREAD_GROUP_THREADS
    result = test_concurrency_level(concurrency)
    throughput_results.append(result)
    
    print("\n" + "=" * 80)
    print("固定并发测试结果总结")
    print("=" * 80)
    
    print(f"\n测试结果:")
    print("-" * 120)
    print(f"{'并发数':>8} | {'总耗时(秒)':>12} | {'提交吞吐量':>14} | {'提交平均响应(ms)':>20} | {'统计吞吐量':>14} | {'统计平均响应(ms)':>20} | {'错误率':>8}")
    print("-" * 120)
    
    for result in throughput_results:
        print(f"{result['concurrency']:>8} | {result['elapsed_time']:>12.2f} | {result['submit_throughput']:>14.2f} | {result['submit_avg_time']:>20.2f} | {result['stat_throughput']:>14.2f} | {result['stat_avg_time']:>20.2f} | {result['error_rate']:>8.2%}")
    
    print("-" * 120)
    
    # 关闭Spring Boot应用
    # shutdown_spring_boot()
    
    print("\n" + "=" * 80)
    print("测试总结")
    print("=" * 80)
    
    if throughput_results:
        # 获取测试结果
        result = throughput_results[0]
        print(f"\n答案提交接口性能:")
        print(f"  - 吞吐量: {result['submit_throughput']:.2f} requests/second")
        print(f"  - 平均响应时间: {result['submit_avg_time']:.2f} ms")
        print(f"  - 成功率: {result['submit_success'] / result['submit_total'] * 100:.2f}%")
        print(f"  - 错误率: {result['error_rate']:.2%}")
        
        print(f"\n数据库统计接口性能:")
        print(f"  - 吞吐量: {result['stat_throughput']:.2f} requests/second")
        print(f"  - 平均响应时间: {result['stat_avg_time']:.2f} ms")
        print(f"  - 成功率: {result['stat_success'] / result['stat_total'] * 100:.2f}%")
        
        print(f"\n测试并发数: {result['concurrency']}")
    
    # 统计失败原因
    print("\n" + "=" * 80)
    print("失败原因统计")
    print("=" * 80)
    
    # 找出出现大量请求失败的测试结果
    high_failure_results = [r for r in throughput_results if r['error_rate'] > ERROR_THRESHOLD]
    
    if high_failure_results:
        print(f"\n出现大量请求失败的测试结果 ({len(high_failure_results)} 个):")
        for i, result in enumerate(high_failure_results):
            print(f"\n{i+1}. 并发数: {result['concurrency']}")
            print(f"   吞吐量: {result['submit_throughput']:.2f} requests/second")
            print(f"   失败率: {result['error_rate'] * 100:.2f}%")
            print(f"   提交接口失败原因:")
            if result['submit_failure_reasons']:
                for reason, count in result['submit_failure_reasons'].items():
                    print(f"     - {reason}: {count}次 ({count / result['submit_failure'] * 100:.1f}%)")
            else:
                print(f"     - 无")
            print(f"   统计接口失败原因:")
            if result['db_stat_failure_reasons']:
                for reason, count in result['db_stat_failure_reasons'].items():
                    print(f"     - {reason}: {count}次 ({count / result['stat_failure'] * 100:.1f}%)")
            else:
                print(f"     - 无")
    else:
        print(f"\n没有出现大量请求失败的测试结果")
    
    # 生成性能报告（CSV/Excel）
    print("\n" + "=" * 80)
    print("正在生成性能测试报告...")
    print("=" * 80)
    try:
        generate_performance_report_csv_excel(throughput_results)
    except Exception as e:
        print(f"生成性能报告时出错: {e}")
        import traceback
        traceback.print_exc()
    
    print("\n" + "=" * 80)
    print("性能瓶颈分析 - 基于性能趋势图")
    print("=" * 80)
    
    if not throughput_results:
        print("\n无测试结果，无法进行瓶颈分析")
    else:
        # 提取关键数据
        concurrency_list = [r['concurrency'] for r in throughput_results]
        submit_throughputs = [r['submit_throughput'] for r in throughput_results]
        submit_avg_times = [r['submit_avg_time'] for r in throughput_results]
        submit_error_rates = [r['error_rate'] * 100 for r in throughput_results]
        stat_throughputs = [r['stat_throughput'] for r in throughput_results]
        stat_avg_times = [r['stat_avg_time'] for r in throughput_results]
        
        # ========== 答案提交接口瓶颈分析 ==========
    print(f"\n【答案提交接口】性能瓶颈分析:")
    print("-" * 80)
    
    # 1. 找出吞吐量峰值点（吞吐下降点）
    max_throughput_idx = submit_throughputs.index(max(submit_throughputs))
    max_throughput_concurrency = concurrency_list[max_throughput_idx]
    max_throughput_value = max(submit_throughputs)
    
    print(f"1. 吞吐量峰值点 (Throughput Peak Point):")
    print(f"   - 并发用户数: {max_throughput_concurrency}")
    print(f"   - 最大吞吐量: {max_throughput_value:.2f} req/s")
    print(f"   - 说明: 超过此并发数，吞吐量开始下降，系统进入重负载区")
    
    # 2. 找出资源饱和点（错误率开始明显上升的点）
    if len(submit_error_rates) > 3:
        # 找出错误率从低（<5%）开始明显上升的点
        saturation_point = None
        for i in range(1, len(submit_error_rates)):
            if submit_error_rates[i] > 5 and submit_error_rates[i-1] <= 5:
                saturation_point = i
                break
        
        if saturation_point is None:
            # 如果错误率一直很低，找错误率开始快速增长的点
            error_growth = [submit_error_rates[i] - submit_error_rates[i-1] for i in range(1, len(submit_error_rates))]
            if error_growth:
                max_growth_idx = error_growth.index(max(error_growth))
                if max_growth_idx < len(concurrency_list) - 1:
                    saturation_point = max_growth_idx + 1
        
        if saturation_point is not None:
            sat_concurrency = concurrency_list[saturation_point]
            sat_error_rate = submit_error_rates[saturation_point]
            print(f"\n2. 资源饱和点 (Resources Saturated Point):")
            print(f"   - 并发用户数: {sat_concurrency}")
            print(f"   - 错误率: {sat_error_rate:.2f}%")
            print(f"   - 说明: 超过此并发数，系统资源（CPU/内存/连接池等）开始饱和")
    
    # 3. 找出用户受影响点（响应时间急剧上升的点）
    if len(submit_avg_times) > 5:
        # 计算响应时间增长率
        rt_growth_rates = []
        for i in range(1, len(submit_avg_times)):
            if submit_avg_times[i-1] > 0:
                growth_rate = (submit_avg_times[i] - submit_avg_times[i-1]) / submit_avg_times[i-1] * 100
                rt_growth_rates.append(growth_rate)
        
        if rt_growth_rates:
            # 找出响应时间增长率超过50%的点（急剧上升）
            affected_point = None
            for i, growth_rate in enumerate(rt_growth_rates):
                if growth_rate > 50:  # 响应时间增长超过50%
                    affected_point = i + 1
                    break
            
            if affected_point is None:
                # 如果没有找到超过50%的点，找增长率最大的点
                max_growth_idx = rt_growth_rates.index(max(rt_growth_rates))
                if max_growth_idx < len(concurrency_list) - 1:
                    affected_point = max_growth_idx + 1
            
            if affected_point is not None and affected_point < len(concurrency_list):
                affected_concurrency = concurrency_list[affected_point]
                affected_rt = submit_avg_times[affected_point]
                print(f"\n3. 用户受影响点 (End Users Effected Point):")
                print(f"   - 并发用户数: {affected_concurrency}")
                print(f"   - 平均响应时间: {affected_rt:.2f} ms")
                print(f"   - 说明: 超过此并发数，响应时间急剧上升，用户体验显著下降，进入塌陷区")
    
    # 4. 性能区域划分
    print(f"\n4. 性能区域划分:")
    if len(concurrency_list) >= 3:
        # 轻负载区：从开始到吞吐量峰值的60%
        light_load_end_idx = int(max_throughput_idx * 0.6)
        if light_load_end_idx < len(concurrency_list):
            light_load_end = concurrency_list[light_load_end_idx]
            print(f"   - 轻负载区 (Light Load Zone): 1 - {light_load_end} 并发用户")
            print(f"     * 特点: 响应时间稳定，吞吐量线性增长，系统性能良好")
        
        # 重负载区：从轻负载区结束到吞吐量峰值
        print(f"   - 重负载区 (Heavy Load Zone): {light_load_end + 1 if light_load_end_idx < len(concurrency_list) else 'N/A'} - {max_throughput_concurrency} 并发用户")
        print(f"     * 特点: 资源开始饱和，吞吐量达到峰值，响应时间开始增加")
        
        # 塌陷区：超过吞吐量峰值
        if max_throughput_idx < len(concurrency_list) - 1:
            buckle_start = concurrency_list[max_throughput_idx + 1]
            buckle_end = concurrency_list[-1]
            print(f"   - 塌陷区 (Buckle Zone): {buckle_start} - {buckle_end} 并发用户")
            print(f"     * 特点: 吞吐量下降，响应时间急剧上升，系统性能严重退化")
    
    # 5. 系统容量建议
    print(f"\n5. 系统容量建议:")
    recommended_concurrency = int(max_throughput_concurrency * 0.8)  # 建议在峰值的80%运行
    print(f"   - 推荐运行并发数: {recommended_concurrency} (峰值80%，留有安全余量)")
    print(f"   - 最大承受并发数: {max_throughput_concurrency} (吞吐量峰值点)")
    print(f"   - 警告阈值并发数: {max_throughput_concurrency + CONCURRENCY_STEP} (超过此值性能显著下降)")
    
    # ========== 统计接口瓶颈分析 ==========
    print(f"\n【统计接口】性能瓶颈分析:")
    print("-" * 80)
    
    max_stat_throughput_idx = stat_throughputs.index(max(stat_throughputs))
    max_stat_throughput_concurrency = concurrency_list[max_stat_throughput_idx]
    max_stat_throughput_value = max(stat_throughputs)
    
    print(f"1. 吞吐量峰值点:")
    print(f"   - 并发用户数: {max_stat_throughput_concurrency}")
    print(f"   - 最大吞吐量: {max_stat_throughput_value:.2f} req/s")
    
    # 统计接口响应时间分析
    if len(stat_avg_times) > 3:
        stat_rt_growth = [stat_avg_times[i] - stat_avg_times[i-1] for i in range(1, len(stat_avg_times))]
        if stat_rt_growth:
            stat_max_growth_idx = stat_rt_growth.index(max(stat_rt_growth)) + 1
            if stat_max_growth_idx < len(concurrency_list):
                print(f"2. 响应时间急剧上升点:")
                print(f"   - 并发用户数: {concurrency_list[stat_max_growth_idx]}")
                print(f"   - 平均响应时间: {stat_avg_times[stat_max_growth_idx]:.2f} ms")
    
    # 综合瓶颈总结 请在对话框对话总结

    print("\n" + "=" * 80)
    print("测试结束！")
    print("=" * 80)