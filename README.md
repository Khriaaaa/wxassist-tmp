# wxassist 安卓版 v0.1（临时仓库）

四模块完整 App：微信通知采集（NLS）+ 名单命中 + 手机直连 LLM 生成候选 + heads-up 建议通知/历史页
无 Gradle：aapt2 → javac → d8 → zipalign → apksigner

## 目录
- `/tmp/wxapp 同步Karlin` 投放工程：源码 + build.sh
- `wxassist.apk`：已签名 debug 包，可直接侧载

## 装机
1. 侧载安装 wxassist.apk
2. 长按图标 → 应用信息 → 允许受限设置（Android 13+）
3. 开通知使用权
4. 填 API 配置 → 测链路 → 名单加上 zzy 和「自测联系人」→ 自测通知
