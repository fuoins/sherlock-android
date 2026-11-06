# Sherlock Mobile — Sherlock 项目的非官方 Android 手机端

> **声明**：本项目是 [sherlock-project/sherlock](https://github.com/sherlock-project/sherlock) 的**非官方 Android 手机端 / 移动客户端**（unofficial mobile client）。
> 本项目与 Sherlock Project 及其维护者**没有隶属、授权或背书关系**，"Sherlock" 名称仅用于指明上游来源。
> 站点探测规则（`app/src/main/assets/sites.json`）直接取自上游仓库，遵循其 MIT 许可证，版权归 Sherlock Project 所有。

上游是一个用用户名在 400+ 社交网络/论坛上查找账号的 OSINT 命令行工具。本工程把它的**规则表 + HTTP 判定引擎**搬到 Android 上，做成一个可以直接在手机上查询的客户端。

## 特性

- 输入用户名，批量探测上游清单中的全部站点（内置 481 条规则），实时显示进度与命中结果
- 判定逻辑与上游一致：`status_code` / `message` / `response_url` 三种方式、WAF 指纹识别、`regexCheck` 预过滤、`urlProbe` 专用探测地址、自定义 headers 与请求方法
- 支持在线更新站点规则（拉取上游 manifest + 误报排除列表），改版站点不用发版
- 结果可导出并通过系统分享；可选"显示全部结果 / 包含 NSFW 站点"
- 纯客户端，无服务端、不登录、不上传用户名到任何自建服务器

## 仅 arm64

**本工程只构建 `arm64-v8a` 一种 ABI**，不生成 32 位包，也不生成 universal 包：

- `app/build.gradle.kts` 中 `splits { abi { reset(); include("arm64-v8a"); isUniversalApk = false } }`，只生成带 `arm64-v8a` 后缀的单个 APK
- AGP 禁止 `ndk.abiFilters` 与 `splits.abi` 同时设置（会报 `Conflicting configuration`），因此只用 splits，产物名即标明架构
- `minSdk = 26`（Android 8.0+，64 位 ARM 设备）

产物为单个 `app-arm64-v8a-debug.apk`。装到 32 位设备或 x86 模拟器会失败，这是预期行为。

## 构建

### GitHub Actions（推荐）

仓库自带 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

- 触发：`push` / `pull_request` / 手动 `workflow_dispatch`
- 环境：ubuntu-latest + JDK 17 + Android SDK 35 + Gradle 8.9
- 步骤：`./gradlew assembleDebug` → 收集 arm64 APK → 上传为 Actions Artifact `sherlock-mobile-arm64-v8a`

在 Actions 页面手动运行一次，构建完成后下载 Artifact 即可安装。Debug 包已用调试密钥签名，可直接 `adb install`。

### 本地构建

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

或用 Android Studio 打开本目录直接运行（需要 JDK 17、Android SDK 35）。

> 发布用 release 包请在 `app/build.gradle.kts` 配置 signingConfig 并把密钥放进 GitHub Secrets，不要提交 `.jks` 或 `local.properties`（已在 `.gitignore` 排除）。

## 项目结构

```
app/src/main/
├── assets/sites.json                # 上游 data.json 快照（481 个站点规则）
├── java/xyz/sherlock/mobile/
│   ├── MainActivity.kt              # 界面、扫描调度、结果分享
│   ├── engine/
│   │   ├── Models.kt                # SiteRule / ScanResult / QueryStatus
│   │   ├── SiteRepository.kt        # 规则解析、本地缓存、在线更新、排除列表
│   │   └── Scanner.kt               # 并发探测与三种判定逻辑（核心）
│   └── ui/ResultAdapter.kt          # 结果列表
└── res/                             # 布局、主题、自适应图标（全为文本资源）
```

核心引擎相当于上游 `sherlock_project/sherlock.py` 中 `sherlock()` 的 Kotlin 实现：用 OkHttp + 协程信号量把并发限制在 20，一次性发出全部请求，再按规则逐站判定。上游清单的字段含义见其 [`data.schema.json`](https://github.com/sherlock-project/sherlock/blob/master/sherlock_project/resources/data.schema.json)。

## 与上游的差异（移动端取舍）

| 项 | 上游 CLI | 本工程 |
|---|---|---|
| 规则来源 | 默认在线 manifest | 内置快照 + 手动在线更新（离线可用） |
| 并发 | 20 / 默认超时 60s | 20 / 默认超时 20s（蜂窝网络更短） |
| 导出 | txt / CSV / XLSX | 文本 + 系统分享（去掉 pandas/openpyxl 等重依赖） |
| 代理 | `--proxy` | 暂无（可自行在 `Scanner` 里给 OkHttpClient 设置 Proxy） |
| 结果状态 | CLAIMED / AVAILABLE / UNKNOWN / ILLEGAL / WAF | 完全一致 |

## 免责声明与合规使用

- 本工具**只读取公开页面**，不登录、不爆破、不绕过任何站点的访问控制；遇到 Cloudflare 等 WAF 会标记为"被拦截"而非强行绕过。
- 判定是**启发式**的：网站改版、限流、A/B 页面都会造成误报或漏报，命中结果请人工复核。"同名"也不等于"同一个人"。
- 输入的用户名会被发送到清单中的**数百个第三方站点**，使用时请自行评估隐私影响。
- **请仅用于**：查询自己的数字足迹、品牌/舆情自查、获得授权的安全测试与 OSINT 研究。禁止用于骚扰、跟踪、人肉搜索或任何违法用途。因使用本工具产生的后果由使用者自行承担。

## 许可证

本项目代码：MIT（见 [LICENSE](LICENSE)）。
站点规则 `sites.json` 来自 [sherlock-project/sherlock](https://github.com/sherlock-project/sherlock)，MIT © Sherlock Project。
