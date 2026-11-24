package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "哪个窗口算相册"判据的回归测试。
 *
 * 锁死的是一个真机现象：
 *
 * > P图 打开相册后不选照片，而是点返回，反复这样。
 *
 * 根因是筛选相册窗口用了**泛词**「相册 / 照片」——
 * 而 P图 能力页上就有这两个词，于是能力页被当成相册去扫（扫不到格子，候选 0），
 * 真正的相册窗口反倒被排除。候选 0 让上层以为"相册是空的"，
 * 于是按返回 → 重开 → 再来一遍。
 *
 * 所以这里的核心断言是：**能力页绝不等于相册**，而真相册必须被认出来。
 */
class MediaPickerWindowPolicyTest {

    /** P图 能力页的真实文案（含"相册"/"照片"这类泛词）。 */
    private val picturePageText = """
        P图
        点击添加创作素材
        相册 拍摄
        请输入你的P图要求
        照片风格
    """.trimIndent()

    /** 首页（含"福利中心"等无关文案）。 */
    private val homeText = """
        快速 AI写作 作业批改 AI创作
        福利中心
        发消息或按住说话...
    """.trimIndent()

    /** 元宝自带相册窗口的真实文案。 */
    private val pickerText = """
        取消
        最近项目
        所有照片
        相机胶卷
        本地相册
    """.trimIndent()

    private fun shouldScan(
        packageName: String = "com.tencent.hunyuan.app.chat",
        text: String,
        camera: Boolean = false,
        media: Boolean = false,
    ) = MediaPickerWindowPolicy.shouldScan(packageName, text, camera, media)

    // ------------------------------ 核心回归 ------------------------------

    /** **P图 能力页不是相册** —— 这条不成立就会退化成"点返回反复循环"。 */
    @Test
    fun picturePageIsNotAPicker() {
        assertFalse(
            "P图 能力页含'相册/照片'泛词，但绝不能当成相册窗口去扫",
            shouldScan(text = picturePageText),
        )
    }

    @Test
    fun homePageIsNotAPicker() {
        assertFalse(shouldScan(text = homeText))
    }

    /** 真正的相册窗口必须被认出来（强标记）。 */
    @Test
    fun realPickerWindowIsRecognised() {
        assertTrue(shouldScan(text = pickerText))
    }

    @Test
    fun eachStrongMarkerAloneIsEnough() {
        MediaPickerWindowPolicy.STRONG_MARKERS.forEach { marker ->
            assertTrue("强标记「$marker」单独出现也应当被认成相册", shouldScan(text = "取消 $marker 完成"))
        }
    }

    /** 泛词**单独**出现不算 —— 这正是旧实现的错误来源。 */
    @Test
    fun genericWordsAloneAreNotEnough() {
        listOf("相册", "照片", "选择", "图片").forEach { word ->
            assertFalse("泛词「$word」不足以判定为相册窗口", shouldScan(text = "P图 $word 拍摄"))
        }
    }

    // ------------------------------ 包名规则 ------------------------------

    /** 系统选择器 / 各家相册不需要文案证据，直接放行。 */
    @Test
    fun mediaPackageAlwaysScanned() {
        assertTrue(shouldScan(packageName = "com.miui.gallery", text = "", media = true))
        assertTrue(shouldScan(packageName = "com.android.photopicker", text = "随便什么", media = true))
    }

    /** 相机包一律排除，哪怕里面有"最近项目"。 */
    @Test
    fun cameraPackageNeverScanned() {
        assertFalse(shouldScan(packageName = "com.miui.camera", text = "最近项目", camera = true))
    }

    // ------------------------------ 文件名证据 ------------------------------

    /** 窗口里能看到生成文件的名字 —— 最强证据，一定在扫。 */
    @Test
    fun markerMentionIsStrongestEvidence() {
        assertTrue(
            MediaPickerWindowPolicy.mentionsMarker(
                "最近项目 YuanbaoDaily_AUTO_pic_1_1.png",
                "YuanbaoDaily_AUTO_pic_1_1",
            ),
        )
        assertFalse(MediaPickerWindowPolicy.mentionsMarker("最近项目", ""))
        assertFalse(MediaPickerWindowPolicy.mentionsMarker("", "YuanbaoDaily_AUTO_pic_1_1"))
    }

    /** 能力页 + 文件名同时出现（相册以覆盖层压在能力页上）→ 必须扫描。 */
    @Test
    fun overlayPickerOnTopOfPicturePageIsScanned() {
        val overlay = picturePageText + "\n最近项目\nYuanbaoDaily_AUTO_pic_9_1.png"
        assertTrue(shouldScan(text = overlay))
        assertTrue(
            MediaPickerWindowPolicy.mentionsMarker(overlay, "YuanbaoDaily_AUTO_pic_9_1"),
        )
    }
}
