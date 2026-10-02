# 杜比大喇叭β版

[![Release](https://img.shields.io/github/v/release/CiscoPacket/dolby_beta?label=Release)](https://github.com/CiscoPacket/dolby_beta/releases/latest)
[![License](https://img.shields.io/github/license/CiscoPacket/dolby_beta?label=License)](https://choosealicense.com/licenses/mit/)

网易云音乐音源代理模块。工作原理为**音源替换而非破解**，单曲付费与无版权歌曲有几率匹配错误，真心支持歌手请付费。

当前版本 **4.1.1**（构建 20261002）。

## 特性

- **动态全版本兼容**：引入 [DexKit](https://github.com/Luckypray/DexKit) 动态特征匹配引擎，自动识别网易云音乐混淆类与关键方法，摆脱对特定版本的硬编码依赖。核心网络层、Tab、评论、主题、侧边栏、音效判定等全部走 DexKit 结构匹配。
- **智能缓存与自愈**：首次检索特征后将类名持久化到本地缓存，二次启动近乎零耗时；若网易云更新导致旧类加载失败，模块将自动失效缓存并启动自愈扫描。
- **全新 XEAPI / EAPI 协议适配**：内嵌 [ITManCHINA/server](https://github.com/ITManCHINA/server) `feat/adapt-xeapi` 分支最新脚本，全面适配网易云音乐新版加密协议。
- **原生双架构支持**：提供 `arm64-v8a` 与 `armeabi-v7a` 双架构原生支持。

## 功能列表与状态

| 功能 | 状态 |
|---|---|
| 音源代理（酷我/QQ/咪咕等） | ✅ 可用 |
| 下载解锁 | ✅ 可用 |
| 启动图 / APP 图标 | ✅ 可用 |
| 去广告 / 隐藏升级提示 | ✅ 可用 |
| 自动签到 / 每日歌曲打卡 | ✅ 可用 |
| 精简侧边栏（纯动态识别） | ✅ 可用 |
| 精简 Tab | ✅ 可用 |
| 评论区优先“最热” | ✅ 可用 |
| 播放页黑胶隐藏 / 停转 | ✅ 可用 |
| 音效 / 播放器样式 / 母带音质等本地 VIP | ⚠️ 未修复，仍需开通会员 |

> ⚠️ **本地 VIP 已知限制**：本版本本地 VIP 仅「下载 / 启动图 / APP 图标」生效，音效、播放器样式、母带音质等权益仍需开通网易云音乐会员，修复进展见后续版本。

## 使用说明

1. 安装本模块并在 LSPosed / Xposed 管理器中勾选「网易云音乐」。
2. **强制停止**网易云音乐后再打开。
3. 进入网易云音乐「设置」页即可看到模块设置入口。
4. 若脚本异常或需要更新规则，可在模块设置中点击「重新释放脚本」。
5. **精简侧边栏**：先打开一次网易云的侧边栏抽屉（自动识别条目），再到模块设置中勾选要隐藏的条目；如出现新的运营条目，可点击对话框中「确定」旁的「刷新条目列表」重新识别。

## 4.1.1 更新日志（20261002）

本版为 9.6.x 全量适配重构（32 个文件，+4577/−3316 行）：

- **评论区优先“最热”重做（v10.0）**：每首歌首屏默认最热、不物理调换 Tab 顺序、歌曲内可自由切换推荐/最新；按歌曲重置生效窗口，请求参数语义化（resType/limit/sortType/pageType）。
- **精简 Tab 重写（v8.0）**：仅保留“首页”与“我的”并均分宽度、冷启动默认“我的”、自由切换不回弹、选中加粗高亮；populate/默认页/页面分发/移除开屏全部 DexKit 结构特征匹配。
- **精简侧边栏重做**：条目清单 100% 运行时识别（抽屉视图扫描）并持久化，零硬编码条目名；数据级过滤 + 渲染入口 + 视图扫描三层隐藏，隐藏条目不可点击、无残留空洞；滚动自适应（不足一屏锁定、超一屏恢复）；关闭功能/取消勾选整体还原；设置对话框新增「刷新条目列表」与扫描状态提示。
- **本地黑胶/音效判定 DexKit 化**：动态定位“是否需要 VIP”判定族（杜比全景声/沉浸声/鲸云音效/超清母带）与模块总线派发入口；音效模型读写双侧清零（setAeVipType/setLimitTime 等）；`/eapi/batch` 子响应 VIP 标记清洗；收银台 pre-check 拦截。
- **网络层路由重构**：EAPI 拦截按职责分流（vipnewcenter / music-vip-configuration / cashier / link-position），正则化 VIP 标记清理，大响应窗口短路避免全量解析。
- **调试系统**：新增应用内日志查看器（LogViewerDialog）、DebugLogger 大幅增强；hook 注册异常隔离（单个失败不中断其余）；依赖 DexKit 的 hook 延迟到解析完成后安装（不卡启动）。
- **主题/播放页**：ThemeAgent/ThemeInfo/ThemeConfig/ResourceRouter 全部 DexKit 结构匹配；播放页黑胶隐藏/停转适配 9.6.x。
- **移除**：「解锁一起听蒙面查看权限」（目标类在 9.6.x 已不存在）、「跟随系统切换夜间模式」（应用自带）、「播放界面背景设置」体系（7 个文件）。
- **全量审计**：混淆类名硬编码清零（`ui.l`、`okhttp3.z` 等死分支移除），全部特征匹配走 DexKit 结构匹配。

[下载 Release](https://github.com/CiscoPacket/dolby_beta/releases/latest)

## 致谢

- [ITManCHINA/server](https://github.com/ITManCHINA/server)
- [Luckypray/DexKit](https://github.com/Luckypray/DexKit)
- [UnblockNeteaseMusic/server](https://github.com/UnblockNeteaseMusic/server)
- [nondanee/UnblockNeteaseMusic](https://github.com/nondanee/UnblockNeteaseMusic)
- [luoxingran/dolby_beta](https://github.com/luoxingran/dolby_beta)
- [nining377/dolby_beta](https://github.com/nining377/dolby_beta)
- [NikolaJyun/dolby_beta](https://github.com/NikolaJyun/dolby_beta)
