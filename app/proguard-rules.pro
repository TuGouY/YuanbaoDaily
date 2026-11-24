# ============================================================================
# YuanbaoDaily R8 保留规则
#
# release 已经打开 minifyEnabled + shrinkResources（见 app/build.gradle）。
# 这里的每一条都对应"有东西在运行期按**字符串名字**找类/方法"，
# 漏一条的表现通常是"编译通过、装上去静默不工作"，所以宁可写得明确。
#
# 反过来说：不在这份清单里的类，全部允许改名、内联、裁剪 —— 这才是混淆的意义。
# ============================================================================


# ----------------------------------------------------------------------------
# 1. Xposed / LSPosed 入口
#
# LSPosed 读 APK 里的 assets/xposed_init，拿到全限定名后反射实例化。
# 名字一旦被改成 a.b.c，框架就找不到入口 —— 表现为"模块已启用但完全不生效"，
# 而且日志里一条都不会有（框架在加载阶段就放弃了）。
# ----------------------------------------------------------------------------
-keep class me.codex.yuanbaodaily.hook.MainHook { *; }

# 框架按接口方法名回调；实现类的方法名必须保持与接口一致。
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }

# Xposed API 是 compileOnly：运行时由框架提供，R8 阶段看不到实现。
-dontwarn de.robv.android.xposed.**


# ----------------------------------------------------------------------------
# 2. 反射调用的第三方库
#
# PrivilegedAccess 用 getDeclaredMethod("newProcess", ...) 调 Shizuku 的私有静态方法
# （13.x 把它从 public 改成了 private，但签名一直没变）。R8 若把类或方法改名，
# 反射拿到 NoSuchMethodException，免 root 版的"写 settings 自愈"就静默失效。
# ----------------------------------------------------------------------------
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**


# ----------------------------------------------------------------------------
# 3. 系统按清单名字实例化的组件
#
# AGP 会依据合并后的清单自动生成 keep 规则，但这里显式再列一遍：
# 一是防止以后换构建方式/换插件时丢掉自动规则，二是让"哪些名字不能动"可读。
#
# AutomationAccessibilityService 尤其关键：无障碍授权在系统设置里存的就是
# "包名/完整类名"，类被改名后系统绑不上，用户会看到开关自动关闭。
# ----------------------------------------------------------------------------
-keep class me.codex.yuanbaodaily.automation.AutomationAccessibilityService { *; }
-keep class me.codex.yuanbaodaily.automation.AutomationCommandService { *; }
-keep class me.codex.yuanbaodaily.config.YuanbaoConfigProvider { *; }
-keep class me.codex.yuanbaodaily.task.DailyAlarmReceiver { *; }
-keep class me.codex.yuanbaodaily.task.KeepAliveService { *; }


# ----------------------------------------------------------------------------
# 4. 依赖库
#
# ML Kit / AndroidX / Shizuku 都自带 consumer ProGuard 规则（随 AAR 合并），
# 这里不重复 -keep：过度保留会把这些库的类名也固定下来，反而削弱混淆效果。
# 构建时若出现 "Missing class ..."，先判断是不是缺 -dontwarn，再决定是否 keep。
# ----------------------------------------------------------------------------


# ----------------------------------------------------------------------------
# 5. 调试信息
#
# 保留行号（用户抓日志时能定位到具体行），但把源文件名统一成 SourceFile，
# 不暴露原始 .kt 文件名。
# ----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
