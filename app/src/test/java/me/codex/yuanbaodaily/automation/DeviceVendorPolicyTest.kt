package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商识别的回归测试。
 *
 * 识错的代价不是"显示错了品牌名"，而是**给出错误的手动设置路径** ——
 * 用户照着去小米的设置里找 i管家（vivo 的东西），找不到就会以为这软件坏了。
 */
class DeviceVendorPolicyTest {

    private fun detect(manufacturer: String, brand: String = manufacturer) =
        DeviceVendorPolicy.detect(manufacturer, brand)

    @Test
    fun detectsXiaomiFamily() {
        assertEquals(DeviceVendorPolicy.Vendor.XIAOMI, detect("Xiaomi"))
        assertEquals(DeviceVendorPolicy.Vendor.XIAOMI, detect("Redmi"))
        assertEquals(DeviceVendorPolicy.Vendor.XIAOMI, detect("POCO"))
        // 真实机型：manufacturer=Xiaomi, brand=Redmi
        assertEquals(DeviceVendorPolicy.Vendor.XIAOMI, detect("Xiaomi", "Redmi"))
    }

    @Test
    fun detectsVivoFamily() {
        assertEquals(DeviceVendorPolicy.Vendor.VIVO, detect("vivo"))
        assertEquals(DeviceVendorPolicy.Vendor.VIVO, detect("iQOO"))
        // 真实机型：manufacturer=vivo, brand=iQOO
        assertEquals(DeviceVendorPolicy.Vendor.VIVO, detect("vivo", "iQOO"))
    }

    @Test
    fun detectsOppoFamily() {
        assertEquals(DeviceVendorPolicy.Vendor.OPPO, detect("OPPO"))
        assertEquals(DeviceVendorPolicy.Vendor.OPPO, detect("OnePlus"))
        assertEquals(DeviceVendorPolicy.Vendor.OPPO, detect("realme"))
        // 真实机型：manufacturer=OPPO, brand=OnePlus
        assertEquals(DeviceVendorPolicy.Vendor.OPPO, detect("OPPO", "OnePlus"))
    }

    /**
     * 荣耀必须**先于**华为判断。
     *
     * 早期荣耀机型的 `manufacturer` 是 `HUAWEI`、`brand` 是 `HONOR`；
     * 如果按"字符串里含 huawei 就算华为"的朴素顺序，这类机器会被指到 EMUI 的设置路径，
     * 而它实际跑的是 MagicOS（路径不同）。所以合并串里同时出现时，荣耀优先。
     */
    @Test
    fun honorTakesPrecedenceOverHuawei() {
        assertEquals(DeviceVendorPolicy.Vendor.HONOR, detect("HONOR"))
        assertEquals(DeviceVendorPolicy.Vendor.HONOR, detect("HUAWEI", "HONOR"))
    }

    @Test
    fun detectsHuaweiSamsungMeizu() {
        assertEquals(DeviceVendorPolicy.Vendor.HUAWEI, detect("HUAWEI"))
        assertEquals(DeviceVendorPolicy.Vendor.SAMSUNG, detect("samsung"))
        assertEquals(DeviceVendorPolicy.Vendor.MEIZU, detect("Meizu"))
    }

    /** 识别不出来时不能崩，要给通用路径（"其他品牌"）。 */
    @Test
    fun unknownFallsBackToOther() {
        assertEquals(DeviceVendorPolicy.Vendor.OTHER, detect("Google"))
        assertEquals(DeviceVendorPolicy.Vendor.OTHER, detect(""))
        assertEquals(DeviceVendorPolicy.Vendor.OTHER, detect("Sony", "Xperia"))
    }

    /** 大小写不影响识别（`Build.MANUFACTURER` 各家大小写并不统一）。 */
    @Test
    fun detectionIsCaseInsensitive() {
        assertEquals(DeviceVendorPolicy.Vendor.XIAOMI, detect("XIAOMI"))
        assertEquals(DeviceVendorPolicy.Vendor.XIAOMI, detect("xiaomi"))
        assertEquals(DeviceVendorPolicy.Vendor.VIVO, detect("VIVO"))
    }

    // --------------------------- 引导文案 ---------------------------

    /** 每个厂商都必须有**至少一条**可执行的路径，否则界面等于什么都没说。 */
    @Test
    fun everyVendorHasActionableToggles() {
        DeviceVendorPolicy.Vendor.entries.forEach { vendor ->
            val toggles = DeviceVendorPolicy.requiredToggles(vendor)
            assertTrue("$vendor 没有任何引导项", toggles.isNotEmpty())
            toggles.forEach { line ->
                assertTrue("$vendor 的引导项过短：$line", line.length > 8)
            }
        }
    }

    /**
     * **不允许出现具体包名/组件名**。
     *
     * 这是本类最重要的约束：一旦在引导里写 `com.miui.securitycenter`，
     * 那个路径在同厂商的其他版本上就会失效，而文案看起来仍然"很具体"，
     * 用户会更困惑。引导只能是"设置 → xxx → yyy"这种人能照着走的描述。
     */
    @Test
    fun togglesNeverHardcodePackageNames() {
        DeviceVendorPolicy.Vendor.entries.forEach { vendor ->
            DeviceVendorPolicy.requiredToggles(vendor).forEach { line ->
                assertFalse("$vendor 的引导里写死了包名：$line", line.contains("com."))
                assertFalse("$vendor 的引导里写死了组件名：$line", line.contains("/."))
            }
        }
    }

    /** 提要及时点名当前机型，否则用户不知道这些路径是不是给自己的。 */
    @Test
    fun hintNamesTheDetectedVendor() {
        val hint = DeviceVendorPolicy.hint(DeviceVendorPolicy.Vendor.XIAOMI)
        assertTrue(hint.contains("小米"))
    }

    /**
     * 提要不要只描述某一家 ROM 的弹窗形态。
     *
     * "关联启动确认框"是 ColorOS 的样子；小米是"后台弹出界面"权限、vivo 是"关联启动"开关。
     * 文案里出现厂商专属词汇时，其他品牌用户会找不到对应开关。
     */
    @Test
    fun hintIsVendorNeutral() {
        val hint = DeviceVendorPolicy.hint(DeviceVendorPolicy.Vendor.OTHER)
        assertFalse(hint.contains("ColorOS"))
        assertFalse(hint.contains("关联启动确认"))
    }
}
