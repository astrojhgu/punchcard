package com.gujunhua.attendance.docx

/**
 * 极小的 XML 结构扫描器。
 *
 * 为什么不用 DOM/Transformer：那会把整个 document.xml 重新序列化，
 * 命名空间前缀、属性顺序、自闭合写法全变，输出与模板不再逐字节可比，
 * 出问题时无法判断是「我改坏了」还是「序列化器改的」。
 *
 * 这里只在字符串层面定位元素边界，替换目标片段，其余字节原样透传。
 * 只支持 OOXML 实际用得到的东西：元素、属性、注释；不处理 CDATA / DTD。
 */
internal object XmlScan {

    /**
     * 一个元素在源字符串里的位置。
     * [start, contentStart) 是开标签，[contentStart, contentEnd) 是内容，
     * [contentEnd, endExclusive) 是闭标签。自闭合元素三者相等。
     */
    class Element(
        val name: String,
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val endExclusive: Int,
        val selfClosing: Boolean,
    )

    /** 从 pos（必须是 '<'）解析一个完整元素；不是元素或未闭合返回 null。 */
    fun parseElement(xml: String, pos: Int, limit: Int = xml.length): Element? {
        if (pos < 0 || pos >= limit || xml[pos] != '<') return null
        if (pos + 1 >= limit) return null
        val second = xml[pos + 1]
        if (second == '!' || second == '?') return null // 注释 / PI / DOCTYPE

        val openEnd = findTagEnd(xml, pos, limit) ?: return null
        val selfClosing = xml[openEnd - 1] == '/'

        var i = pos + 1
        val nameStart = i
        while (i < openEnd && !xml[i].isWhitespace() && xml[i] != '/' && xml[i] != '>') i++
        val name = xml.substring(nameStart, i)
        if (name.isEmpty()) return null

        if (selfClosing) {
            return Element(name, pos, openEnd + 1, openEnd + 1, openEnd + 1, true)
        }

        // 用深度计数找配对闭标签。OOXML 里 w:tc/w:tr 不会自嵌套，
        // 但 w:tbl 可以（表格套表格），所以不能简单地 indexOf 第一个闭标签。
        var depth = 1
        var k = openEnd + 1
        while (k < limit) {
            val lt = xml.indexOf('<', k)
            if (lt < 0 || lt >= limit) break
            if (xml.startsWith("</", lt)) {
                val closeEnd = findTagEnd(xml, lt, limit) ?: break
                val closeName = xml.substring(lt + 2, closeEnd).trim()
                if (closeName == name) {
                    depth--
                    if (depth == 0) {
                        return Element(name, pos, openEnd + 1, lt, closeEnd + 1, false)
                    }
                }
                k = closeEnd + 1
            } else if (xml.startsWith("<!--", lt)) {
                val ce = xml.indexOf("-->", lt)
                k = if (ce < 0) limit else ce + 3
            } else {
                val child = parseElement(xml, lt, limit)
                k = child?.endExclusive ?: (lt + 1)
            }
        }
        return null
    }

    /** [from, to) 区间内的顶层子元素。 */
    fun children(xml: String, from: Int, to: Int): List<Element> {
        val out = ArrayList<Element>()
        var k = from
        while (k < to) {
            val lt = xml.indexOf('<', k)
            if (lt < 0 || lt >= to) break
            if (xml.startsWith("<!--", lt)) {
                val ce = xml.indexOf("-->", lt)
                k = if (ce < 0) to else ce + 3
                continue
            }
            val el = parseElement(xml, lt, to)
            if (el == null) {
                k = lt + 1
                continue
            }
            out.add(el)
            k = el.endExclusive
        }
        return out
    }

    /** 取第一个名为 [name] 的后代元素（深度优先）。 */
    fun findFirst(xml: String, name: String, from: Int = 0, to: Int = xml.length): Element? {
        for (el in children(xml, from, to)) {
            if (el.name == name) return el
            if (!el.selfClosing) {
                val nested = findFirst(xml, name, el.contentStart, el.contentEnd)
                if (nested != null) return nested
            }
        }
        return null
    }

    /** 读元素自身某个属性的值，找不到返回 null。 */
    fun attribute(xml: String, el: Element, name: String): String? {
        // 注意：自闭合元素的 contentStart == endExclusive == 开标签之后，
        // 所以不能拿它们当开标签的结束位置，必须重新找 '>'。
        val openEnd = findTagEnd(xml, el.start, el.endExclusive) ?: return null
        var k = el.start + 1
        // 跳过标签名
        while (k < openEnd && !xml[k].isWhitespace() && xml[k] != '>' && xml[k] != '/') k++
        while (k < openEnd) {
            while (k < openEnd && xml[k].isWhitespace()) k++
            if (k >= openEnd || xml[k] == '/' || xml[k] == '>') break
            val attrStart = k
            while (k < openEnd && xml[k] != '=' && !xml[k].isWhitespace()) k++
            val attrName = xml.substring(attrStart, k)
            while (k < openEnd && xml[k].isWhitespace()) k++
            if (k < openEnd && xml[k] == '=') {
                k++
                while (k < openEnd && xml[k].isWhitespace()) k++
                if (k < openEnd && (xml[k] == '"' || xml[k] == '\'')) {
                    val quote = xml[k]
                    k++
                    val valStart = k
                    while (k < openEnd && xml[k] != quote) k++
                    val value = xml.substring(valStart, k)
                    k++
                    if (attrName == name) return value
                }
            } else if (attrName == name) {
                return ""
            }
        }
        return null
    }

    /** 收集元素自身与其全部后代的文本（w:t 内容拼接由调用方决定）。 */
    fun descendants(xml: String, el: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(from: Int, to: Int) {
            for (child in children(xml, from, to)) {
                if (child.name == name) out.add(child)
                if (!child.selfClosing) walk(child.contentStart, child.contentEnd)
            }
        }
        walk(el.contentStart, el.contentEnd)
        return out
    }

    private fun findTagEnd(xml: String, pos: Int, limit: Int): Int? {
        var inQuote = false
        var quote = ' '
        var i = pos + 1
        while (i < limit) {
            val c = xml[i]
            if (inQuote) {
                if (c == quote) inQuote = false
            } else {
                when (c) {
                    '"', '\'' -> { inQuote = true; quote = c }
                    '>' -> return i
                }
            }
            i++
        }
        return null
    }
}
