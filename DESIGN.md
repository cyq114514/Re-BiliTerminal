# 界面规范（Re-BiliTerminal）

> 颜色、字号、圆角、间距一律沿用 `res/values/colors.xml` / `themes.xml` / 现有控件样式，不自己另定一套。

## 顶栏操作按钮

- **不要只放一个裸文字按钮**：用「图标 + 文字 + 圆角卡片」，30dp 高、单行、手表小屏不挤占标题（`pageName` 预留固定 `marginEnd`）。
  **Why:** 用户 2026-11-06 指出「推荐/动态页刷新按钮只放一个文字按钮」不符合项目本身的 UI 风格。
- **顶栏刷新按钮始终半透明灰卡片，和视频卡片一个风格**：复用 `@color/card_bg_new` / `@dimen/card_round_new`(10dp) / `@dimen/card_stroke_new` / `@color/card_border_new`，文字与图标用 `@color/text_gray`；**加载中不高亮、不切主题强调色**，只有按压时底色略实一点。
  **Why:** 用户 2026-11-06 第二次纠正：加载时变成高亮色很突兀；此前两次的「选中态走强调色」做法作废。
  **How to apply:** 顶栏操作按钮都按这个灰卡片语言；强调色留给内容里最重要的按钮。

- **跟自定义主题的强调色不要写死在 XML selector 里**（对其它需要强调色的控件仍成立）：`ThemeApplier.applyBackground()` 只换 `ColorDrawable` 和 `@drawable/background_badge` 的 `GradientDrawable`，对 `StateListDrawable` 不生效。凡选中态要显示强调色的控件，必须在 Java 里用 `ThemeManager.getPrimary()` 重建 drawable，并注册 `OnThemeChangedListener`（改色页 `setSeed()` 不重启进程）。

- **番剧分区所有独立页面统一「点标题栏返回」**，图标用 `arrow_back`。子页面用 `setTopbarExit()`（finish 回上一级）；**顶级主页 `BangumiActivity` 例外，用 `setMenuClick()` 打开导航菜单**——菜单跳转时会把自己 finish 掉，主页再 finish 就是直接退出应用。
  **Why:** 用户 2026-11-06 先要求所有页面点标题栏能返回，随后纠正「点返回要回到导航菜单那一屏」。
  **How to apply:** 新增番剧分区子页面一律 `setTopbarExit()`；共用布局不能改 XML 图标时，就只在该页代码里给 `pageName` 换箭头。顶级入口页不要照抄子页的 `finish()`。

## 字号（2026-11-07 排查后收敛）

- **角标/徽章/说明类小字 11sp 合法，正文仍 ≥12sp**：badge（如选集"会员"角标）、列表次级信息、"读一眼"的说明文字用 11sp；10sp 仅限卡片角标。**Why:** 2026-11-07 全项目排查：11sp 有 131 处、10sp 有 30 处，是第二/第五常用字号——规范要贴合现实，全量升字号在手表小屏有溢出风险，且大字号并不带来信息增量。

## 圆角、间距、次要文字（2026-11-14 视觉系统收敛）

- **圆角只有三档**：卡片/输入框/按钮 `@dimen/card_round_new`(10dp)、胶囊 chip `@dimen/radius_chip`(14dp)、角标 `@dimen/badge_round`(3dp)。
  不再出现 8dp / 12dp / 16dp 这类「同一语义换个值」的卡片圆角；`cell_up_avatar` 的 28dp（圆形头像）与播放器封面 16dp 属形状，不在此列。
  **Why:** 2026-11-14 全项目排查：布局里有 8dp×4、12dp×3、16dp×1 的 `cardCornerRadius`，同一列表里卡片圆角不一致。
- **卡片垂直间距 4dp**（`@dimen/list_margin_vertical`）。2dp 在暗底上两张卡几乎糊成一片。
- **次要文字只有三档 alpha**：0.9（描述/说明）、0.7（次级 meta，如播放量、UP 名）、0.5（弱提示/禁用）。
  不再写 0.55 / 0.75 / 0.78 / 0.85 / 0.86 / 0.88 这类中间值。
  **Why:** 2026-11-14 排查时全项目有 15 档 alpha，同一条「播放量+UP名」在视频卡是 0.7、用户卡是 0.86。
- **`@color/text_gray` 与 alpha 两条通道并存是既成事实**：文字色用 token、需要连同图标一起弱化时用 alpha。不要为了「统一」把 alpha 换成 textColor（会连带改 compound drawable 的图标颜色）。

## 渐变豁免（新版美学既有语言）

- **两个 drawable 的渐变/光晕豁免「不加渐变」条款**：`background_card_gradient_border`（卡片渐变描边）、`splash_text`（开屏光晕，与 AmbientBackground 同源）。**Why:** 2026-11-07 排查确认三者的文件注释都声明是有意的 Re-WearBili 视觉语言，已随新版美学上线。**新增 UI 不得新造渐变/发光**，需要强调时用强调色实底或既有描边 token（`card_border_new` 等）。

## 主菜单圆形按钮

- **与视频卡片同一套 token**：`background_menu_button`（cell_menu_grid 的圆形图标底）用 `card_bg_new` 半透明灰底 + `card_border_new` 0.5dp 描边，与选集卡片、顶栏刷新胶囊统一。**Why:** 用户 2026-11-07 先反馈灰底渐变描边「灰蒙蒙」，白描边方案后最终定稿为视频卡片统一 token——圆形/方形只是形状差异，卡片语言只有一套。

## 通用清单

- 图标只用一套风格（项目现有 `icon_*.xml` 为 filled vector），正文/按钮里不用 emoji，不手画复杂 vector 路径。
- 层级靠字重、颜色、间距拉开，不靠不断加大字号；间距沿用现有 chip 的 6dp / 10dp / 12dp，不另造 13dp、17dp 这类手调值。
- 硬编码文案进 `strings.xml`；同一件事只说一遍，标题上面的装饰小字一律删。
- 不加渐变、发光描边、毛玻璃；分组先靠留白，其次底色，最后分割线。
