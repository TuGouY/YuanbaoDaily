package me.codex.yuanbaodaily.automation

/**
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 这个字符串的**纯函数**解析/改写。
 *
 * 单独拆出来是为了能进纯 JVM 单元测试 —— 真机上这条设置字符串一旦写错，
 * 结果是"无障碍列表里的开关直接消失"，比掉线更难恢复，所以改写规则必须有回归测试兜底。
 *
 * 格式（AOSP `AccessibilityManagerService`）：
 * ```
 *     pkg/pkg.Class:pkg2/pkg2.Class2
 * ```
 * 分隔符是冒号 `:`，允许空白与重复项（系统会自己归一化）。
 */
internal object AccessibilityServiceList {
    const val SEPARATOR = ":"

    /** 拆成有序、去空、去重的组件名列表。 */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 按系统格式拼回去；空列表返回空串（系统语义 = 没有任何无障碍服务启用）。 */
    fun join(components: List<String>): String = components
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(SEPARATOR)

    fun contains(raw: String?, component: String): Boolean = parse(raw).contains(component.trim())

    /**
     * 摘掉某个组件 —— 用于**强制重新绑定**的第一步。
     *
     * 系统只在 `enabled_accessibility_services` 的**字符串发生变化**时才会
     * 重新走一遍 bind/unbind；原地写同样的值不会触发任何动作。所以自愈必须
     * "先摘掉 → 再装回去"，这里产出的就是第一步的值。
     */
    fun without(raw: String?, component: String): String {
        val target = component.trim()
        return join(parse(raw).filter { it != target })
    }

    /**
     * 装回某个组件 —— 强制重新绑定的第二步。
     *
     * 已存在时返回归一化后的原值（幂等），不会重复追加。
     */
    fun withComponent(raw: String?, component: String): String {
        val target = component.trim()
        val list = parse(raw).toMutableList()
        if (!list.contains(target)) list.add(target)
        return join(list)
    }
}
