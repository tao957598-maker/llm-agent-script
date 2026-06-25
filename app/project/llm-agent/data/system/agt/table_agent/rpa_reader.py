from playwright.sync_api import sync_playwright
import time

def read_table_data(url, table_selector='table'):
    """
    使用RPA读取页面表格数据
    :param url: 目标网页URL
    :param table_selector: 表格选择器，默认为'table'
    :return: 表格数据列表，每个元素为一行数据的字典
    """
    print(f"正在访问网页: {url}")
    
    with sync_playwright() as p:
        # 启动浏览器
        browser = p.chromium.launch(headless=False)  # 非无头模式便于调试
        page = browser.new_page()
        
        try:
            # 访问网页
            page.goto(url, timeout=60000)
            
            # 等待页面加载完成
            time.sleep(3)
            
            # 等待表格元素加载
            page.wait_for_selector(table_selector, timeout=30000)
            
            # 获取表格数据
            table_data = []
            
            # 获取表格
            table = page.query_selector(table_selector)
            if not table:
                print("未找到表格元素")
                return table_data
            
            # 获取表头
            headers = []
            header_rows = table.query_selector_all('thead tr')
            if header_rows:
                header_cells = header_rows[0].query_selector_all('th')
                headers = [cell.inner_text().strip() for cell in header_cells]
            else:
                # 如果没有thead，尝试从第一行获取表头
                first_row = table.query_selector('tbody tr')
                if first_row:
                    header_cells = first_row.query_selector_all('th, td')
                    headers = [cell.inner_text().strip() for cell in header_cells]
            
            # 获取表格数据行
            rows = table.query_selector_all('tbody tr')
            print(f"找到 {len(rows)} 行数据")
            
            for row in rows:
                cells = row.query_selector_all('td')
                row_data = {}
                
                for i, cell in enumerate(cells):
                    if i < len(headers):
                        row_data[headers[i]] = cell.inner_text().strip()
                    else:
                        row_data[f"column_{i+1}"] = cell.inner_text().strip()
                
                if row_data:
                    table_data.append(row_data)
            
            print(f"成功读取 {len(table_data)} 行表格数据")
            return table_data
            
        except Exception as e:
            print(f"读取表格数据时出错: {str(e)}")
            return []
        finally:
            # 关闭浏览器
            browser.close()

if __name__ == "__main__":
    # 测试示例
    test_url = "https://example.com/table"
    data = read_table_data(test_url)
    print("读取的表格数据:")
    for row in data:
        print(row)
