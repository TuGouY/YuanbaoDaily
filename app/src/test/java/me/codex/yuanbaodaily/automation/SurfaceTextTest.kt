package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "现在在哪一屏"判据的回归测试。
 *
 * 这些判据直接决定**要不要按返回键**，而"在首页按返回会把元宝整个退掉"，
 * 所以宁可判"不是首页"（多按一次返回、也就多退一层）也不能把它判反了 ——
 * 但反过来，把**能力页**判成首页会让模块在能力页上找抽屉入口，找不到就乱点，
 * 也就是用户反馈的那个现象。两侧都要有断言。
 *
 * 文案全部取自真机 dump（`evidence/home_probe.xml`、`evidence/write_probe.xml`）。
 */
class SurfaceTextTest {

    /** 真机首页：快速入口那一行 + 底部输入栏提示。 */
    private val homeText = """
        元宝
        快速 AI写作 作业批改 AI创作
        EditText
        发消息或按住说话...
        切换语音
    """.trimIndent()

    /** 真机写作页（`evidence/write_probe.xml`）。 */
    private val writingText = """
        选择写作类型
        通用 作文 作文素材 营销文案 公文
        发布平台 写作风格 字数
        AI写作
        请输入你要写的主题
        切换语音
    """.trimIndent()

    /** P图能力页（首页里那个"AI创作"点了之后的页面）。 */
    private val pictureText = """
        P图
        点击添加创作素材
        请输入你的P图要求
    """.trimIndent()

    /** 侧栏展开时的屏幕文本（此时既不是首页也不是能力页）。 */
    private val drawerText = """
        元宝
        新建对话
        AI创作
        福利中心
        元宝派
        技能
        创建分组
    """.trimIndent()

    // ------------------------------- 首页 -------------------------------

    @Test
    fun recognisesRealHomePage() {
        assertTrue(SurfaceText.looksLikeHome(homeText))
    }

    /**
     * **底栏被滑走之后，仍然是首页。**
     *
     * 真机实证（1.4.21，lite）：底栏那排快捷入口可横向滚动。写作任务为了点 `AI写作`
     * 把它滑到最右端之后，屏幕上就只剩 `抽iPhone Duo资格 / AI写作 / AI录音笔` ——
     * 老判据要求"看到 AI创作"，于是判成"其它页"，`ensureHomeSurface` 连按两次返回
     * **把元宝直接退出去了**，后面整轮任务一个点击都落不下去。
     */
    @Test
    fun homeWithScrolledChipRowIsStillHome() {
        val scrolled = """
            元宝
            抽iPhone Duo资格 AI写作 AI录音笔
            发消息或按住说话...
            切换语音
        """.trimIndent()
        assertTrue(SurfaceText.looksLikeHome(scrolled))
    }

    /** 能力页即使有"切换语音"这类共用文案，也不能被判成首页（否则会在上面按返回）。 */
    @Test
    fun capabilityPagesAreNeverHome() {
        assertFalse(SurfaceText.looksLikeHome(writingText))
        assertFalse(SurfaceText.looksLikeHome(pictureText))
    }

    @Test
    fun recognisesHomeByActivityName() {
        assertTrue(SurfaceText.isHomeActivity("com.tencent.hunyuan.app.chat.home.v2.YBHomeActivityV2"))
    }

    /**
     * 福利中心必须能被单独认出来。
     *
     * 真机实证（1.4.7）：它在文本上很像首页（有"问元宝问题""使用写作能力"），
     * 于是 `looksLikeHome` 可能点头，进而在上面找"AI创作"这个根本不存在的入口。
     * 用 Activity 判最稳。
     */
    @Test
    fun detectsWelfareActivity() {
        assertTrue(
            SurfaceText.isWelfareActivity(
                "com.tencent.hunyuan.app.chat.biz.welfare.ui.WelfareWebActivity",
            ),
        )
        assertFalse(
            SurfaceText.isWelfareActivity("com.tencent.hunyuan.app.chat.home.v2.YBHomeActivityV2"),
        )
        assertFalse(SurfaceText.isWelfareActivity(""))
    }

    @Test
    fun otherActivitiesAreNotHome() {
        assertFalse(SurfaceText.isHomeActivity("com.tencent.hunyuan.app.chat.biz.welfare.ui.WelfareWebActivity"))
        assertFalse(SurfaceText.isHomeActivity("com.tencent.hunyuan.app.chat.biz.chat.ChatActivity"))
        assertFalse(SurfaceText.isHomeActivity(""))
    }

    /** 侧栏展开时**不能**判成首页：判错就会在这个状态下去点抽屉入口（其实是关闭按钮）。 */
    @Test
    fun drawerOpenIsNotHome() {
        assertFalse(SurfaceText.looksLikeHome(drawerText))
    }

    // ------------------------------ 能力页 ------------------------------

    @Test
    fun recognisesWritingPageAsCapability() {
        assertTrue(SurfaceText.looksLikeCapability(writingText))
    }

    @Test
    fun recognisesPicturePageAsCapability() {
        assertTrue(SurfaceText.looksLikeCapability(pictureText))
    }

    @Test
    fun homeIsNotCapability() {
        assertFalse(SurfaceText.looksLikeCapability(homeText))
    }

    @Test
    fun drawerIsNotCapability() {
        assertFalse(SurfaceText.looksLikeCapability(drawerText))
    }

    @Test
    fun emptyTextIsNeither() {
        assertFalse(SurfaceText.looksLikeHome(""))
        assertFalse(SurfaceText.looksLikeCapability(""))
    }

    /**
     * `AI写作` 这个词在**首页快捷入口**和写作页里都有，单凭它不能判任何一屏。
     * 这条锁死"别把首页当写作页、也别把写作页当首页"。
     */
    @Test
    fun aiWritingWordAloneDecidesNothing() {
        val only = "AI写作"
        assertFalse(SurfaceText.looksLikeHome(only))
        assertFalse(SurfaceText.looksLikeCapability(only))
    }

    // --------------------------- 领积分弹窗 ---------------------------

    /**
     * 真机弹窗文本（2026-10-21 截图 `evidence/shots/after_problem.png`）：
     * 盖在福利中心上的白色模态框，背景那层文本仍能读到一部分，所以这里连背景一起给。
     */
    private val rewardDialogText = """
        24
        每日问元宝得积分
        恭喜获得
        1500 积分
        可前往 兑换商城 查看并使用
        开心收下
        使用写作能力 已完成 3/3
        问元宝问题 已完成 1/3
    """.trimIndent()

    @Test
    fun recognisesRewardDialog() {
        assertTrue(SurfaceText.looksLikeRewardDialog(rewardDialogText))
    }

    /**
     * **福利中心页面本身不能判成弹窗**：这页到处是"积分"（239500 积分、1500 积分），
     * 判错就会在正常页面上到处找「开心收下」，点不到还算好，点错就是误触。
     */
    @Test
    fun welfarePageIsNotRewardDialog() {
        val welfare = """
            239500 积分 兑换商城
            每日问元宝得积分
            积分 5000 6000 8000 10000
            问元宝问题 1000 已完成 1/3 去提问
            使用写作能力 1500 已完成 3/3 去写作
        """.trimIndent()
        assertFalse(SurfaceText.looksLikeRewardDialog(welfare))
    }

    /** 标题在、按钮还没渲染出来时**不许点**（没有安全落点就放弃）。 */
    @Test
    fun rewardTitleWithoutButtonIsNotActionable() {
        assertFalse(SurfaceText.looksLikeRewardDialog("恭喜获得 1500 积分"))
    }

    @Test
    fun emptyIsNotRewardDialog() {
        assertFalse(SurfaceText.looksLikeRewardDialog(""))
    }
}
