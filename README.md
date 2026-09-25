# 杜比大喇叭β版

[![Release](https://img.shields.io/github/v/release/CiscoPacket/dolby_beta?label=Release)](https://github.com/CiscoPacket/dolby_beta/releases/latest)
[![License](https://img.shields.io/github/license/CiscoPacket/dolby_beta?label=License)](https://choosealicense.com/licenses/mit/)

网易云音乐音源代理模块。工作原理为**音源替换而非破解**，单曲付费与无版权歌曲有几率匹配错误，真心支持歌手请付费。

当前版本 **4.1.1**。

## 特性

- **动态全版本兼容**：引入 [DexKit](https://github.com/Luckypray/DexKit) 动态特征匹配引擎，自动识别网易云音乐混淆类与关键方法，摆脱对特定版本的硬编码依赖，支持网易云音乐全版本（已在 9.5.70 ~ 9.6.05+ 等多版本全量验证）。
- **智能缓存与自愈**：首次检索特征后将类名持久化到本地缓存，二次启动近乎零耗时；若网易云更新导致旧类加载失败，模块将自动失效缓存并启动自愈扫描。
- **全新 XEAPI / EAPI 协议适配**：内嵌 [ITManCHINA/server](https://github.com/ITManCHINA/server) `feat/adapt-xeapi` 分支最新脚本，全面适配网易云音乐新版加密协议。
- **原生双架构支持**：提供 `arm64-v8a` 与 `armeabi-v7a` 双架构原生支持。

## 使用说明

1. 安装本模块并在 LSPosed / Xposed 管理器中勾选「网易云音乐」。
2. **强制停止**网易云音乐后再打开。
3. 进入网易云音乐「设置」页即可看到模块设置入口。
4. 若脚本异常或需要更新规则，可在模块设置中点击「重新释放脚本」。

## 4.1.1 更新日志

- **全版本自适应**：通过 DexKit 实现对核心网络拦截器、响应体解析器、下载校验逻辑、侧边栏设置入口及广告过滤类的动态特征匹配。
- **启动性能优化**：实现三层类查找机制（SharedPreferences 缓存 -> 快速启发式加载 -> DexKit 动态扫描），大幅降低冷启动开销。
- **协议升级**：内置解封脚本同步更新至 `ITManCHINA/server:feat/adapt-xeapi`，全面适配网易云新版 XEAPI。
- **稳定性增强**：针对高低版本优化「一起听」与「隐藏 Tab」等钩子的容错检查，杜绝因组件类缺失导致的崩溃。
- **包体精简**：删除打赏界面与冗余图片资源，减小模块体积。

[下载 Release](https://github.com/CiscoPacket/dolby_beta/releases/latest)

## 致谢

- [ITManCHINA/server](https://github.com/ITManCHINA/server)
- [Luckypray/DexKit](https://github.com/Luckypray/DexKit)
- [UnblockNeteaseMusic/server](https://github.com/UnblockNeteaseMusic/server)
- [nondanee/UnblockNeteaseMusic](https://github.com/nondanee/UnblockNeteaseMusic)
- [luoxingran/dolby_beta](https://github.com/luoxingran/dolby_beta)
- [nining377/dolby_beta](https://github.com/nining377/dolby_beta)
- [NikolaJyun/dolby_beta](https://github.com/NikolaJyun/dolby_beta)
