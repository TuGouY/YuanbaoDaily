package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商安全提示处理策略的测试。
 *
 * 这层的容错空间是**零**：判断错的后果不是"功能不好用"，而是
 * **把无障碍关掉** —— 而那正是免 root 版唯一的执行能力。
 * 所以每条断言都在锁"敢不敢点"，而不是"能不能点"。
 */
class SystemGuardPolicyTest {

    // --------------------------- 包名识别 ---------------------------

    @Test
    fun recognisesVendorSecurityCenters() {
        listOf(
            "com.oplus.safecenter",           // ColorOS（本次真机就是它）
            "com.coloros.safecenter",
            "com.oplus.securitypermission",   // ColorOS 权限弹窗
            "com.miui.securitycenter",        // 小米
            "com.vivo.permissionmanager",     // vivo
            "com.iqoo.secure",                // iQOO
            "com.huawei.systemmanager",       // 华为
            "com.hihonor.systemmanager",      // 荣耀
            "com.samsung.android.lool",       // 三星
            "com.meizu.safe",                 // 魅族
        ).forEach { pkg ->
            assertTrue("$pkg 应该被识别为安全中心", SystemGuardPolicy.isGuardPackage(pkg))
        }
    }

    /** 同厂商的带后缀包名也要命中（版本之间会变）。 */
    @Test
    fun recognisesPackageWithSuffix() {
        assertTrue(SystemGuardPolicy.isGuardPackage("com.miui.securitycenter.remote"))
    }

    /**
     * **普通应用绝不能被认成安全中心。**
     *
     * 这一条是防止"把别人的界面当成弹窗去点"的最后一道闸：
     * 一旦误认，`dismissGuardResult` 就会在元宝/微信里找"保持开启"并点下去。
     */
    @Test
    fun doesNotRecogniseNormalApps() {
        listOf(
            "com.tencent.hunyuan.app.chat",   // 元宝本体
            "com.tencent.mm",                 // 微信
            "com.android.launcher",
            "me.codex.yuanbaodaily.lite",     // 我们自己
            "",
        ).forEach { pkg ->
            assertFalse("$pkg 不应被识别为安全中心", SystemGuardPolicy.isGuardPackage(pkg))
        }
    }

    // --------------------------- 按钮文案 ---------------------------

    /** 只有明确的"保留"语义才允许点。 */
    @Test
    fun acceptsExplicitKeepLabels() {
        listOf("保持开启", "保持使用", "保持允许", "继续使用", "保持").forEach { label ->
            assertTrue("$label 应被允许点击", SystemGuardPolicy.isKeepLabel(label))
        }
    }

    /**
     * **默认主按钮「关闭无障碍」必须被拒绝。**
     *
     * 这是整个类存在的理由：那个按钮是系统高亮的主按钮，
     * 任何"点主按钮 / 点第一个按钮 / 点坐标"的兜底都会把它点掉。
     */
    @Test
    fun rejectsTheDangerousDefaultButton() {
        listOf("关闭无障碍", "关闭", "取消", "停用", "禁用").forEach { label ->
            assertFalse("$label 绝不能被当成保留按钮", SystemGuardPolicy.isKeepLabel(label))
        }
    }

    /** 近似但不相等的文案也不能点（`trim` 之后必须完全相同）。 */
    @Test
    fun requiresExactMatch() {
        listOf(
            "保持开启（推荐）",
            "点击保持开启",
            "保持开启可以继续使用",
            "关闭无障碍 保持开启",
        ).forEach { label ->
            assertFalse("$label 不是精确匹配，不应点击", SystemGuardPolicy.isKeepLabel(label))
        }
    }

    /** 空白不点。 */
    @Test
    fun ignoresBlank() {
        assertFalse(SystemGuardPolicy.isKeepLabel(""))
        assertFalse(SystemGuardPolicy.isKeepLabel("   "))
    }

    // --------------------------- 危险词复核 ---------------------------

    @Test
    fun flagsDangerousWords() {
        listOf("关闭无障碍", "关闭", "停用无障碍", "禁用", "取消").forEach { label ->
            assertTrue("$label 应被判为危险", SystemGuardPolicy.isDangerousLabel(label))
        }
    }

    @Test
    fun keepLabelsAreNotDangerous() {
        listOf("保持开启", "保持使用", "保持").forEach { label ->
            assertFalse("$label 不应被判为危险", SystemGuardPolicy.isDangerousLabel(label))
        }
    }

    /**
     * **双重闸门**：即使文案白名单因为系统改词而命中，
     * 只要里面带危险词也必须放弃。
     */
    @Test
    fun safeToClickRequiresBothChecks() {
        assertTrue(SystemGuardPolicy.isSafeToClick("保持开启"))
        assertFalse(SystemGuardPolicy.isSafeToClick("关闭无障碍"))
        // "保持" 同时满足"在白名单里"和"不含危险词" → 可以点
        assertTrue(SystemGuardPolicy.isSafeToClick("保持"))
    }

    /** 大小写/前后空格要能容错，但不能改变语义。 */
    @Test
    fun trimsWhitespaceBeforeComparing() {
        assertTrue(SystemGuardPolicy.isKeepLabel("  保持开启  "))
        assertFalse(SystemGuardPolicy.isKeepLabel("  关闭无障碍  "))
    }
}
