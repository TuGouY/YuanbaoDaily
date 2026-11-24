package me.codex.yuanbaodaily.automation

/**
 * Minimal UIAutomator dump reader.
 *
 * The platform dump is a flat list of `<node>` elements carrying absolute bounds
 * plus text, content description, resource id and package. It is the only way to
 * see a cloned ("双开") host: those windows belong to another Android user, so
 * neither this process' accessibility service nor the module Provider can reach
 * them, while the root shell can.
 */
internal object ScreenScanner {
    /**
     * 一个 dump 节点。
     *
     * `bounds` 故意用纯 Int 的 [Bounds] 而不是 `android.graphics.Rect`：几何判定
     * （`ComposerGeometry`）必须能被纯 JVM 单测覆盖，而 `Rect` 在单测里是空壳
     * （构造函数不保存值、`width()`/`centerX()` 返回 0），拿它当数据载体，
     * 测试就永远跑不出真实结果。
     */
    data class DumpNode(
        val packageName: String,
        val text: String,
        val description: String,
        val resourceId: String,
        val className: String,
        val bounds: Bounds,
        val clickable: Boolean,
        val scrollable: Boolean,
    ) {
        val label: String
            get() = listOf(text, description).filter(String::isNotBlank).joinToString(" ")
        val area: Long get() = bounds.width.toLong() * bounds.height.toLong()
        val box: Bounds get() = bounds
    }

    fun parseDump(raw: String): List<DumpNode> {
        if (raw.isBlank()) return emptyList()
        val nodes = mutableListOf<DumpNode>()
        var index = 0
        while (true) {
            val open = raw.indexOf("<node", index)
            if (open < 0) break
            val close = raw.indexOf('>', open)
            if (close < 0) break
            parseNode(raw.substring(open, close))?.let(nodes::add)
            index = close + 1
        }
        return nodes
    }

    private fun parseNode(tag: String): DumpNode? {
        val bounds = parseBounds(attribute(tag, "bounds")) ?: return null
        return DumpNode(
            packageName = attribute(tag, "package"),
            text = attribute(tag, "text"),
            description = attribute(tag, "content-desc"),
            resourceId = attribute(tag, "resource-id"),
            className = attribute(tag, "class"),
            bounds = bounds,
            clickable = attribute(tag, "clickable") == "true",
            scrollable = attribute(tag, "scrollable") == "true",
        )
    }

    private fun attribute(tag: String, name: String): String {
        val key = "$name=\""
        val start = tag.indexOf(key)
        if (start < 0) return ""
        val valueStart = start + key.length
        val end = tag.indexOf('"', valueStart)
        if (end < 0) return ""
        return tag.substring(valueStart, end)
    }

    /** Parses the `[left,top][right,bottom]` form used by UIAutomator dumps. */
    fun parseBounds(value: String): Bounds? {
        val digits = NUMBER_REGEX.findAll(value).map { it.value }.toList()
        if (digits.size < 4) return null
        val left = digits[0].toIntOrNull() ?: return null
        val top = digits[1].toIntOrNull() ?: return null
        val right = digits[2].toIntOrNull() ?: return null
        val bottom = digits[3].toIntOrNull() ?: return null
        if (right <= left || bottom <= top) return null
        return Bounds(left, top, right, bottom)
    }

    /** The smallest node whose label contains one of the tokens. */
    fun findToken(nodes: List<DumpNode>, tokens: List<String>): DumpNode? {
        val clean = normalize(tokens)
        if (clean.isEmpty()) return null
        return nodes.asSequence()
            .filter { node ->
                val label = normalize(listOf(node.label)).firstOrNull().orEmpty()
                label.isNotEmpty() && clean.any(label::contains)
            }
            .minByOrNull { it.area }
    }

    /** The smallest node whose label equals one of the tokens. */
    fun findExact(nodes: List<DumpNode>, tokens: List<String>): DumpNode? {
        val clean = normalize(tokens)
        if (clean.isEmpty()) return null
        return nodes.asSequence()
            .filter { node ->
                val label = normalize(listOf(node.label)).firstOrNull().orEmpty()
                label.isNotEmpty() && clean.any { label == it }
            }
            .minByOrNull { it.area }
    }

    /**
     * All nodes whose label contains one of the tokens, ordered the way a
     * recommendation list is read: top to bottom, then left to right.
     */
    fun findAllTokens(nodes: List<DumpNode>, tokens: List<String>): List<DumpNode> {
        val clean = normalize(tokens)
        if (clean.isEmpty()) return emptyList()
        return nodes.asSequence()
            .filter { node ->
                val label = normalize(listOf(node.label)).firstOrNull().orEmpty()
                label.isNotEmpty() && clean.any(label::contains)
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .toList()
    }

    /** First node whose resource id ends with `:id/<name>` (or equals it). */
    fun findByResourceId(nodes: List<DumpNode>, ids: List<String>): DumpNode? {
        val clean = ids.map(String::trim).filter(String::isNotEmpty)
        if (clean.isEmpty()) return null
        return nodes.firstOrNull { node ->
            val id = node.resourceId
            id.isNotEmpty() && clean.any { id.equals(it, true) || id.endsWith(":id/$it", true) }
        }
    }

    /** The first editable field the dump exposes (Compose reports EditText). */
    fun findEditable(nodes: List<DumpNode>): DumpNode? = nodes
        .filter { it.className.contains("EditText", ignoreCase = true) }
        .maxByOrNull { it.area }

    /** The largest scrollable container, used as the photo-grid frame. */
    fun findGridContainer(nodes: List<DumpNode>): DumpNode? = nodes
        .filter {
            it.scrollable || it.className.contains("RecyclerView") ||
                it.className.contains("GridView") || it.className.contains("ViewPager")
        }
        .maxByOrNull { it.area }

    private fun normalize(values: List<String>): List<String> = values
        .map { it.replace(WHITESPACE, "").lowercase() }
        .filter(String::isNotEmpty)

    private val WHITESPACE = Regex("\\s+")
    private val NUMBER_REGEX = Regex("-?\\d+")
}
