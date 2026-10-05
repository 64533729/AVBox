package com.github.catvod.crawler.js

import android.text.TextUtils

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.RegexUtils
import com.github.tvbox.osc.util.StringUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

import java.net.MalformedURLException
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

class HtmlParser {

    class Painfo {

        @JvmField
        var nparse_rule: String? = null

        @JvmField
        var nparse_index: Int = 0

        @JvmField
        var excludes: List<String>? = null
    }

    companion object {

        private var pdfh_html: String = ""
        private var pdfa_html: String = ""
        private val p: Pattern = Pattern.compile("url\\((.*?)\\)", Pattern.MULTILINE or Pattern.DOTALL)
        private val NOADD_INDEX: Pattern = Pattern.compile(":eq|:lt|:gt|:first|:last|^body$|^#") // 不自动加eq下标索引
        private val URLJOIN_ATTR: Pattern = Pattern.compile("(url|src|href|-original|-src|-play|-url|style)$", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE) // 需要自动urljoin的属性
        private val SPECIAL_URL: Pattern = Pattern.compile("^(ftp|magnet|thunder|ws):", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE) // 过滤特殊链接,不走urlJoin
        private var pdfh_doc: Document? = null
        private var pdfa_doc: Document? = null

        @JvmStatic
        fun joinUrl(parent: String?, child: String): String {
            if (StringUtils.isEmpty(parent)) {
                return child
            }
            val base = parent!!
            var q = base
            try {
                val url = URL(URL(base), child)
                q = url.toExternalForm()
            } catch (e: MalformedURLException) {
                LOG.e("HtmlParser", e)
            }
            //        if (q.contains("#")) {
            //            q = q.replaceAll("^(.+?)#.*?$", "$1");
            //        }
            return q
        }

        private fun getParseInfo(nparse: String): Painfo {
            /*
         根据传入的单规则获取 parse规则，索引位置,排除列表  -- 可以用于剔除元素,支持多个，按标签剔除，按id剔除等操作
         :param nparse:
         :return:*/
            val painfo = Painfo()
            //List<String> excludes = new ArrayList<>();  //定义排除列表默认值为空
            //int nparse_index;  //定义位置索引默认值为0
            painfo.nparse_rule = nparse //定义规则默认值为本身
            if (nparse.contains(":eq")) {
                painfo.nparse_rule = RegexUtils.getPattern(":").split(nparse)[0]
                var nparse_pos = RegexUtils.getPattern(":").split(nparse)[1]

                if (painfo.nparse_rule!!.contains("--")) {
                    val rules = RegexUtils.getPattern("--").split(painfo.nparse_rule!!)
                    val excludes = ArrayList(rules.toList())
                    excludes.removeAt(0)
                    painfo.excludes = excludes
                    painfo.nparse_rule = rules[0]
                } else if (nparse_pos.contains("--")) {
                    val rules = RegexUtils.getPattern("--").split(nparse_pos)
                    val excludes = ArrayList(rules.toList())
                    excludes.removeAt(0)
                    painfo.excludes = excludes
                    nparse_pos = rules[0]
                }

                try {
                    painfo.nparse_index = nparse_pos.replace("eq(", "").replace(")", "").toInt()
                } catch (e1: Exception) {
                    painfo.nparse_index = 0
                }
            } else {
                if (nparse.contains("--")) {
                    val rules = RegexUtils.getPattern("--").split(painfo.nparse_rule!!)
                    val excludes = ArrayList(rules.toList())
                    excludes.removeAt(0)
                    painfo.excludes = excludes
                    painfo.nparse_rule = rules[0]
                }
            }
            return painfo
        }

        @JvmStatic
        fun isIndex(str: String?): Boolean {
            if (StringUtils.isEmpty(str)) {
                return false
            }
            val text = str!!
            for (str2 in arrayOf(":eq", ":lt", ":gt", ":first", ":last", "body", "#")) {
                if (text.contains(str2)) {
                    if (str2 == "body" || str2 == "#") {
                        return text.startsWith(str2)
                    }
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun isUrl(str: String?): Boolean {
            if (StringUtils.isEmpty(str)) {
                return false
            }
            val text = str!!
            for (str2 in arrayOf("url", "src", "href", "-original", "-play")) {
                if (text.contains(str2)) {
                    return true
                }
            }
            return false
        }

        private fun parseHikerToJq(parse: String, first: Boolean): String {
            /*
         海阔解析表达式转原生表达式,自动补eq,如果传了first就最后一个也取eq(0)
        :param parse:
        :param first:
        :return:
        */
            // 不自动加eq下标索引
            var result = parse
            if (result.contains("&&")) {
                val parses = RegexUtils.getPattern("&&").split(result) //带&&的重新拼接
                val new_parses = ArrayList<String>() //构造新的解析表达式列表
                for (i in parses.indices) {
                    val pss = RegexUtils.getPattern(" ").split(parses[i])
                    val ps = pss[pss.size - 1] //如果分割&&后带空格就取最后一个元素
                    val m = NOADD_INDEX.matcher(ps)
                    //if (!isIndex(ps)) {
                    if (!m.find()) {
                        if (!first && i >= parses.size - 1) { //不传first且遇到最后一个,不用补eq(0)
                            new_parses.add(parses[i])
                        } else {
                            new_parses.add(parses[i] + ":eq(0)")
                        }
                    } else {
                        new_parses.add(parses[i])
                    }
                }
                result = TextUtils.join(" ", new_parses)
            } else {
                val pss = RegexUtils.getPattern(" ").split(result)
                val ps = pss[pss.size - 1] //如果分割&&后带空格就取最后一个元素
                val m = NOADD_INDEX.matcher(ps)
                //if (!isIndex(ps) && first) {
                if (!m.find() && first) {
                    result += ":eq(0)"
                }
            }
            return result
        }

        @JvmStatic
        fun parseDomForUrl(html: String, rule: String, add_url: String): String {
            if (pdfh_html != html) {
                pdfh_html = html
                pdfh_doc = Jsoup.parse(html)
            }
            val doc = pdfh_doc
            if (rule == "body&&Text" || rule == "Text") {
                return doc!!.text()
            } else if (rule == "body&&Html" || rule == "Html") {
                return doc!!.html()
            }
            var option = ""
            var currentRule = rule
            if (currentRule.contains("&&")) {
                val rs = RegexUtils.getPattern("&&").split(currentRule)
                option = rs[rs.size - 1]
                val excludes = ArrayList(rs.toList())
                excludes.removeAt(rs.size - 1)
                currentRule = TextUtils.join("&&", excludes)
            }
            currentRule = parseHikerToJq(currentRule, true)
            val parses = RegexUtils.getPattern(" ").split(currentRule)
            var ret = Elements()
            for (nparse in parses) {
                ret = parseOneRule(doc!!, nparse, ret)
                if (ret.isEmpty()) {
                    return ""
                }
            }
            var result: String
            if (StringUtils.isNotEmpty(option)) {
                if (option == "Text") {
                    result = ret.text()
                } else if (option == "Html") {
                    result = ret.html()
                } else {
                    result = ret.attr(option)
                    if (option.lowercase(Locale.getDefault()).contains("style") && result.contains("url(")) {
                        val m = p.matcher(result)
                        if (m.find()) {
                            result = m.group(1)
                        }
                        if (StringUtils.isNotEmpty(result)) {
                            result = result.replace(Regex("^['|\"](.*)['|\"]$"), "\$1")
                        }
                    }
                    if (StringUtils.isNotEmpty(result) && StringUtils.isNotEmpty(add_url)) {
                        // 需要自动urljoin的属性
                        val m = URLJOIN_ATTR.matcher(option)
                        val n = SPECIAL_URL.matcher(result)
                        //if (isUrl(option)) {
                        if (m.find() && !n.find()) {
                            if (result.contains("http")) {
                                result = result.substring(result.indexOf("http"))
                            } else {
                                result = joinUrl(add_url, result)
                            }
                        }
                    }
                }
            } else {
                result = ret.outerHtml()
            }
            return result
        }

        @JvmStatic
        fun parseDomForArray(html: String, rule: String): List<String> {
            if (pdfa_html != html) {
                pdfa_html = html
                pdfa_doc = Jsoup.parse(html)
            }
            val doc = pdfa_doc
            val currentRule = parseHikerToJq(rule, false)
            val parses = RegexUtils.getPattern(" ").split(currentRule)
            var ret = Elements()
            for (pars in parses) {
                ret = parseOneRule(doc!!, pars, ret)
                if (ret.isEmpty()) {
                    return ArrayList()
                }
            }

            val eleHtml = ArrayList<String>()
            for (i in 0 until ret.size) {
                val element1 = ret[i]
                eleHtml.add(element1.outerHtml())
            }
            return eleHtml
        }

        private fun parseOneRule(doc: Document, nparse: String, ret: Elements): Elements {
            val painfo = getParseInfo(nparse)
            var result: Elements
            result = if (ret.isEmpty()) {
                doc.select(painfo.nparse_rule!!)
            } else {
                ret.select(painfo.nparse_rule!!)
            }

            if (nparse.contains(":eq")) {
                result = if (painfo.nparse_index < 0) {
                    result.eq(result.size + painfo.nparse_index)
                } else {
                    result.eq(painfo.nparse_index)
                }
            }

            val excludes = painfo.excludes
            if (excludes != null && !result.isEmpty()) {
                result = result.clone() //克隆一个, 免得直接remove会影响doc的缓存
                for (i in excludes.indices) {
                    result.select(excludes[i]).remove()
                }
            }
            return result
        }

        @JvmStatic
        fun parseDomForList(html: String, p1: String, list_text: String, list_url: String, add_url: String): List<String> {
            if (pdfa_html != html) {
                pdfa_html = html
                pdfa_doc = Jsoup.parse(html)
            }
            val doc = pdfa_doc
            val rule = parseHikerToJq(p1, false)
            val parses = RegexUtils.getPattern(" ").split(rule)
            var ret = Elements()
            for (pars in parses) {
                ret = parseOneRule(doc!!, pars, ret)
                if (ret.isEmpty()) {
                    return ArrayList()
                }
            }
            val new_vod_list = ArrayList<String>()
            for (i in 0 until ret.size) {
                val it = ret[i].outerHtml()
                new_vod_list.add(parseDomForUrl(it, list_text, "").trim() + '$' + parseDomForUrl(it, list_url, add_url))
            }
            return new_vod_list
        }
    }
}
