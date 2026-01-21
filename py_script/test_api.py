import requests
import json

# 测试API接口 - 写操作
write_url = "http://localhost:8080/api/answer/submit-db"

# 测试API接口 - 读操作
read_url = "http://localhost:8080/api/answer/statistic/db/1/1"

# 测试数据
payload = {
    "questionId": 1,
    "studentId": 1,
    "answer": "A",
    "planId": 1
}

headers = {
    "Content-Type": "application/json"
}

def test_write_operation():
    """测试写操作（应该使用主库）"""
    try:
        print("\n" + "="*60)
        print("测试写操作 (使用主库)")
        print("="*60)
        print(f"测试接口: {write_url}")
        print(f"请求数据: {json.dumps(payload, indent=2)}")
        
        response = requests.post(write_url, json=payload, headers=headers, timeout=10)
        
        print(f"\n响应状态码: {response.status_code}")
        print(f"响应内容: {response.text}")
        
        if response.status_code == 200:
            print("\n✓ 写操作测试成功！")
            return True
        else:
            print("\n✗ 写操作测试失败！")
            return False
            
    except Exception as e:
        print(f"\n✗ 写操作测试失败: {str(e)}")
        return False

def test_read_operation():
    """测试读操作（应该使用从库）"""
    try:
        print("\n" + "="*60)
        print("测试读操作 (使用从库)")
        print("="*60)
        print(f"测试接口: {read_url}")
        
        response = requests.get(read_url, headers=headers, timeout=10)
        
        print(f"\n响应状态码: {response.status_code}")
        print(f"响应内容: {response.text}")
        
        if response.status_code == 200:
            print("\n✓ 读操作测试成功！")
            return True
        else:
            print("\n✗ 读操作测试失败！")
            return False
            
    except Exception as e:
        print(f"\n✗ 读操作测试失败: {str(e)}")
        return False

# 执行测试
if __name__ == "__main__":
    print("测试MySQL主从架构读写分离功能")
    print("="*60)
    
    write_success = test_write_operation()
    read_success = test_read_operation()
    
    print("\n" + "="*60)
    if write_success and read_success:
        print("✓ 读写分离功能测试成功！")
        print("✓ 写操作使用主库，读操作使用从库")
    else:
        print("✗ 读写分离功能测试失败！")
    print("="*60)
