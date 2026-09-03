# 汽车滤芯管理（FilterMaster）

原生 Kotlin 安卓应用，用于录入和检索汽车滤芯信息：货品编码、OE码、车型、规格、胶圈、盒子、备注与照片。

## 功能

- 📝 录入滤芯：品牌（下拉选择：AD / 玖壳 / 默利森 / 金登 / 康信 / 外贸 / 海泽飞 / 诺富曼 / 滤之源 / 雷鼎）、货品编码、别称、OE码、车型、规格、位置、胶圈、盒子、备注
- 🔍 全字段实时搜索（含别称、位置）+ 按品牌筛选
- 📷 扫码识别条码/二维码：可填入 OE 码，也可直接作为搜索关键词
- 🖼️ 滤芯照片（拍照 / 相册），自动压缩存储
- 📋 一键复制 OE 码
- 🗄️ **备份与恢复**：本地备份到任意位置 / 从本地文件恢复；接入坚果云（WebDAV）实现云备份与云恢复
- 💾 数据离线存储在本机（SharedPreferences + 应用私有目录）

## 备份格式

备份文件是标准 ZIP，可直接用电脑解压查看：

```
FilterMaster_备份_20260903_1830.zip
├── data.json       全部记录（JSON）
└── images/         引用到的照片原文件
```

恢复时可选择「合并」（跳过重复项）或「覆盖」（清空后写入）。

## 坚果云配置

1. 登录坚果云网页版 → 右上角账户 → **账户信息** → **安全选项**
2. 点「添加应用」，名称随意（如 FilterMaster），生成一串**应用密码**
3. App 内点「🗄️ 备份与恢复」→「⚙️ 配置坚果云账号」，填入：
   - 服务器地址：`https://dav.jianguoyun.com/dav/`（默认已填）
   - 账户：坚果云注册邮箱
   - 应用密码：上一步生成的密码（**不是登录密码**）
   - 云端文件夹：默认 `FilterMaster`，会自动创建
4. 点「保存并测试」验证连通性

同样支持其它标准 WebDAV 服务（Nextcloud、群晖等），只需替换服务器地址。

> ⚠️ 应用密码保存在应用私有存储中。坚果云的应用密码可随时在网页端撤销，建议专门为本应用生成一个。

## 构建

### 方式一：GitHub Actions 自动构建

仓库已包含 `.github/workflows/android.yml`。把代码推送到 GitHub 后：

1. 进入仓库 **Actions** 页面
2. 选择 **Build APK** 工作流
3. 等待构建完成，在 Artifacts 中下载 `FilterMaster-debug-apk`

每次 push 到 main/master 分支都会自动构建。

### 方式二：本地构建

用 Android Studio 打开项目根目录，等待 Gradle Sync 完成后点击 Run 即可。
命令行方式：

```bash
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17。

## 技术栈

| 项目 | 说明 |
|---|---|
| 语言 | Kotlin |
| 最低支持 | Android 7.0 (API 24) |
| UI | Material Components + RecyclerView + BottomSheetDialog |
| 扫码 | zxing-android-embedded |
| 数据 | SharedPreferences (JSON)，图片存 filesDir/images |

## 目录结构

```
app/src/main/
├── java/com/filtermaster/app/
│   ├── MainActivity.kt    # 界面与交互逻辑
│   ├── FilterItem.kt      # 数据模型
│   ├── Brands.kt          # 品牌列表与徽章配色（增删品牌改这里）
│   ├── FilterStore.kt     # 本地持久化
│   ├── FilterAdapter.kt   # 列表适配器
│   ├── BackupUtil.kt      # 备份 ZIP 打包 / 解包
│   ├── WebDavClient.kt    # WebDAV 客户端（坚果云）
│   └── CloudPrefs.kt      # 云端账号配置存储
├── res/layout/            # 主界面 / 列表项 / 编辑与详情底部弹层
└── AndroidManifest.xml
```
