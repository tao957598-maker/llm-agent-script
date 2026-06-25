import mysql.connector
import json
from mysql.connector import Error

def save_to_mysql(json_data, db_config, table_name="table_data"):
    """
    将JSON数据保存到MySQL数据库
    :param json_data: 要保存的JSON数据
    :param db_config: 数据库配置信息
    :param table_name: 表名
    :return: 保存是否成功
    """
    print(f"正在连接MySQL数据库: {db_config['host']}")
    
    connection = None
    cursor = None
    
    try:
        # 连接数据库
        connection = mysql.connector.connect(**db_config)
        
        if connection.is_connected():
            cursor = connection.cursor()
            
            # 检查表格是否存在，不存在则创建
            create_table_if_not_exists(cursor, table_name)
            
            # 准备数据
            data_to_insert = []
            
            # 处理JSON数据
            if isinstance(json_data, dict):
                # 如果是字典，检查是否有parsed_data字段
                if "parsed_data" in json_data:
                    if isinstance(json_data["parsed_data"], list):
                        data_to_insert = json_data["parsed_data"]
                    else:
                        data_to_insert = [json_data["parsed_data"]]
                else:
                    data_to_insert = [json_data]
            elif isinstance(json_data, list):
                data_to_insert = json_data
            
            print(f"准备插入 {len(data_to_insert)} 条数据")
            
            # 插入数据
            for item in data_to_insert:
                # 构建插入语句
                columns = []
                values = []
                
                for key, value in item.items():
                    columns.append(key)
                    values.append(str(value))
                
                if columns:
                    # 构建SQL语句
                    columns_str = ", ".join([f"`{col}` VARCHAR(255)" for col in columns])
                    placeholders = ", ".join(["%s"] * len(values))
                    
                    # 动态添加列（如果不存在）
                    add_columns_if_not_exists(cursor, table_name, columns)
                    
                    # 插入数据
                    insert_sql = f"INSERT INTO `{table_name}` ({', '.join([f'`{col}`' for col in columns])}) VALUES ({placeholders})"
                    cursor.execute(insert_sql, values)
            
            # 提交事务
            connection.commit()
            print(f"成功插入 {cursor.rowcount} 条数据")
            return True
            
    except Error as e:
        print(f"MySQL操作出错: {str(e)}")
        if connection:
            connection.rollback()
        return False
    finally:
        # 关闭连接
        if cursor:
            cursor.close()
        if connection and connection.is_connected():
            connection.close()
            print("数据库连接已关闭")

def create_table_if_not_exists(cursor, table_name):
    """
    如果表不存在则创建
    :param cursor: 数据库游标
    :param table_name: 表名
    """
    create_table_sql = f"""
    CREATE TABLE IF NOT EXISTS `{table_name}` (
        `id` INT AUTO_INCREMENT PRIMARY KEY,
        `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """
    cursor.execute(create_table_sql)
    print(f"检查/创建表 `{table_name}` 完成")

def add_columns_if_not_exists(cursor, table_name, columns):
    """
    添加不存在的列
    :param cursor: 数据库游标
    :param table_name: 表名
    :param columns: 列名列表
    """
    # 获取现有列
    cursor.execute(f"SHOW COLUMNS FROM `{table_name}`")
    existing_columns = [row[0] for row in cursor.fetchall()]
    
    # 添加新列
    for column in columns:
        if column not in existing_columns and column not in ['id', 'created_at']:
            alter_sql = f"ALTER TABLE `{table_name}` ADD COLUMN `{column}` VARCHAR(255)"
            try:
                cursor.execute(alter_sql)
                print(f"添加列 `{column}` 到表 `{table_name}`")
            except Error as e:
                print(f"添加列 `{column}` 时出错: {str(e)}")

if __name__ == "__main__":
    # 测试示例
    test_data = {
        "parsed_data": [
            {"姓名": "张三", "年龄": "25", "职业": "工程师"},
            {"姓名": "李四", "年龄": "30", "职业": "教师"}
        ]
    }
    
    db_config = {
        "host": "localhost",
        "user": "root",
        "password": "password",
        "database": "test"
    }
    
    save_to_mysql(test_data, db_config)
