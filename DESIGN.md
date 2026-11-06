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

## 通用清单

- 图标只用一套风格（项目现有 `icon_*.xml` 为 filled vector），正文/按钮里不用 emoji，不手画复杂 vector 路径。
- 层级靠字重、颜色、间距拉开，不靠不断加大字号；间距沿用现有 chip 的 6dp / 10dp / 12dp，不另造 13dp、17dp 这类手调值。
- 硬编码文案进 `strings.xml`；同一件事只说一遍，标题上面的装饰小字一律删。
- 不加渐变、发光描边、毛玻璃；分组先靠留白，其次底色，最后分割线。
