# Blink 内网测试服务

成都 ECS 的 API 仅监听回环地址 `127.0.0.1:18765`。数据保存在 `/var/lib/blink-staging/blink.sqlite3`。安卓内测版的账号入口在「日程 → 账号与同步」。首次注册或登录时可以选择合并游客记录，默认保留在游客空间。同步令牌由 Android Keystore 保护。

## 安卓连接

在已授权的 Mac 上启动隧道，另开终端为测试机设置 USB 转发。每次重新连接设备后检查转发。Mac 出口 IP 变化需要更新单地址安全组规则。

```sh
BLINK_SSH_HOST=<测试实例公网地址> bash scripts/android-tunnel.sh
adb -s <测试机序列号> reverse tcp:18765 tcp:18765
curl -fsS http://127.0.0.1:18765/healthz
```

测试包和一期包可并存。旧版调试包保持前台时，执行 `node scripts/export-android-guest.mjs DEVICE NEW_BACKUP.json`。用 Android 文件选择器从内测版「导入备份」恢复到游客空间。备份含个人记录，应保存在自己的设备上。测试版覆盖升级使用同一测试签名和递增版本号。

## 协议

| 接口 | 行为 |
| --- | --- |
| POST /v1/register、/v1/login | 接收 name、password 和可选 device、label。返回账号 UUID、会话和到期时间。首次注册同时返回一次性恢复码。 |
| POST /v1/sync | 上传最多 200 条 UUID 打卡或撤销，按游标拉取。撤销后重传旧打卡仍保持撤销。 |
| POST /v2/sync | 原子更新固定作息，逐项同步频率、临时安排、当班例外、历史基准、每日修正及设备用眼区间。对象带版本和变更 ID，重叠时段取并集。 |
| POST /v2/devices、/v2/revoke、/v1/logout | 查看当前会话、撤销指定设备、退出本机。 |
| POST /v2/password、/v2/recovery-code、/v2/reset、/v2/delete | 修改密码、轮换恢复码、使用恢复码重设密码和注销账号。 |

认证采用 Bearer 会话，期限 30 天。记录和对象分别保存游标。恢复数据库时变更 epoch 并撤销历史会话，客户端重新登录后对账。

## 发布与恢复

推送到 main 后，服务端工作流对接口运行集成测试，签名提交 SHA 和递增发布编号。ECS 每两分钟取回签名，核验后切换版本。进程健康及未认证请求应分别返回 200 和 401，失败则切回上一版程序。数据库恢复单独进行。测试 Android 工作流由开发者触发，发布地址在仓库的 Releases 页面。

```sh
python3 -B -m unittest discover -s server -p 'test_*.py'
npm test
server/ops/snapshot.sh backup DATABASE PUBLIC_CERT NEW_FILE.cms
server/ops/snapshot.sh restore FILE.cms PRIVATE_KEY NEW_DATABASE
```

恢复工具只创建新文件，可在隔离目录先核对账号、打卡、撤销、游标和区间。恢复的数据库会获得新代次，原会话失效。将正式环境接入前，需独立配置域名、HTTPS、正式签名和备份位置。
