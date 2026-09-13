# LiquidPanel

Minecraft 服务端网页面板。HTTP 与 WebSocket 服务内置在插件里，
不需要另外起 Web 服务、不需要反向代理，装上去就能用。

面向 Spigot / Paper 1.21+，Java 21。

## 功能

**概览** —— CPU 型号与核心数、整机与进程占用率（可切换单线／复线折线图）、
物理内存与 JVM 堆、服务端文件夹占用的硬盘空间、各世界文件夹大小、
在线人数与 TPS 曲线、实时控制台日志。

**控制台** —— 实时日志（原控制台的颜色 1:1 还原，UTF-8 不乱码），
以及直接下发服务端命令。

**文件管理** —— 浏览、上传（带进度条）、下载、在线编辑文本文件、
新建、重命名、删除、剪切、复制、压缩（zip / 7z）、解压。
根目录锁定在服务端目录，出不去。

**玩家管理** —— 全部 / 在线 / 离线 / 已封禁 四种筛选，正版皮肤头像、
UUID、实时坐标、最后下线位置、生命（含伤害吸收）· 饥饿 · 护甲 · 经验等级。
可执行踢出、封禁（支持临时封禁）、解封、传送、以玩家身份执行命令。
接入 Vault 后还能查看并直接增减余额。

**设置** —— 面板账号密码、每页玩家数、封禁方式、Vault 经济开关。

## 环境要求

| 项目 | 要求                                        |
| --- |-------------------------------------------|
| 服务端 | Spigot / Paper 1.21+（开发环境为 Paper 1.21.11） |
| Java | 21                                        |
| 可选 | Vault + 任意经济插件（只在使用余额功能时需要）               |

## 安装

1. 把 jar 放进 `plugins/`
2. 启动服务端
3. 控制台会打印面板地址与初始账号密码

初始账号是 `admin`，密码是随机生成的 16 位大小写字母与数字。
它**只在首次生成 `account.json` 时打印一次**，之后不再显示。
首次登录会被强制要求改掉。

面板默认监听 `0.0.0.0:1357`。只想本机访问就把 `panel.host` 改成 `127.0.0.1`。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/liquidpanel help` | 帮助列表 | `liquidpanel.command.help` |
| `/liquidpanel reload` | 重载并缓存配置与语言文件 | `liquidpanel.command.reload` |
| `/liquidpanel openpanel` | 在服务端所在机器上打开浏览器（仅控制台可用） | `liquidpanel.command.openpanel` |

别名 `/ldp`。另有 `liquidpanel.admin` 一把梭权限。

## 配置

配置分成两个文件，职责不同：

### `config.yml`

管理员手改的启动配置，改完执行 `/ldp reload`。

- `language` —— 语言文件后缀，例如 `zhcn` 对应 `messages_zhcn.yml`
- `panel.enabled` / `port` / `host` —— 监听参数
- `panel.session_timeout_minutes` —— 登录状态闲置多久失效
- `panel.bind_session_ip` —— 是否把登录状态与来源 IP 绑定
- `panel.allowed_hosts` —— 域名白名单。用域名访问面板时必须填，否则同源校验会拒绝
- `panel.database.type` —— `json` 或 `sqlite`
- `panel.security.*` —— 防密码爆破的失败次数、窗口与封禁时长

改 `panel.database.type` 需要重启服务端：两种实现的文件格式不相通。

### `panelconfig.json`

网页上直接改、改完立刻落盘的东西。单独一个文件是为了
**避免网页去覆写管理员手写的 yml**（会丢注释、丢格式）。

- `players_per_page`
- `ban_method` 与 `ban_command_temp` / `ban_command_perm` / `ban_command_unban`
- `vault_enabled`

## 封禁方式

在设置页的「兼容性设置」里选，三种方式都不要求玩家在线。

| 方式 | 临时封禁 | 永久封禁 | 解封 |
| --- | --- | --- | --- |
| `Vanilla` | 写进服务端封禁名单 | 同左 | `pardon` |
| `LiteBans/AdvancedBan` | `tempban <玩家> <时长> <原因>` | `ban <玩家> <原因>` | `unban <玩家>` |
| `CustomCommand` | 自己填，占位符 `%player%` `%reason%` `%time%` | 同左 | 自己填 |

时长写法是 `1y2mo3w4d5h6m7s`，1 年按 365 天、1 月按 30 天折算。

三种方式的**到期解除都由面板负责**：原版方式也只往服务端名单里写一条永久封禁，
到期时间记在面板自己的存储里，由定时任务到点解除。
这样到期逻辑只有一处，不会出现「原版封禁等服务端解、插件封禁等面板解」两套行为。
代价是临时封禁离不开存储 —— 存储不可用时临时封禁会被直接拒绝，宁可封不出去。

## 安全

面板是管理入口，所以这块是重点：

- 密码用 **PBKDF2-HMAC-SHA256**（210,000 次迭代）加随机盐存储，比对是恒定时间的
- 会话令牌放 Cookie，`HttpOnly` + `SameSite=Strict`，可选绑定来源 IP
- 同源校验 + **Host 白名单**，用来挡 DNS Rebinding
- 请求体在 IO 线程上异步收完之后才派发到工作线程，
  声明了 `Content-Length` 却一个字节不发的连接占不住工作线程
- 登录失败次数限制与来源封禁
- CSP、`X-Content-Type-Options`、`X-Frame-Options` 等安全响应头
- 文件路径先 `toRealPath()` 跟随符号链接、再校验是否仍在根目录内

`cyber/` 目录下有三份安全审计报告与对应的验证脚本。

> 面板默认跑在 **HTTP** 上。要暴露到公网请套一层 HTTPS 反向代理。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。运行时依赖直接展开进 jar，没有使用 shadow 插件。

### 关于 Vault 依赖

经济接口来自 `net.milkbowl.vault:VaultAPI:1.7`，走 **CodeMC** 仓库（Vault 的官方发布处；
它不在 Maven Central 上）。

这个依赖是 `compileOnly`：**不会被打进插件**，运行时由服务端上装的 Vault 插件提供。
也就是说构建时不需要你准备任何东西，装到服务端上才需要装 Vault。

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。
