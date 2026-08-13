# 杜比大喇叭β版

[![Stars](https://img.shields.io/github/stars/NikolaJyun/dolby_beta?label=Stars)](https://github.com/NikolaJyun/dolby_beta)
[![Release](https://img.shields.io/github/v/release/NikolaJyun/dolby_beta?label=Release)](https://github.com/NikolaJyun/dolby_beta/releases/latest)
[![Download](https://img.shields.io/github/downloads/NikolaJyun/dolby_beta/total)](https://github.com/NikolaJyun/dolby_beta/releases/latest)
[![License](https://img.shields.io/github/license/NikolaJyun/dolby_beta?label=License)](https://choosealicense.com/licenses/mit/)

#### 杜比大喇叭β是一款网易云音乐的音源代理模块，初衷只是我对网易云音乐的热爱，希望让更多的人使用网易云。
#### 模块工作原理为音源替换而非破解，所以单曲付费与无版权歌曲有几率匹配错误，真心支持歌手请付费！
#### 模块工作原理为音源替换而非破解，所以单曲付费与无版权歌曲有几率匹配错误，真心支持歌手请付费！
#### 模块工作原理为音源替换而非破解，所以单曲付费与无版权歌曲有几率匹配错误，真心支持歌手请付费！
#### 重要事情得说三遍，不要再因为不匹配而提交issue了。

当前版本 **4.1.0**（本仓库为 [luoxingran/dolby_beta](https://github.com/luoxingran/dolby_beta) 的 fork，原作为 [nining377/dolby_beta](https://github.com/nining377/dolby_beta)）。

杜比大喇叭β 4.1.0：
-   网易云 9.5+ RN 设置页可进入模块设置（「账号与安全」与「播放与下载」之间独立卡片，跟随「通用 → 显示 → 深色模式」）
-   音源代理增加 UnblockNeteaseMusic `ENABLE_LOCAL_VIP`（脚本本地 VIP，与模块「本地黑胶」不同）
-   内嵌 [UnblockNeteaseMusic/server](https://github.com/UnblockNeteaseMusic/server) `enhanced` 分支最新脚本

杜比大喇叭β 4.X 相对 3.X：
-   编译版本提高至29并采用AndroidX API
-   模块设置嵌入网易云「设置」页，最新版 RN 设置中心也可进入
-   内嵌 [UnblockNeteaseMusic/server](https://github.com/UnblockNeteaseMusic/server) `enhanced` 分支
-   由于Android R对可执行文件的进一步限制，摒弃了2.X手动选择脚本与Node的方式
-   因本人不使用太极，所以不保证太极等非root框架可以顺利运行，如非必要请使用内嵌版本

*网易云在7.X版本后对非会员暗降了音质，由标准128K、较高192K、极高320K降低为标准96K、较高128K、极高256K，导致匹配逻辑出现问题，鉴于后续版本都在添加无用功能，推荐只听音乐的云村居民使用431或者600版本以获得最佳体验。*

[快帮我点小星星呀，我要好多好多的小星星！](https://github.com/NikolaJyun/dolby_beta)

## 下载方式

[GitHub Release](https://github.com/NikolaJyun/dolby_beta/releases/latest)

[杜比大喇叭β版](https://wwi.lanzoui.com/b0cqxgwje) 访问密码：brdb

[网易云音乐模块内嵌版①](https://wwu.lanzouw.com/b0crkhyzg) 访问密码：3qvw

[网易云音乐模块内嵌版②](https://www.123pan.com/s/8qHrVv-hk1r) 访问密码：Wp2p

[网易云音乐模块内嵌版制作教程](https://github.com/nining377/dolby_beta/issues/142)

## 内嵌 UnblockNeteaseMusic 脚本

当前内嵌 [UnblockNeteaseMusic/server](https://github.com/UnblockNeteaseMusic/server) 的 `enhanced` 分支最新代码（v0.28.0，commit `c29ff1b`，2026-08-11）。  
脚本包位于 `app/src/main/assets/UnblockNeteaseMusic.zip`，版本信息见 `UnblockNeteaseMusic.version`。CI 构建时会再次拉取该仓库并覆盖 zip。

安装/更新后若脚本未刷新，请在模块设置中使用「重新释放脚本」。

## TODO

-   尽可能保证不因模块造成crash（改版网易云往往经过二次混淆，将得不到适配）
-   ~~部分美化功能的添加~~（已完成）
-   ~~更稳定的代理方式~~（已完成）

## 主要功能

<img src="https://raw.githubusercontent.com/nining377/dolby_beta/master/image/img_01.png" width="50%">

## 致谢

[nondanee/UnblockNeteaseMusic](https://github.com/nondanee/UnblockNeteaseMusic)

[Unblock Netease Music 维护小组](https://github.com/UnblockNeteaseMusic/server)

[bin456789/Unblock163MusicClient-Xposed](https://github.com/bin456789/Unblock163MusicClient-Xposed)

[Flysky12138/UnblockNeteaseMusic-Android](https://github.com/Flysky12138/UnblockNeteaseMusic-Android)

[luoxingran/dolby_beta](https://github.com/luoxingran/dolby_beta)

[nining377/dolby_beta](https://github.com/nining377/dolby_beta)

## 许可

The MIT License
