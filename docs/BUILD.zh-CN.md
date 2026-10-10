# AiSteve — 本地构建与使用指南（DeepSeek API 版）

> 本文档面向本地开发/使用者，指导如何构建 AiSteve（Minecraft 1.20.1 Forge 模组）并接入 **DeepSeek API**。
>
> 仓库来源：<https://github.com/Tawesh/AiSteve.git>（上游：<https://github.com/YuvDwi/Steve.git>）
>
> 构建产物与依赖说明见 [README](../README.md#构建)；当前已知缺口见 [STATUS](STATUS.md)。

---

## 1. 项目简介

AiSteve 是一个运行在 **Minecraft 1.20.1（Forge）** 中的 AI 智能体模组。它把大语言模型（LLM）接入游戏，
让一个名为 “Steve” 的实体能够理解自然语言指令，自主寻路、挖矿、砍树、合成、钓鱼、种田、翻箱子、建造、战斗与探索。

- 交互方式：游戏内用聊天指令 **`/as`**（例如 `/as mine 20 iron ore`）。**没有 GUI 面板。**
- **同时只能存在 1 个** AI 玩家，用 `/as create <名字>` 创建。
- 技术栈：Java 17 + Forge 47.2.0 + Gradle 8.4（Gradle Wrapper）。
- LLM 能力：内置多个 provider 客户端，可插拔。

> DeepSeek 提供的是 **OpenAI 兼容接口**，本仓库已新增 `deepseek` provider（见第 7 节），可像其他 provider 一样配置使用。

---

## 2. 环境要求

| 组件 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | **17**（必须） | Forge 1.20.1 要求 Java 17；高版本 JDK（如 21/23）可能导致构建失败 |
| Gradle | 无需单独安装 | 使用项目自带 `gradlew` / `gradlew.bat`（Gradle 8.4） |
| Minecraft | 1.20.1 | 需已安装对应版本的 **Forge**（47.2.0+） |
| 网络 | 可访问外网 | 构建时需下载依赖；运行时需访问 `api.deepseek.com` |
| 磁盘 | 建议 ≥ 3 GB 可用空间 | Gradle 缓存 + Forge 依赖较大 |

检查本机 Java 版本：

```powershell
java -version
```

若输出不是 17.x，请安装 JDK 17（如 Eclipse Temurin 17）并设置 `JAVA_HOME` 指向它。

---

## 3. 获取代码

```powershell
git clone https://github.com/Tawesh/AiSteve.git
cd AiSteve
```

（下文所有命令均假设当前目录为仓库根目录。）

---

## 4. 本地构建

> Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`。

### 4.1 编译（快速校验）

```powershell
.\gradlew.bat compileJava
```

### 4.2 构建可安装的 JAR（重要）

```powershell
.\gradlew.bat build -x test fatJar
```

> 说明：
> - `-x test` 跳过单元测试（仓库内测试多为占位 `TODO`）。
> - **`fatJar` 是必须的**：上游默认的 `jar`/`reobfJar` 只打包模组自身的 class，
>   不含运行期依赖库（resilience4j / caffeine）。直接把它装进游戏，
>   在 Steve 规划任务时会抛 `NoClassDefFoundError` 而崩溃。
>   `fatJar` 会把「reobf 重混淆后的模组类 + 上述依赖库」合并成一个可直接安装的 JAR。

构建成功后，产物位于 `build/libs/`：

| 文件 | 大小(约) | 用途 |
| --- | --- | --- |
| `aisteve-1.1.0-all.jar` | ~1.7 MB | ✅ **装进 `mods/` 就用这个**（已含依赖库） |
| `aisteve-1.1.0.jar` | ~0.2 MB | ⚠️ 仅含模组类，单独安装会在运行期崩溃（开发环境由 Gradle 提供依赖，可用） |

> 也可只跑 `.\gradlew.bat fatJar`（它会自动先执行 `reobfJar`）。
> GraalVM 相关类刻意未打包：`CodeExecutionEngine` 在本项目中从未被实例化（死代码），
> 打包 JS 引擎体积大且易与其他模组冲突。

### 4.3 开发环境直接运行（调试用）

```powershell
.\gradlew.bat runClient
```

首次运行会生成 `run/` 目录（含开发环境存档与配置），可在此直接测试模组。

### 4.4 常见构建问题

- **`Unsupported class file major version` / Java 版本报错**：构建要求 **JDK 17**。若本机只有 JDK 21 等版本，
  Gradle 会通过 `foojay-resolver` **自动下载 JDK 17**（实测可行），无需手动安装。
- **`Downloading ... gradle-8.4-bin.zip failed: timeout`**：Gradle Wrapper 需要访问 `services.gradle.org`。
  若本机直连受限但存在本地代理（如 `127.0.0.1:7890`），可让构建走代理：
  ```powershell
  $env:JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
  .\gradlew.bat build -x test fatJar
  ```
  或在 `%USERPROFILE%\.gradle\gradle.properties` 中取消注释 `systemProp.http(s).proxyHost/Port`。
- **依赖下载缓慢或失败**：构建需访问 `maven.minecraftforge.net`、`services.gradle.org`、`repo1.maven.org`、`repo.maven.apache.org`。
- **Gradle 内存不足**：项目 `gradle.properties` 中 `org.gradle.jvmargs=-Xmx3G`，可酌情调大。
- **首次构建很慢**：首次需下载 Minecraft/Forge 并做 MCP 反编译，实测约 **7 分钟**；之后增量构建约 **1 分钟**。

---

## 5. 安装到游戏

> ⚠️ **必须使用 Forge，不能用 Fabric。** 本模组是 Forge 1.20.1 模组；
> 放进 Fabric 实例会被忽略（日志出现 `Found N non-fabric mod`），表现为指令不存在。
> 详见 [USAGE.zh-CN.md](USAGE.zh-CN.md) 第 0 节。

1. 安装 **Minecraft 1.20.1** 并安装 **Forge 47.x**（推荐 47.2.0）。
2. 启动一次游戏以生成 `.minecraft` 目录结构。
3. 将第 4.2 步生成的 **`aisteve-1.1.0-all.jar`** 放入 `.minecraft/mods/` 目录。
4. 启动游戏，进入任意世界，然后执行 `/as create <名字>`。

---

## 6. 配置 DeepSeek API（核心步骤）

模组使用 Forge 配置系统，配置文件为 `config/aisteve-common.toml`：

- **正式游戏**：`.minecraft/config/aisteve-common.toml`
- **开发运行（runClient）**：`Steve/run/config/aisteve-common.toml`

首次启动后该文件会自动生成。可参考仓库中的示例：`config/aisteve-common.toml.example`。

> ⚠️ **重要**：`aisteve-common.toml.example` **只是参考模板，游戏不会读取它**。
> 你必须把内容填到真正的 `config/aisteve-common.toml`（上面两个路径之一）才会生效。
> 推荐做法：先启动一次游戏让它自动生成 `aisteve-common.toml`，再把 Key/模型填入该文件。

### 6.1 DeepSeek 配置示例

> ⚠️ **先看这里**：配置文件是**游戏首次启动时自动生成**的，生成时 **key 是空的、provider 是默认的 `groq`**。
> 你必须去改**那份自动生成的文件**，改完**重启游戏**才生效。
> 仓库里的 `config/aisteve-common.toml.example` 只是模板，**游戏不会读取它**。

配置文件位置见本节开头。编辑 `config/aisteve-common.toml`：

```toml
[ai]
    # 选择 DeepSeek 作为 provider
    provider = "deepseek"

[deepseek]
    # DeepSeek API Key（在 https://platform.deepseek.com/api_keys 获取）
    apiKey = "sk-你的DeepSeek密钥"

    # 模型（本账号当前可用模型见下）
    model = "deepseek-flash"

    # API 基址（不要带末尾的 /chat/completions）
    baseUrl = "https://api.deepseek.com"

[openai]
    # 若 provider = "deepseek" 且下方为空，则复用本文件 [deepseek].apiKey
    apiKey = ""

    maxTokens = 8000
    temperature = 0.7
```

### 6.2 配置字段说明

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `ai.provider` | `deepseek` | 也可填 `groq` / `openai` / `gemini` |
| `deepseek.apiKey` | `sk-****` | DeepSeek 密钥；留空时回退使用 `[openai].apiKey` |
| `deepseek.model` | `deepseek-flash` | **V4.1-Flash**，100 万上下文，支持图片理解，推荐 |
|  | `deepseek-v4-pro` | **V4-Pro**，100 万上下文，纯文本 |
|  | `deepseek-chat` / `deepseek-reasoner` | 旧模型名（V3 / R1），**仅在部分账号可用**，本账号已不可用 |
| `deepseek.baseUrl` | `https://api.deepseek.com` | 官方地址；如用代理/网关可替换 |
| `openai.maxTokens` | 100~65536，默认 8000 | 单次请求最大输出 token；新模型实际可支持更大输出，如需可在 `SteveConfig` 调整上限 |
| `openai.temperature` | 0.0~2.0，默认 0.7 | 越低越稳定；动作规划建议 0.5~0.7 |

> 可用模型可用以下命令自行查询（把 Key 换成你自己的）：
> ```powershell
> curl.exe -H "Authorization: Bearer <你的Key>" https://api.deepseek.com/models
> ```

> 说明：`maxTokens` / `temperature` 采用模组全局配置（在 `[openai]` 段），对所有 provider 生效。

### 6.3 DeepSeek 接口信息（供排查参考）

- 请求地址：`POST https://api.deepseek.com/chat/completions`
- 请求头：`Authorization: Bearer <API_KEY>`、`Content-Type: application/json`
- 请求体（OpenAI 兼容）：
  ```json
  {
    "model": "deepseek-flash",
    "messages": [
      {"role": "system", "content": "..."},
      {"role": "user", "content": "..."}
    ],
    "max_tokens": 8000,
    "temperature": 0.7
  }
  ```
- 响应：`choices[0].message.content` 为模型输出；`usage.total_tokens` 为 token 用量。
  若返回中包含 `reasoning_content`（推理过程），本模组会忽略它，只取 `content`。

---

## 7. 代码改动说明

本仓库相对上游做了大量改动，**完整清单见 [CHANGELOG.md](../CHANGELOG.md)**，按阶段分为：

| 阶段 | 内容 |
| --- | --- |
| 一 | 接入 DeepSeek API（新增 `[deepseek]` 配置段、同步/异步客户端、provider 分发） |
| 二 | 构建与打包修复（新增 `fatJar`；排除 Forge 自带的 `commons-codec` 以修复启动崩溃） |
| 三 | 上游 bug 修复（降级处理器、建造失效、协作建造只建 1/4 等） |
| 四 | 架构重构：移除 GUI 面板 → `/as` 指令；限定单一 AI 玩家 |
| 五~六 | 修复实体初始化崩溃、动作系统重构（`mine` 从"隧道机"改为真正的采集器） |
| 七 | 幽灵 AI 同步修复；新增探索与翻箱子能力 |
| 八 | 自给自足能力：深层挖矿、真实合成、钓鱼、种田、持久世界记忆 |
| 九 | 修正"任务完成却提示失败"的状态语义问题 |
| 十 | **项目更名为 AiSteve**（modId、配置名、jar 名、指令全部统一） |

> 配置示例 `config/aisteve-common.toml.example` 已同步更新。

---

## 8. 游戏内使用

### 8.1 创建 AI 玩家

```text
/as create Bob
```

同时只能存在一个。要换名字先 `/as remove`，升级模组后可用 `/as cleanup` 清理遗留实体。

### 8.2 下达指令

直接在聊天框用 `/as` 说自然语言（`say` 可省略）：

```text
/as mine 20 iron ore
/as build a house near me
/as follow me
/as defend me from zombies
/as 帮我弄一些吃的
/as 去挖点钻石
```

它不是背固定指令的——用日常说法即可，模型会自己判断并拆解步骤。

### 8.3 给 / 取物品

```text
/as give                       # 把手上的物品交给它
/as give flint_and_steel 2     # 直接给指定物品
/as info                       # 查看它的背包与当前目标
/as take                       # 收回它身上的所有东西
```

### 8.4 其他常用管理指令

| 指令 | 作用 |
| --- | --- |
| `/as info` | 位置、生命、背包、当前目标 |
| `/as stop` | 立即停止当前任务 |
| `/as come` | 把它叫到你身边 |
| `/as remove` | 移除 AI 玩家 |
| `/as cleanup` | 强制清除世界上所有 AI 实体（升级后兜底） |

---

## 9. 运行流程与排错

### 9.1 请求链路

```
玩家输入 → ActionExecutor → TaskPlanner(planTasksAsync)
        → AsyncDeepSeekClient.sendAsync → api.deepseek.com/chat/completions
        → ResponseParser 解析动作 → 逐 tick 执行动作
```

### 9.2 日志位置

- 正式游戏：`.minecraft/logs/latest.log`
- 开发运行：`Steve/run/logs/latest.log`

可搜索关键字：`deepseek`、`TaskPlanner`、`DeepSeek API error`。

### 9.3 常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 指令无响应，日志无请求 | Key 未配置 / provider 拼写错误 | 检查 `ai.provider="deepseek"` 与 `deepseek.apiKey` |
| 日志出现 `HTTP 401` | API Key 错误或失效 | 重新生成 DeepSeek Key |
| 日志出现 `HTTP 402`（余额不足） | 账户余额不足 | 充值后重试 |
| 日志出现 `HTTP 429` | 触发限流 | 稍后重试 |
| 请求超时 | 网络无法访问 `api.deepseek.com` | 检查网络/代理；必要时改用可达的 `baseUrl` 网关 |
| 动作解析失败 | 模型输出非预期 JSON | 换用 `deepseek-flash`，适当降低 `temperature` |
| `HTTP 400` 且提示 model 不存在 | 模型名无效 | 用 `/models` 接口查询可用模型名 |
| 日志 `Plan received: (N tasks, 0ms, 0 tokens)` | 0ms/0tokens 表示**规则降级**，不是真实模型回复 | 检查 Key/网络 |
| 构建卡在下载依赖 | 网络受限 | 配置 Gradle 代理/镜像后重试 |

> 容错提示：模组内置熔断/重试/降级机制。当某个 provider 连续失败触发熔断后，会短暂使用**基于规则的内置降级回复**（日志中标记为 `fallback`），并非真实模型输出。

---

## 10. 附：目录结构速览

```
Steve/
├── build.gradle                     # Forge 1.20.1 / Java 17 / 依赖声明
├── gradle/wrapper/                  # Gradle 8.4 Wrapper
├── config/aisteve-common.toml.example # 配置示例（已含 deepseek 段）
└── src/main/java/com/steve/ai/
    ├── config/SteveConfig.java      # 配置定义（含新增 [deepseek]）
    ├── llm/
    │   ├── TaskPlanner.java         # LLM 调用编排（已注册 deepseek）
    │   ├── DeepSeekClient.java      # ★ 新增：同步 DeepSeek 客户端
    │   ├── async/
    │   │   ├── AsyncDeepSeekClient.java  # ★ 新增：异步 DeepSeek 客户端
    │   │   └── LLMExecutorService.java   # 线程池（已加 deepseek）
    │   └── resilience/              # 熔断/重试/限流/降级
    ├── action/                      # 动作系统（挖矿/建造/合成/钓鱼/种田/探索…）
    ├── entity/                      # AI 实体、背包、世界记忆
    ├── memory/                      # 记忆 / 世界知识
    └── command/                     # /as 指令树
```

---

*如需改用 OpenAI / Groq / Gemini，只需把 `ai.provider` 改回对应值并在相应段落填写 Key 即可；改动为向后兼容。*
