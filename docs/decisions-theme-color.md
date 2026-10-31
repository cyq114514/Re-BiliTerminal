# 主题配色（新版美学强调色）决策记录

> 本文件记录「主题色自定义」功能的已定结论与理由，供后续维护者判断边界。
> 每条结论都附证据或理由；改动前请先读「不可越过的边界」。

## 背景

Re-BiliTerminal 的新版美学设计（`SharedPreferencesUtil.NEW_UI_DESIGN`）使用固定强调色
`#FE679A`。本次开放其配色自定义能力，**只改变配色，不改变任何 UI 结构**。

## 不可越过的边界

| 结论 | 理由 |
|---|---|
| 只动态化数值等于 `#FE679A` 的新版强调色体系（含其派生 `#1AE75688`、`#59FE679A`） | 只有"默认值完全相等"才能保证默认主题下逐像素等价；旧版遗留粉 `#FB7299`/`#FB8799` 数值不同，动态化会让默认主题的视觉从 `FB7299` 变成 `FE679A`，直接违反"默认视觉与原版一致" |
| `#FB7299` 系列（播放器进度条、选集 tab、筛选 chip、评论定位动画等）保持固定 | 同上 |
| 品牌色（`bili_blue`/`link`/B 站返回的昵称色）、语义色（`color_danger`/未读角标 `#CF4B5F`）保持固定 | 与强调色无关，按用途判断 |
| 不改布局结构、字号、圆角、间距、描边宽度、阴影、动画 | 本任务只换颜色来源 |
| 不改 `versionName` / `versionCode` | 发版由用户决定 |

## 已定结论

### 1. 动态化范围：只认 `#FE679A`（2026-10-31）

结论：仅把"值等于新版强调色"的引用动态化，旧版 `#FB7299` 系列全部保留。

理由：默认主题必须与原版逐像素一致，这是最高优先级验收条件。`FB7299` 与 `FE679A` 数值不同，
一旦跟随主题，默认主题下这些位置的观感就会变化。
代价：用户换主题后这些位置仍显示旧粉 —— 已写入交付报告的「已知限制」。

### 2. 默认主题走硬编码快路径（2026-10-31）

结论：`seed == 0xFFFE679A` 时 `ThemeManager` 直接返回原始常量表
（`0xFFFE679A` / `0x1AE75688` / `0xFFFE679A` / `0xFFFE679A`），完全不经过 HCT。

理由：`#1AE75688` 并不是 `#FE679A` 的 10% alpha（前者是更暗的 `E75688`），任何"按公式生成"的
算法都会让默认高亮卡片底发生变化。硬编码表让"默认一致"成为编译期保证，而不是"算得接近"。

### 3. 算法：只用 `Hct`，不用 `Scheme`/`DynamicColor`（2026-10-31）

结论：`material:1.9.0` 已内含 `com.google.android.material.color.utilities`（43 个类），
但 `DynamicColor`/`Scheme` 的字节码引用 `java.util.function.*`（API 24+），本工程
minSdk 19 且未开 core library desugaring，用了必崩。因此只调用 `Hct.fromInt/from/toInt`，
并包 `try/catch (Throwable)` 兜底到 HSV 近似。

证据：`javap -c DynamicColor.class | grep java/util/function` → `BiFunction`/`Function`；
`Hct`/`HctSolver`/`Cam16`/`ColorUtils`/`MathUtils` 的常量池无 `java.util/function|time|nio`。

代价：不新增任何依赖；release 包只多保留被引用的几个 HCT 类。

### 4. 低明度种子自动提亮（2026-10-31，用户确认）

结论：种子色的 HCT tone `< 50` 时，强调色自动提到 tone `55`；`>= 50` 原样使用。

理由：黑底上 tone 过低的颜色几乎不可见，用户输入 `#221133` 会得到"没有强调色"的界面。
这是为可读性做的降级，属于行为变更，已由用户确认。

### 5. 生效方式：重进页面生效，不重启进程（2026-10-31，用户确认）

结论：`ThemeManager.setSeed()` 立即写盘并通知已注册的自绘组件；Activity 布局在下次进入时生效。
设置页只提示"其他页面重进后生效"，预览页可立即看到效果。

理由：为了试色反复重启进程代价太高（每次都要走冷启动恢复链），而"新版美学开关"需要重启是
因为它要换整套主题 overlay，性质不同。

### 6. 设置入口：独立子页（2026-10-31，用户确认）

结论：新增 `activity/settings/ThemeColorActivity`，从「设置 → 界面」进入，不把色块塞进现有设置页。

理由：界面设置页已有 6 张卡片，再塞 10 个色块 + HEX 输入会显著拉长；独立页可复用现有卡片语言。

### 7. XML 适配方式：R1（显式应用器）+ 精确值守卫（2026-10-31，用户批准）

结论：布局里的 `@color/bili_pink` **保持不动**，作为默认兜底；`ThemeApplier.apply(root)` 在
`BaseActivity` 的 `setContentView` 之后做一次语义应用，只处理审计过的 A 类控件类型，
且仅当「当前色 == 默认强调色」时才替换；默认主题下整体直接 return，零开销。

理由：零 XML 改动、可回滚（删一次调用）、默认零差异。
代价：覆盖面限于白名单；Adapter 行内布局、对话框、Fragment 需要在其创建/绑定处显式调用。

### 8. 关掉新版美学时强制回到原版取色（2026-10-31，实施中补充）

结论：`ThemeManager` 的所有取色接口在 `NEW_UI_DESIGN == false` 时一律返回原版常量，
`isDefault()` 也返回 true（应用器随之整体跳过）。

理由：经典样式本来就不属于新版强调色体系。若不做这层闸门，一个关掉新版美学、又选过主题色的用户，
会在经典页面看到粉色被替换成别的颜色——属于"经典样式被意外影响"。闸门放在 `ThemeManager` 的单点上，
调用方（适配器/组件）不需要各自判断。

### 9. 预设色不选会被自动提亮的高彩度暗色（2026-10-31，实施中补充）

结论：紫色预设用 `#9E86FF`（HCT tone 63）而不是 `#7C4DFF`（tone 48）。

理由：`#7C4DFF` 会命中"tone < 50 自动提到 55"的规则，被改成 `#8D69FF`，用户点了预设却发现
颜色变了。预设必须是所见即所得。实测数据见交付报告「测试结果」里的 HCT 输出。

### 10. 调色盘：自绘 HSV + 拖动不落盘（2026-10-31，用户批准 P1）

结论：新增自绘控件 `ui/widget/ColorPickerView`（SV 面板 + 色相条），不引第三方 UI 库；
拖动只改本页预览与 HEX 文本，**点「应用」才写主题**；不做吸管取色。

理由：
- 只用到 `Canvas`/`Paint`/`LinearGradient`，minSdk 19 全兼容；项目已有自绘先例（`HighEnergyProgressBar`/`BatteryView`）；
- 拖动中写 SharedPreferences 没有意义（已渲染的页面本来就不会跟着变），还容易误触；
- 外层 `RotaryScrollView` 是 ScrollView 子类，会抢竖向拖动，所以控件在 `ACTION_DOWN` 时
  `requestDisallowInterceptTouchEvent(true)`，抬手/取消时释放——这是必须保留的实现细节；
- 吸管取色需要悬浮窗权限 + 截图通道，成本远大于收益。

配套：`BiliCardView.setHighlightColorsForPreview(bg, border)` 仅供预览页临时落色（会取消入场动画），
点应用后由 `ThemeManager` 回调覆盖回真实值；其他页面不用这个接口。

## 待定 / 已知限制

- `res/drawable/splash_text.xml` 的首帧渐变由 manifest 主题在 `onCreate` 前绘制，**无法**按设置切换，保持默认粉。
- API < 21 的部分 tint 无法回改（`ProgressBar` 进度色、部分 backgroundTint），已按可用 API 做降级。
- 手表（W527）与低配机型的实际流畅度未验证。
