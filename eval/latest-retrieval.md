# 检索层自己的度量

端到端召回分不清是检索错还是深挖错：一条条款没被检索回来，和条款回来了但模型没理它，在报告上长得一样。所以先单独量检索。

做法：语料里每条「该命中的条款」标一个可接受集合（打印密码同时违反「认证失败的日志」与「不得写标准输出」，两个都算对，逼它二选一等于量运气），跑初审，按 `pol-` 编号在计划里的先后算名次。

五个标记用例（其余四十四条不标，只统计有没有意外命中）：

| 用例 | 该命中 | 纯嵌入 | 嵌入 + 词面加分 |
|---|---|---|---|
| case-insensitive-password | pol-security-2（口令比较） | 未命中（回了 pol-security-1） | 未命中（回了 pol-security-1） |
| password-content-equals | pol-security-2 | 未命中（回了 pol-security-1） | 未命中（回了 pol-security-1） |
| policy-plain-equals | pol-security-2 | 未命中（回了 pol-security-1） | 未命中（回了 pol-security-1） |
| password-logged | pol-security-1 或 pol-logging-1 | 第 1 | 第 1 |
| policy-stdout-in-audit | pol-logging-1 | 未命中（没过阈值） | 第 1 |

顶一命中：纯嵌入 1/5，加词面加分 2/5。条款总数只有五条，所以这不是「检索困难」，是「检索没起作用」。

最值得看的一行是口令比较：三例全错到同一个兄弟条款。「认证失败的日志」与「口令比较」在同一份文档里，前者正文里也写着「口令」两个字，代码里的 `password`、`equals` 就把它顶了上去。这正是代码与文字跨模态匹配的偏差——不是分数不够高，是分错了对象。

复现：`java -cp "target/classes;<classpath>" com.codereview.agent.eval.PolicyRetrievalEval target/eval-corpus`

下一步：条款附正反示例代码，检索时同时比条款正文与示例代码，取最高分；三种打分（纯嵌入、加词面、加示例）跑同一张表对比。
