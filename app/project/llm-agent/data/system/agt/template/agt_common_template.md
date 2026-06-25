```/*
{
"title": "Agent 标题",
"description": "Agent 描述",
"parameter": [
 {
      "name":"参数名",
      "label":"参数描述",
      "type":"类型（只支持：select、textarea、file，默认值textarea）",
      "length":"50（最大长度，默认50）",
      "required":"true（是否必填，默认false）"
   }

 ],
"llm_type": "模型类型（默认空字符串）",
"life": "生命周期（默认空字符串）"
"uiEntrance": "入口界面（默认空字符串）",
"tag":"标签（默认空字符串）"
}
*/

// 定义提示词，大模型调用
define('system_prompt', '''
   角色定义：你是一个智能生成Agent 模板助手，擅长从自然语言对话中根据现有的XXX执行XXX。 请遵循以下规则:
   核心能力：
      a) ...
      b)XXX生成逻辑:
         1.XXX
         2.XXX
   示例交互：
      用户输入：生成一个Agent模板用户指令： "生成config_template.txt，包含server配置和数据库连接池设置，保存到D:/agt/"
   模板输出模板(markdown/Json/数组等格式)：
     // todo 编写数据格式示例
''')

// 定义变量（n_开头）
assign('n_var1','1')
// 定义变量（n_开头）-json字符串或Map
assign('n_var2','{"a":1,"b":"123"}')
// 定义变量（n_开头）-fn.get方法获取json或map里的属性a,然后赋值给n_var2_a
assign('n_var2_a','{{fn.get("n_var2","a")}}')
// 定义变量（n_开头）-fn.str方法获取json或map里的属性b,然后赋值给n_var2_b
assign('n_var2_b', '{{fn.str(n_var2, "b")}}')
// 定义变量（n_开头）-数组（这里是json数组示例）
assign('n_list1','[{"al":1,"bl":"123"},{"al":1,"bl":"123"}]')
// list 下标获取对应内容 start 0  
assign('n_list1_0','{{n_list1[0]}}')
// 捕捉失败定义运行失败
on_failure({
    assign("n_result", "fail")
    // 等待3秒 定义
    //act_flow.wait?second=3
})

// 日志打印
log(' n_var1 is: {{n_var1!}}')

// when 判断
when('n_var1=="1"', {
    // it is true,then todo ...
}, {
     // else todo ...
})
// if else 写法 
assign("n_flag", 'False')
if ('n_flag==true') {
    // todo
}

// foreach 循环。第二个参数可传条件，也可传数组比如'[1..9]'，也可以是json对象的变量，比如n_list1
foreach('n_plan_step', 'n_plan_step.index < 3', {
 // callback tpl
 tpl_XXX

 // when 判断，等同于if else。可删除else部分代码，就等同于只有if
   when('n_plan_step.index=="1"', {
        // it is true,then todo ...
   }, {
         // else todo ...
   })

	// 下标对应内容
	assign('test_item', '{{n_plan_step.item}}')
})

/ 
	
// Memory Retrieval
n_result,rag_memory.retrieve?var=kbData

// 单行注释且一级Plan时，使用//#；二级Plan时，使用//##，目前只支持到二级. 一级二级会在页面展示出来
//## call kbs。 psr_XXX支持psr_all、psr_json、psr_ref、psr_codeblock、psr_code、psr_sql。 rag_XXX可以改为act_XXX
rag_XXX.{{}}}?data='{{n_result!}}',psr_XXX
//act_XXX.{{}}}?data='{{n_result!}}',psr_XXX
// psr_ref：RAG 场景，将输出中的 ref（<div id="a" type="1" num="n"/>格式）标签转为 UI 引用块,且内容有www.baidu.com这种url会输出超链接标签
tpl_rag_answer, psr_ref?urlLinkEnabled=true
// groovy script: new List
n_data,act_func?script=''' 
    // ------- map使用方法 ---- //
    // [:] 是 Groovy 中创建空 Map 的语法糖
    def map = [:]
    // 方式1：使用方括号，键值对用冒号分隔
    def map1 = [name: "张三", age: 30]
    
    //  如果 meta_data_map 为 null → 整个表达式为 null ->如果 meta_data_map 存在但没有 title 返回 null -> 如果上一步为 null → 用空字符串代替
    def title = meta_data_map?.title ?: ""
 

    // 方式2：使用字符串键
    def map2 = ["name": "张三", "age": 30]
    
    // 修改 name 属性
    map1.name = "李四"
    // 新增 address 属性
    map1.address = "北京市"
    
    // 方式3：Java 风格
    def map3 = new HashMap()
    map3.put("name", "张三")
    map3.put("age", 30)
    
    // 创建包含 map1 和 map2 所有内容的新 Map
    def map2Copy = [:] + map1 + map2  
    // 复制 map1 的内容到 map3Copy
    def map3Copy = [:]
    map3Copy.putAll(map1) 
    
    // ------- list使用方法 ---- //
    // 定义空list
    def v = []
    v << "元素1"   // 使用左移操作符添加元素
    v.add("元素2") // 使用 add 方法添加元素
    v.each { item -> println item }  // 遍历列表

    // 创建两个列表
    def list1 = [1, 2, 3]
    def list2 = [4, 5, 6]
    
    // 方式1：直接调用 addAll 方法
    list1.addAll(list2)
    println list1  // 输出: [1, 2, 3, 4, 5, 6]
    
    // 方式2：使用加号操作符（创建新列表）
    def list3 = [1, 2, 3]
    def list4 = list3 + [4, 5, 6]
    println list4  // 输出: [1, 2, 3, 4, 5, 6]
    
    // 方式3：使用左移操作符（添加多个元素）
    def list5 = [1, 2, 3]
    list5 << 4 << 5 << 6  // 逐个添加元素
    println list5  // 输出: [1, 2, 3, 4, 5, 6]

    // ------- 字符串拼接，过滤空和空字符 ---- // 
    def meta_data_map = ["title": "张三", "keywords": "[1,2,3]", "description":"这是个实例描述"]
    def title = meta_data_map?.title ?: ""
    def keywords = fn.list(meta_data_map?.keywords)
    def keywordsStr = (keywords ?: []).join(",") // 将关键字列表转换为逗号分隔的字符串
    def description = meta_data_map?.description ?: ""  
    def content = [title, keywordsStr, description]
                .findAll { it }  // 过滤 null 或空字符串
                .join("""\n""")     // 用换行拼接
                
    // ------- list和map混用使用方法 ---- //
    def result = []
    for (i in 0..<n_list1.size()) {
        def item = [
            "ig": n_list1[i].al,
            "te": n_list1[i].bl
        ]
        result.add(item)
    }
    return result
    
    // json字符串转map，可以调用我们自定义的系统内置函数的fn.XXX
    def jsonStr = '{"name":"Tom","age":18,"tags":["java","groovy"]}'
    def map = fn.map(jsonStr)
'''

//# 1. 显示调用agent（内部、外部的agt_XXX.txt都支持）
agt_XXX?extension={{n_var2.a}}
//# 2. 隐式调用调用agent（内部、外部的agt_XXX.txt都支持）。参数名必须和后端接口名称保存一致
n_analysis_result, act_plan.run?executor={{agt_XXX}}&parameters={{n_var2}}

// 定义一个内部的agent。agent定义以agt_开头，使用下划线命名法，例如agt_config_template
agent('agt_XXX', {
    // 可任何语法任何逻辑实现 todo
})

// ----------------------- RPA 相关操作----------------------- 
// RPA 获取页面信息
act_rpa.send?command=GetHtml2Mardown

// ----------------------- Python 文件 相关操作----------------------- 
// 保存为json文件,默认tmp文件夹
act_python.writeTmp?requirement='json_test文件'&pythonType='json'&pythonCode='{{n_var1}}'
// 保存为json文件,默认generated文件夹
act_python.write?requirement='json_test文件'&pythonType='json'&pythonCode='{{n_var1}}'
// 保存为json文件,agt_core_code文件夹
act_python.write?requirement='json_test文件'&pythonType='json'&pythonCode='{{n_var1}}'&subfolder='agt_core_code'


// 大模型调用（tpl_开头）
define('tpl_XXX', '''
    Reference answer:
    // 上一个Step执行结果
    {{sys_last_result}}
    {{tpl_select_agent}}
    
    Customer's Question:
    {{userInput}}

    {{fn.chatFlag('%{system_prompt}%', '', '', '', 10)}}
''')


// ----------------------- 知识库检索相关tool --------------------
// 以下tool调用，使用act_或rag_ 开头都可以

assign('userInput','查询下知识库有什么')
assign('params','{"question":"{{userInput}}","folderIds":"{{kbSpaceId!}}","knowledgeIds":"{{kbSpaceFileId!}}"}')

// 向量库查询（部分属性可能来自于mysql）
/*
    requirement  用户需求
    name         表名,不传默认取系统配置type=knowledge_agent&value={{来自系统配置type=knowledge_vendor&value=default属性}}
    column       指定查询的向量化字段，默认content或自动查找表meta_data_column定义的第一个向量字段
    outputFields 返回的列，支持多个列，逗号分隔,不传默认全部
    top          返回的数量，不传默认10
    isRerank     是否 二次rerank（调用外部的rerank接口）,true为是,
    bm25Weight   bm25权重(精确优先匹配权重）,值范围为[0-1]。不想生效设置为0，空时默认为0.3（底层知识库查询方法已经有默认值）
    isReferenceGraph  是否关联图谱知识 true为是，否则false,
    filterItem   用于精确匹配向量库中的标量字段，格式为:{"filterKey":"tool_type","operator":"==","filterValue":"API","logicType":""}
*/
act_kb.search?requirement=&name=&column&outputFields=

// 查询知识空间的名称（即目录名称）
/*  参数说明： kbSpaceIdOrFileId:知识空间ID即目录ID  或 知识ID即文件ID  （其中多个值会用拼接，只至此一种类型）
             isKbSpaceFileId：是否是文件id（0-目录id 1-文件id） 
             format：需要展示的的样式 ，比如 【%s】，代表结果会加上【】，目前只支持一个占位符
*/
rag_kbs.searchKbSpaceName?kbSpaceIdOrFileId={{n_kbSpaceIdOrFileId!}}&isKbSpaceFileId={{n_isKbSpaceFileId}}&format=': %s'

// 根据问题查询知识库的内容（根据系统配置决定查询ink的接口还是自己的向量库,自己的自己的向量库同act_kb.search)
/*  参数说明： 
    question：问题文本
    similarity： 相似度阈值0-1。 若传空或不传，则默认最低0.5
    top：返回Top N，不传默认5
    isRerank：是否二次rerank（true/false）
    bm25Weight：BM25权重0-1（0不生效，空默认0.3） 0代表只相似度查询 1代表关键字/全文检索 ，(0-1)代表混合查询 其中值为BM25权重值
*/
rag_kbs.searchQuestion?paramData='{{params}}'&similarity='0.1'&top=10&bm25Weight=0.3&isRerank=true

// 将data转换为引用内容 - 每条内容末尾自动加上"<div id=\"" + 引用id + "\" type=\"" + 引用类型（1-切片信息 2-专家知识 3-原始文档） + "\" num=\"" + num + "\"/>"
// 场景：一般聊天页面需要引用图标展示。另外一定要最终输出结果加上psr_ref ,还需要结合提示词使用，参考agt_chatdoc_qa.chatdoc这个agent
/* 参数说明： 
    data：json数组 。 
    invokeId 除了foreach循环要区分唯一性，则需要传，比如报表的agent，否则不要传invokeId。 
*/
rag_kbs.markReference?data='json数组'&invokeId=122222222

// 分页查下文件内容（查询mysql的kbs_doc_page_parse.content)
/* 参数说明： 
    paramData： 通上面的参数params，但目前只有里面的属性knowledgeIds生效
    minPage:最小页码 可不传
    maxPage:最大页码 可不传
    top：返回多少条
*/
rag_kbs.searchFileContent?paramData='{{params}}'

// 查询专家知识（FAQ）
/* 参数说明：
    question         查询问题question
    similarity       相似度，注意下游接口默认最低0.5。 非INK接口没起作用，先预留，备用
    isRerank         是否 二次rerank（调用外部的rerank接口）,true为是,true为是
    bm25Weight       bm25权重(精确优先匹配权重）,值范围为[0-1]。不想生效设置为0，空时默认为0.3（底层知识库查询方法已经有默认值）
    isReferenceGraph isReferenceGraph   是否关联图谱 true：是. 若传空或不传，则默认false
    isTest           是否测试,true:是. 比如页面是测试按钮. agent脚本调用不要传。 这个目的是用来记录判断是否开启记录使用的次数 
*/
rag_kbs.searchFaq?question='{{userInput}}'&similarity='0.1'&top=10&isRerank=true&bm25Weight=0.3

// 重排序数据 （通过调用api接口实现调用外部接口进行Rerank） 
/* 参数说明：
    apiCode      rerank 接口编码 .可以不用传，如果不传，则默认取系统配置的rerank_api使用哪个api_code，默认api_code是siliconflow_rerank
    params       接口参数  {query: 搜索文本, documents: [文档列表], top_n: 返回文档数量} 
*/
assign('n_documents','{"query": "搜索文本", "documents": "[\"1\",\"2\"]", "top_n": 10}')
//## Rerank
n_rerank_data,act_kbs.rerankData?params={{fn.json('query, userInput, documents, n_documents, top_n, "5"')}}

// 查询知识库文档（根据目录、用户的问题，找到对应的知识文档，检索文件名）
/* 参数说明：
    folderIds    目录ids，多个用逗号分隔
    question     用户问题
    size         返回文件个数，不传默认为20
    outputFields 返回属性，格式如：id,knowledge_name，不传默认为：id,knowledge_name,extension,store_uri,kb_spaces_folder_id,file_name
    score        相似度分数，double类型，不传默认为0.7
*/
rag_kbs.searchDocument?folderIds='1,2,3'&question='查询文档'&size=10&outputFields='id,knowledge_name'&score=0.8

// 查询知识信息（根据不同调用类型查询知识ID或其他字段）
/* 参数说明：
    callType           调用类型：folderName(根据目录名)、folderPath(根据目录路径)、folderIds(根据目录ID)、knowledgeIds(根据知识ID)
    value              对应类型的值，支持多值（逗号分隔）
    knowledgeNameItems 知识名称列表，可选过滤条件  目前只针对callType=folderIds时生效
    outFieldName       kb_spaces_folder_knowledge表输出的字段名，默认为id，只能指定一个字段
*/
rag_kbs.callQuestionKnowledges?callType='folderName'&value='技术文档'&knowledgeNameItems=''
rag_kbs.callQuestionKnowledges?callType='folderPath'&value='appdata/ac_script/selenium/result/FAAP_001'&knowledgeNameItems=''&outFieldName='id'
rag_kbs.callQuestionKnowledges?callType='folderName'&value='技术文档'&knowledgeNameItems=''&outFieldName='id'
rag_kbs.callQuestionKnowledges?callType='folderIds'&value='1,2,3'&knowledgeNameItems=''&outFieldName='knowledge_name'
rag_kbs.callQuestionKnowledges?callType='knowledgeIds'&value='100,101,102'&knowledgeNameItems=''&outFieldName='store_uri'

// 根据问题搜索报告模板内容
/* 参数说明：
    question：问题文本 非空
    top： 返回前N条，不传或空默认为3
*/
rag_kbs.searchTemplate?question='查找咨询报告'&top=5

// ----------------------- API Tool 相关操作-----------------------
// 调用API工具的doExecute方法 。旧方法，不支持复杂的动态参数/环境变量参数解析，建议不要用，建议使用下面的act_api.doExecuteDynamic方法
/* 参数说明：
    name：API配置编码 （记住是api工具页面配置的编码，不是name）
    params：请求参数Map
    fileNames：文件名列表（可选）
    headers：请求头Map（可选）
*/
n_api_result,act_api.doExecute?name='api_code'&params='{"param1":"value1","param2":"value2"}'&fileNames='[]'&headers='{"Content-Type":"application/json"}'

// 调用API工具的doExecuteDynamic方法  -- 新方法，推荐使用
/* 参数说明：
    name：API配置编码 （记住是api工具页面配置的编码，不是name）
    dynamicParamData：动态参数数据Map
*/
n_dynamic_api_result,act_api.doExecuteDynamic?name='api_code'&dynamicParamData='{"key1":"value1","key2":"value2"}'
```
