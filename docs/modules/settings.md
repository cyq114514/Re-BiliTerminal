# 设置与主题（SettingMainActivity / SettingsAdapter / 主题 / SP 兼容）

> 什么时候读：新增或修改设置项、设置页、设置搜索、强调色/主题、界面开关，或排查"设置不生效/升级后设置丢失/设置页闪退/搜不到设置"时。
> 路径约定：Java 路径省略前缀 `app/src/main/java/com/RobinNotBad/BiliClient/`；清单指 `app/src/main/AndroidManifest.xml`。

## 先读

| 文件 | 关键位置 | 为什么先读 |
| --- | --- | --- |
| `activity/settings/SettingMainActivity.java` | 类 `:22`、`onCreate:30`、搜索入口 `:36-39`、卡片入口 `:66-190` | 设置中心只是"卡片 + Intent"，不含任何设置值；新页面要么加卡片，要么挂到已有页 |
| `adapter/SettingsAdapter.java` | `typeMap:40-58`、`getItemViewType:66-69`、`ChooseHolder:208`（`bindMulti:290`）、`InputHolder:332`、`OpenHolder:430-456` | 列表型设置的渲染与写值全部按 **`type` 字符串**分派；`choose` 的存储类型由选项个数决定 |
| `model/SettingSection.java` | `:3-21`（`id` 的两种语义） | 普通类型 `id` = SP key；`open` 类型 `id` = 目标 Activity 全类名 |
| `activity/settings/SettingSearchActivity.java` | `EXTRA_SCROLL_TITLE:40`、`AUTO_PAGES:155-167`、`buildIndex:171-189`、`Class.forName:184` | 搜索索引 = 列表页 `buildSections` 自动生成 + 自绘页手写；漏注册只"搜不到"不崩 |
| `util/SharedPreferencesUtil.java`（176 行） | key 常量 `:10-75`、登录态 `:77-90`、静态实例 `:92`、类型兜底 `:100-120`、`putStringSync:134` | 全项目 SP 入口；类型对不上靠 `getString` 的 CCE 兜底，只是保险丝不是设计 |
| `ui/theme/ThemeManager.java` | `KEY_SEED:40`、`DEFAULT_*:43-52`、派生色 `:70-110`、`setSeed:158`、弱引用监听 `:77` | 种子色 → HCT 派生；默认种子走硬编码快路径 |
| `ui/theme/ThemeApplier.java` | `apply:61-76`、白名单判定 `:32-45`、开关 tint `:85,216` | 递归换色只认"精确值 + 白名单类型"，不是全局无脑替换 |
| `activity/base/BaseActivity.java` | Classic 覆盖层 `:85-90`、顶栏渐隐 `:472-473` | 旧 UI 兼容层与 `titlebar_fade` 的挂载点 |
| `activity/settings/SettingTerminalPlayerActivity.java` | `buildSections:43`、`scrollToSettingTitle:31`（调用 `RefreshListActivity:140`） | 列表型设置页范式（静态清单，供搜索复用） |

## 允许改动

- 新增设置项/二级设置页、调整分组与文案、调整默认值（新 key）与描述。
- 列表型页面拆分/合并（保留静态 `buildSections` 供搜索索引）。
- 主题派生算法之外的表现层：`ThemeApplier` 白名单内新增控件类型、补充 `OnThemeChangedListener` 重建 drawable。
- 界面设置页的自绘控件（圆屏/DPI/间距），前提是走 `SharedPreferencesUtil` 且 key 不变。

## 禁止 / 慎改（红线）

1. **改已有 SP key 的名字或类型**：升级用户的值读不出来 → 设置"自己回到默认"，或抛 `ClassCastException`（`getString` 兜底只覆盖 string 读数字/布尔，反向不兜底）。
2. **`type` 字符串与 `typeMap`**（`:40-58`）：`getItemViewType` 直接对 `get()` 结果拆箱，未登记的 type 会 NPE（注释已写明）；新增 type 必须同时补 `onCreateViewHolder`/`onBindViewHolder` 分支。
3. **`choose` 的存储语义**（`:208-330`）：两选项存 **boolean**；三选项及以上存 **int 下标**（`defaultValue` 即默认下标）。改选项数量/顺序 = 老用户已存值被解读成另一项；读侧必须同样按下标（例：`SegmentSkipMode.fromIndex`）。
4. **`open` 类型的 `id`**（`:430-456`，`Class.forName`）：存的是全类名。重构包名/类名 = 设置入口静默失效（只弹"无法打开"）。
5. **`ThemeManager.DEFAULT_SEED / DEFAULT_PRIMARY*` 常量**（`:43-52`）：必须逐字不变，默认主题靠硬编码快路径返回这些常量；改一个字节，所有未自定义主题的用户配色都会变（设计依据 `../decisions-theme-color.md`）。
6. **`ThemeApplier` 的白名单外扩**（`:32-45`）：把品牌蓝/语义色/旧粉当强调色替换，会让状态色与按钮语义混淆。
7. **开关默认值**：`titlebar_fade`（`BaseActivity:472`，默认 true）、`NEW_UI_DESIGN`（`:49`，默认 true）、`ui_disable_animation`（`:63`）等默认值一改，全站观感变化且无法回退。
8. **设置页里顺手改 DB**：`download.db` 版本/列语义不属设置页范围（见 `download.md`）。

## 必查回归清单（按波及面排序）

- [ ] 覆盖升级：旧版本 → 新版本（保留 SP/DB），逐项确认已有设置值仍在；全新安装默认值正确。
- [ ] 搜索链路：新设置项能搜到 → 点开 → 目标页滚到该行（`EXTRA_SCROLL_TITLE` → `RefreshListActivity:140`）。
- [ ] `type` 渲染全覆盖：`switch` / `switch_warn` / `choose`（2 项与 ≥3 项各一）/ `input_int` / `input_float` / `input_string` / `open` / `title` / `divider`。
- [ ] 主题三态：默认种子（快路径）/ 手选种子 / 背景图取色；关闭"新版美学设计"回 Classic 覆盖层（`BaseActivity:85-90`），播放器等 NoSwipe 页不受影响。
- [ ] `titlebar_fade` 开/关；界面设置（界面大小、左右/上下留白、强制 DPI、圆屏适配）。
- [ ] 设置搜索搜不到描述文字是现状（`applyFilter` 只匹配标题与页名）——若改成搜 desc，需确认索引规模与噪声。
- [ ] 输入框写入时机：每次字符变化即写 SP（无防抖），`input_float` 中途解析失败被忽略（`:363-380`）。
- [ ] 圆屏/DPI/深色背景下的设置页可读性（字号 ≥12sp、圆角 10/14dp，见 `../../DESIGN.md`）。

## 新增一个设置项的 4 处（缺一处即"能用但搜不到/进不去"）

1. **列表**：目标页的 `buildSections(Context)` 加 `new SettingSection(type, 标题, id/key, 描述, 默认值[, extra])`。
2. **搜索**：列表型页只需在 `AUTO_PAGES`（`SettingSearchActivity:155-167`）注册过页面即自动入索引；**自绘页（`SettingUIActivity` 等）必须手写 `add(...)` 条目**（`buildIndex:171-189` 之后）。新页面要加一行 `AutoPage`。
3. **SP key**：新 key 常量进 `SharedPreferencesUtil:10-75`；类型（boolean/int/float/string）一旦落库不可更改。
4. **Manifest**：新建 Activity 必须注册（当前清单中 `.activity.settings.*` 共 30 处）；另需确认 `SettingMainActivity` 有卡片入口，否则用户到不了。

## 常见坑

- 1.2.9：菜单设置曾在 `onDestroy` 才落库，杀进程丢改动且加载期退出 NPE → 改"切换即落库"。
- 1.3.0：SP 类型混用（`input_float` 写 float、`getString` 读）→ 横滑灵敏度必崩；修法是读侧 `getFloat` + `getString` CCE 保险丝（`:100-120`）。
- 1.2.8 ANR：Activity 全销毁时主线程 `commit()` 同步写盘，`SettingMainActivity` 5 秒超时 → 只有"写完就要杀进程"才用 `putStringSync:134`，其余一律 `apply()`。
- 主题切换后 `StateListDrawable` 不会被换色：选中态必须 Java 侧重建并注册 `OnThemeChangedListener`（`ThemeApplier:32-45`）。
- 页面跳转用 `Class.forName` 的地方有两处（`SettingsAdapter:450`、`SettingSearchActivity:184`），改类名要一起搜。

## 相关文档

- `../../DESIGN.md`（颜色/字号/圆角/间距规范）、`../decisions-theme-color.md`（主题派生决策）
- `../../app/AGENTS.md` §1（界面约束）与 §5（设置项 4 处）、`../更新日志.md`（1.2.9 / 1.3.0 / 1.2.8 设置相关根因）
- `../变更记录-UI视觉系统升级-20261114.md`（UI 收敛与 `titlebar_fade` 全局化）