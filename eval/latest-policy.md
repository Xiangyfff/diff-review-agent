# 政策检索的消融（离线替身、49 条用例）

同一份语料、同一个流水线，只把 `docs/policies` 的检索打开和关掉各跑一次。

| | 政策检索开 | 政策检索关 |
| --- | --- | --- |
| 缺陷用例 | 33 | 33 |
| 被排期 | 32（97%） | 32（97%） |
| 干净用例 | 16 | 16 |
| 被排期（误报） | 3（19%） | 3（19%） |
| 结论数 | 43 | 43 |
| 端到端召回 | 64%（21/33） | 64%（21/33） |

数字一模一样，说明政策检索在这份语料上没有改变召回或误报。唯一可测的差别在档位：

| 用例 | 有政策 | 无政策 |
| --- | --- | --- |
| `resource-unclosed` | 必查 | 待判断 |
| `policy-stdout-in-audit` | 必查 | 待判断 |

两个单元从「问模型值不值得查」变成「不用问，规则书说了必须查」。它们本来也会被排期
（待判断档加上模型给出的问题同样会进深挖），所以结论数与召回不变，省的是那一次询问，
换的是「这一档不由模型决定」。

## 为什么差别这么小

我们在语料里写的强制条款，恰好都能被别的规则抓到：

- 认证与口令相关的改动落在 `auth` 路径上，`SENSITIVE-PATH` 早就把它们推进必查档；
- 「生产代码不得写标准输出」同时触发静态检查的 `DEBUG_PRINT`，那是建议级，只让它进待判断档。

所以政策检索的增量价值在这份语料上体现不出来。它真正要解决的问题是另一类条款：无法用
路径或形状表达的强制要求，例如「访问令牌有效期不得超过两小时」「日志必须带 trace id」。
这类条款我们只补了两条用例，还没覆盖到这个量级。

## 检索本身的调参记录

- `bge-m3` 的余弦分数集中在 0.4–0.7 的窄带里，绝对阈值很敏感；
- 英文查询匹配中文条款时，最相关的条款是 0.49，无关的是 0.52，排不对；
- 把查询改成中文之后，同一条款升到 0.61，无关改动 0.52，阈值 0.55 就能分开；
- 纯嵌入会模糊标识符（`System.out`、`equalsIgnoreCase`），加了一点按标识符重合的加分，
  权重 0.25，加性。

复现：

```
mvn -q -DskipTests package
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
     com.codereview.agent.eval.EvalRunner target/eval-corpus eval-policy-on
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
     com.codereview.agent.eval.EvalRunner target/eval-corpus eval-policy-off --no-policy
```

关掉检索靠 `--no-policy`，走的是同一条流水线的开关，不是另一份代码。
