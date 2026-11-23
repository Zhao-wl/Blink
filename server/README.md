# Blink 内网测试 API

服务监听 `127.0.0.1:18765`。systemd 使用动态用户，SQLite 数据存放在 `/var/lib/blink-staging`。此阶段仅供服务器本机或受控隧道访问。

注册和登录分别向 `/v1/register`、`/v1/login` POST JSON 对象，字段为 `name`、`password`。响应中的 token 用于后续请求的 Authorization Bearer 头，30 天过期。POST 空对象到 `/v1/logout` 会注销当前会话。

向 `/v1/sync` POST 包含 `records` 和 `cursor` 的对象。首次游标为 0。每次最多提交 200 条打卡，格式沿用客户端现有的 UUID、种类和毫秒时间戳，如 `{"id":"…","k":2,"at":1795400000000}`。撤销使用同一 UUID 和 `"deleted":true`。所有写入按账号隔离，整批校验失败时回滚。相同 UUID 重传时合并，已撤销记录保持撤销。不同 UUID 视为两次独立打卡。

响应返回本页更改和新游标。客户端应在本地持久化更改后保存游标，`more` 为真时使用新游标继续拉取。当前接口覆盖远眺、离席和饮水打卡。用眼累计、作息偏好和客户端账号界面待接入。

运行 `python3 -B server/test_api.py` 可在独立的临时数据库中验证接口。升级已有测试服务时，在服务器上校验指定版本文件后执行 `bash server/deploy-staging.sh`。脚本先运行测试，再更新服务，健康检查失败时恢复旧程序和服务配置。旧版的 health.py 保留以支持首次升级回退。
