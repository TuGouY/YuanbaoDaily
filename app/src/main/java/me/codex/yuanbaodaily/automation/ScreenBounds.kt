package me.codex.yuanbaodaily.automation

/**
 * "落点/节点是否在屏幕内"的纯函数校验（可单测）。
 *
 * ## 为什么需要它（真机 `evidence/trace_e3_ask` 实证）
 *
 * 元宝首页的侧栏（抽屉）是用**把主内容窗口整体推到右侧屏幕之外**的方式展开的。
 * 侧栏打开时同一帧的无障碍树是：
 *
 * ```text
 * composerState editor=[1108,2274][1871,2571] text='关于培养稳定的阅读习惯…'
 * composerBar    send=[2008,2392][2167,2551]  action=2087,2471
 * ```
 *
 * 而这块屏幕只有 **1272 宽**。也就是说：
 * - 几何算法本身没错（它老老实实按节点 bounds 算的）；
 * - 但场景是错的 —— 主窗口根本不在屏幕上，算出来的任何坐标都点不到东西。
 *
 * 后果是那一轮"发送"连续 4 次以 `x=2087` 派发手势，每次都报"派发成功"，
 * 而输入框里的文案**一直没变**，上层却因为只看派发返回值而把它当成发送成功
 * （`prompt: 对话页兜底点发送(真实几何) tapped=true` → `submitted=true`）。
 * 一条任务就这么静默丢了。
 *
 * 所以这里提供一道**与业务无关的通用兜底**：只要落点/节点超出屏幕，
 * 一律判定为"几何不可信"，由调用方拒绝派发并留下可指认原因的日志。
 *
 * ## 读不到屏幕尺寸时的取向
 *
 * `screenWidth <= 0 || screenHeight <= 0` 时**返回 true（不拦）**。
 * 宁可漏拦（退化成旧行为），也不要在拿不到尺寸时把**正常**点击全部拦掉 ——
 * 后者会让整个模块彻底不可用。
 */
internal object ScreenBounds {

    /** [x],[y] 是否落在 `screenWidth × screenHeight` 的屏幕内。 */
    fun containsPoint(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        return x >= 0 && y >= 0 && x < screenWidth && y < screenHeight
    }

    /**
     * 矩形 `[left,top][right,bottom]` 是否**完整**落在屏幕内。
     *
     * 必须"完整"而不是"相交"：侧栏展开时输入框是 `[1108,2274][1871,2571]`，
     * 它和屏幕 `[0,0][1272,2800]` **是相交的**（左侧 164px 还看得见），
     * 用"相交"判据根本发现不了问题。
     */
    fun containsBox(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        return left >= 0 && top >= 0 && right <= screenWidth && bottom <= screenHeight
    }
}
