# AI玩家无响应问题排查指南

## 症状
使用 `/as say <指令>` 或 `/as <指令>` 后，AI玩家没有任何聊天回复，也不执行任何动作。

## 排查步骤

### 1. 检查配置文件
配置文件位置：`config/aisteve-common.toml`（服务器）或 `run/config/aisteve-common.toml`（开发环境）

必须配置以下项目：
```toml
[ai]
provider = "deepseek"  # 或 openai/groq/gemini

[deepseek]
apiKey = "sk-xxxxxxxxxxxxx"  # 必须填写真实的API密钥
model = "deepseek-chat"       # 或 deepseek-flash
baseUrl = "https://api.deepseek.com"
```

**常见错误：**
- API密钥为空或只有 `"sk-"` 前缀
- baseUrl 填写错误
- 配置文件不存在（首次启动会自动生成）

### 2. 查看日志
日志文件位置：`logs/latest.log`

**查找关键错误信息：**
```bash
# Windows PowerShell
Select-String -Path "logs/latest.log" -Pattern "API key|Failed|ERROR|Steve.*processing"

# Linux/Mac
grep -i "API key\|Failed\|ERROR\|Steve.*processing" logs/latest.log
```

**正常情况下应该看到：**
```
[aisteve/TaskPlanner] [Async] Requesting AI plan for Steve 'xxx' using deepseek: <你的指令>
[aisteve/TaskPlanner] [Async] Plan received: <计划> (N tasks, Xms, Y tokens, cache: false)
[aisteve/ActionExecutor] Steve 'xxx' queued N tasks
```

**异常情况：**
```
No API key configured for provider 'deepseek'
Failed to get AI response for command: <指令>
[Async] Empty response from LLM
Failed to parse AI response
```

### 3. 测试网络连接

#### DeepSeek API 测试
```bash
curl -X POST https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer sk-你的密钥" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "hi"}],
    "max_tokens": 10
  }'
```

**预期返回：**
```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "Hello! ..."
      }
    }
  ]
}
```

**常见错误：**
- `401 Unauthorized` - API密钥错误
- `402 Payment Required` - 账户余额不足
- `429 Too Many Requests` - 请求频率过高
- `超时/无法连接` - 网络问题或防火墙拦截

### 4. 检查AI玩家状态
```
/as info
```
应该显示AI玩家的位置、生命值、背包等信息。如果提示"当前没有AI玩家"，需要先创建：
```
/as create 测试
```

### 5. 启用详细日志（开发环境）
编辑 `src/main/resources/log4j2.xml`，将日志级别改为 DEBUG：
```xml
<Logger name="com.steve.ai" level="DEBUG"/>
```

重新运行 `./gradlew runClient`

## 常见问题解答

### Q: 配置了API密钥但还是不工作
**A:** 
1. 确认配置文件已保存（不是 `.example` 文件）
2. 重启游戏（配置修改需要重启才能生效）
3. 检查API密钥是否包含多余的空格或换行符
4. 使用GUI配置界面重新输入（按 K 键或 `/as config`）

### Q: 日志显示 "No API key configured"
**A:** 
- 如果使用 `provider = "deepseek"`，需要配置 `[deepseek].apiKey`
- 如果 `[deepseek].apiKey` 为空，会尝试使用 `[openai].apiKey` 作为备用
- 至少需要配置其中一个API密钥

### Q: 看到 "Plan received: (0 tasks)" 
**A:** 这表示使用了fallback规则，LLM实际没有返回响应。原因：
- API调用失败（网络/认证问题）
- 响应解析失败
- 查看前面的日志确认具体错误

### Q: 使用国内网络无法访问OpenAI
**A:** 
1. 使用国内可访问的API提供商（DeepSeek、SiliconFlow、阿里云百炼）
2. 配置代理或中转服务
3. 使用配置界面的预设快速切换提供商

### Q: AI玩家说话但不执行动作
**A:** 检查能力开关配置：
```
/as config
```
进入"AI能力开关"页面，确保需要的能力（采矿、建造等）已启用

### Q: 空手右键点 AI，背包窗口没打开
**A:** 按顺序确认：

1. **手上是不是真的空的** —— 主手有东西时会走"交给它 1 个"的行为（这是设计如此）；
2. **它是否还在你身边** —— 窗口打开后走远 8 格，或者它被 `/as remove` 移除，窗口会自动关闭；
3. 打开的是**只读**窗口（槽位不能取放，属设计如此）。取回东西用 `/as take`；
4. 如果窗口打开了但里面一直是空的，说明它确实什么都没拿 —— 用 `/as info` 交叉确认一下。

如果窗口完全打不开，请看 `logs/latest.log` 里有没有 `aisteve` 相关的报错，
以及客户端是否加载了 `steve_inventory` 这个菜单类型。

### Q: 测试时想看到即时反馈
**A:** 启用聊天响应：
1. 按 K 键打开配置
2. 进入"AI权限与行为"
3. 将"AI聊天响应"设为"启用"
4. 保存

这样AI玩家收到任务后会回复 "Okay! <计划>"

## 获取技术支持

如果以上步骤都无法解决问题，请提供以下信息：

1. 配置文件内容（隐去真实API密钥）
2. `logs/latest.log` 中相关的错误日志
3. 使用的指令和预期行为
4. Minecraft版本、Forge版本、Mod版本

创建 issue：https://github.com/Tawesh/AiSteve/issues
