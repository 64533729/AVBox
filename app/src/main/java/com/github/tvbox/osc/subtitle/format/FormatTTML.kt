/**
 * Class that represents the .ASS and .SSA subtitle file format
 *
 * <br><br>
 * Copyright (c) 2012 J. David Requejo <br>
 * j[dot]david[dot]requejo[at] Gmail
 * <br><br>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 * <br><br>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <br><br>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * @author J. David REQUEJO
 *
 */

package com.github.tvbox.osc.subtitle.format

import com.github.tvbox.osc.subtitle.exception.FatalParsingException
import com.github.tvbox.osc.subtitle.model.Style
import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.subtitle.model.Time
import com.github.tvbox.osc.subtitle.model.TimedTextObject
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.RegexUtils

import org.w3c.dom.Document
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList

import java.io.IOException
import java.io.InputStream
import java.util.ArrayList

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

class FormatTTML : TimedTextFileFormat {

    @Throws(IOException::class, FatalParsingException::class)
    override fun parseFile(fileName: String, `is`: InputStream): TimedTextObject {

        val tto = TimedTextObject()
        tto.fileName = fileName

        val dbFactory = DocumentBuilderFactory.newInstance()
        var dBuilder: DocumentBuilder
        try {
            dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(`is`)
            doc.documentElement.normalize()

            var node: Node? = doc.getElementsByTagName("ttm:title").item(0)
            if (node != null) tto.title = node.textContent
            node = doc.getElementsByTagName("ttm:copyright").item(0)
            if (node != null) tto.copyrigth = node.textContent
            node = doc.getElementsByTagName("ttm:desc").item(0)
            if (node != null) tto.description = node.textContent

            val styleN: NodeList = doc.getElementsByTagName("style")
            val captionsN: NodeList = doc.getElementsByTagName("p")

            tto.warnings += "Styling attributes are only recognized inside a style definition, to be referenced later in the captions.\n\n"
            for (i in 0 until styleN.length) {
                var style = Style(Style.defaultID())
                node = styleN.item(i)
                val attr: NamedNodeMap = node!!.attributes
                var currentAtr: Node? = attr.getNamedItem("id")
                if (currentAtr != null)
                    style.iD = currentAtr.nodeValue
                currentAtr = attr.getNamedItem("xml:id")
                if (currentAtr != null)
                    style.iD = currentAtr.nodeValue

                currentAtr = attr.getNamedItem("style")
                if (currentAtr != null)
                    if (tto.styling!!.containsKey(currentAtr.nodeValue))
                            style = Style(style.iD, tto.styling!!.get(currentAtr.nodeValue)!!)

                currentAtr = attr.getNamedItem("tts:backgroundColor")
                if (currentAtr != null)
                    style.backgroundColor = parseColor(currentAtr.nodeValue, tto)

                currentAtr = attr.getNamedItem("tts:color")
                if (currentAtr != null)
                    style.color = parseColor(currentAtr.nodeValue, tto)

                currentAtr = attr.getNamedItem("tts:fontFamily")
                if (currentAtr != null)
                    style.font = currentAtr.nodeValue

                currentAtr = attr.getNamedItem("tts:fontSize")
                if (currentAtr != null)
                    style.fontSize = currentAtr.nodeValue

                currentAtr = attr.getNamedItem("tts:fontStyle")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("italic", ignoreCase = true) || currentAtr.nodeValue.equals("oblique", ignoreCase = true))
                        style.italic = true
                    else if (currentAtr.nodeValue.equals("normal", ignoreCase = true))
                        style.italic = false

                currentAtr = attr.getNamedItem("tts:fontWeight")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("bold", ignoreCase = true))
                        style.bold = true
                    else if (currentAtr.nodeValue.equals("normal", ignoreCase = true))
                        style.bold = false

                currentAtr = attr.getNamedItem("tts:opacity")
                if (currentAtr != null) {
                    try {
                        var alpha = currentAtr.nodeValue.toFloat()
                        if (alpha > 1)
                            alpha = 1f
                        else if (alpha < 0)
                            alpha = 0f

                        var aa = Integer.toHexString((alpha * 255).toInt())
                        if (aa.length < 2)
                            aa = "0" + aa

                        style.color = style.color!!.substring(0, 6) + aa
                        style.backgroundColor = style.backgroundColor!!.substring(0, 6) + aa

                    } catch (e: NumberFormatException) {
                        LOG.d("FormatTTML", "alpha parse failed, keep color without alpha")
                    }
                }

                currentAtr = attr.getNamedItem("tts:textAlign")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("left", ignoreCase = true) || currentAtr.nodeValue.equals("start", ignoreCase = true))
                        style.textAlign = "bottom-left"
                    else if (currentAtr.nodeValue.equals("right", ignoreCase = true) || currentAtr.nodeValue.equals("end", ignoreCase = true))
                        style.textAlign = "bottom-right"

                currentAtr = attr.getNamedItem("tts:textDecoration")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("underline", ignoreCase = true))
                        style.underline = true
                    else if (currentAtr.nodeValue.equals("noUnderline", ignoreCase = true))
                        style.underline = false

                tto.styling!!.put(style.iD, style)
            }

            for (i in 0 until captionsN.length) {
                val caption = Subtitle()
                caption.content = ""
                var validCaption = true
                node = captionsN.item(i)

                val attr: NamedNodeMap = node!!.attributes
                var currentAtr: Node? = attr.getNamedItem("begin")
                caption.start = Time("", "")
                caption.end = Time("", "")
                if (currentAtr != null)
                    caption.start!!.mseconds = parseTimeExpression(currentAtr.nodeValue, tto, doc)

                currentAtr = attr.getNamedItem("end")
                if (currentAtr != null)
                    caption.end!!.mseconds = parseTimeExpression(currentAtr.nodeValue, tto, doc)
                else {
                    currentAtr = attr.getNamedItem("dur")
                    if (currentAtr != null)
                        caption.end!!.mseconds = caption.start!!.mseconds + parseTimeExpression(currentAtr.nodeValue, tto, doc)
                    else
                        validCaption = false
                }

                currentAtr = attr.getNamedItem("style")
                if (currentAtr != null) {
                    val style = tto.styling!!.get(currentAtr.nodeValue)
                    if (style != null)
                        caption.style = style
                    else
                        tto.warnings += "unrecoginzed style referenced: " + currentAtr.nodeValue + "\n\n"
                }

                val textN: NodeList = node.childNodes
                for (j in 0 until textN.length) {
                    if (textN.item(j).nodeName.equals("#text"))
                        caption.content += textN.item(j).textContent.trim { it <= ' ' }
                    else if (textN.item(j).nodeName.equals("br"))
                        caption.content += "<br />"

                }
                if (caption.content!!.replace(Regex("<br />"), "").trim { it <= ' ' }.isEmpty())
                    validCaption = false

                if (validCaption) {
                    var key = caption.start!!.mseconds
                    while (tto.captions!!.containsKey(key)) key++
                    tto.captions!!.put(key, caption)
                }

            }

        } catch (e: Exception) {
            LOG.e("FormatTTML", e)
            throw FatalParsingException("Error during parsing: " + e.message)
        }

        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): Array<String>? {

        if (!tto.built)
            return null

        var index = 0
        val file = ArrayList<String>(30 + tto.styling!!.size + tto.captions!!.size)

        file.add(index++, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        file.add(index++, "<tt xml:lang=\"" + tto.language + "\" xmlns=\"http://www.w3.org/ns/ttml\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\">")
        file.add(index++, "\t<head>")
        file.add(index++, "\t\t<metadata xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">")
        var title: String?
        if (tto.title == null || tto.title!!.isEmpty())
            title = tto.fileName
        else title = tto.title
        file.add(index++, "\t\t\t<ttm:title>" + title + "</ttm:title>")
        if (tto.copyrigth != null && !tto.copyrigth!!.isEmpty())
            file.add(index++, "\t\t\t<ttm:copyright>" + tto.copyrigth + "</ttm:copyright>")
        var desc = "Converted by the Online Subtitle Converter developed by J. David Requejo\n"
        if (tto.author != null && !tto.author!!.isEmpty())
            desc += "\n Original file by: " + tto.author + "\n"
        if (tto.description != null && !tto.description!!.isEmpty())
            desc += tto.description + "\n"
        file.add(index++, "\t\t\t<ttm:desc>" + desc + "\t\t\t</ttm:desc>")

        file.add(index++, "\t\t</metadata>")
        file.add(index++, "\t\t<styling>")

        var line: String
        val itrS = tto.styling!!.values.iterator()
        while (itrS.hasNext()) {
            val style = itrS.next()
            line = "\t\t\t<style xml:id=\"" + style.iD + "\""
            if (style.color != null)
                line += " tts:color=\"#" + style.color + "\""
            if (style.backgroundColor != null)
                line += " tts:backgroundColor=\"#" + style.backgroundColor + "\""
            if (style.font != null)
                line += " tts:fontFamily=\"" + style.font + "\""
            if (style.fontSize != null)
                line += " tts:fontSize=\"" + style.fontSize + "\""
            if (style.italic)
                line += " tts:fontStyle=\"italic\""
            if (style.bold)
                line += " tts:fontWeight=\"bold\""
            line += " tts:textAlign=\""
            if (style.textAlign!!.contains("left"))
                line += "left\""
            else if (style.textAlign!!.contains("right"))
                line += "rigth\""
            else line += "center\""
            if (style.underline)
                line += " tts:textDecoration=\"underline\""
            line += " />"
            file.add(index++, line)
        }

        file.add(index++, "\t\t</styling>")

        file.add(index++, "\t</head>")
        file.add(index++, "\t<body>")
        file.add(index++, "\t\t<div>")

        val itrC = tto.captions!!.values.iterator()
        while (itrC.hasNext()) {
            val caption = itrC.next()
            line = "\t\t\t<p begin=\"" + caption.start!!.getTime("hh:mm:ss,ms").replace(',', '.') + "\""
            line += " end=\"" + caption.end!!.getTime("hh:mm:ss,ms").replace(',', '.') + "\""
            if (caption.style != null)
                line += " style=\"" + caption.style!!.iD + "\""
            line += " >" + caption.content + "</p>\n"
            file.add(index++, line)
        }

        file.add(index++, "\t\t</div>")
        file.add(index++, "\t</body>")
        file.add(index++, "</tt>")

        file.add(index++, "")

        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }

    private fun parseColor(color: String, tto: TimedTextObject): String {
        var value: String? = ""
        var values: Array<String>
        if (color.startsWith("#")) {
            if (color.length == 7)
                value = color.substring(1) + "ff"
            else if (color.length == 9)
                value = color.substring(1)
            else {
                value = "ffffffff"
                tto.warnings += "Unrecoginzed format: " + color + "\n\n"
            }

        } else if (color.startsWith("rgb")) {
            var alpha = false
            if (color.startsWith("rgba"))
                    alpha = true
            try {
                values = RegexUtils.getPattern(",").split(RegexUtils.getPattern("\\(").split(color)[1])

                var r: Int
                var g: Int
                var b: Int
                var a = 255
                r = values[0].toInt()
                g = values[1].toInt()
                b = values[2].substring(0, 2).toInt()
                if (alpha) a = values[3].substring(0, 2).toInt()

                values[0] = Integer.toHexString(r)
                values[1] = Integer.toHexString(g)
                values[2] = Integer.toHexString(b)
                if (alpha) values[2] = Integer.toHexString(a)

                for (i in 0 until values.size) {
                    if (values[i].length < 2)
                        values[i] = "0" + values[i]
                    value += values[i]
                }

                if (!alpha)
                    value += "ff"

            } catch (e: Exception) {
                value = "ffffffff"
                tto.warnings += "Unrecoginzed color: " + color + "\n\n"
            }

        } else {
            value = Style.getRGBValue("name", color)
            if (value == null || value.isEmpty()) {
                value = "ffffffff"
                tto.warnings += "Unrecoginzed color: " + color + "\n\n"
            }
        }

        return value!!
    }

    private fun parseTimeExpression(timeExpression: String, tto: TimedTextObject, doc: Document): Int {
        var timeExpression = timeExpression
        var mSeconds = 0
        if (timeExpression.contains(":")) {
            val parts = RegexUtils.getPattern(":").split(timeExpression)
            if (parts.size == 3) {
                var h: Int
                var m: Int
                var s: Float
                h = parts[0].toInt()
                m = parts[1].toInt()
                s = parts[2].toFloat()
                mSeconds = h * 3600000 + m * 60000 + (s * 1000).toInt()
            } else if (parts.size == 4) {
                var h: Int
                var m: Int
                var s: Int
                var f: Float
                var frameRate = 25
                val n: Node? = doc.getElementsByTagName("ttp:frameRate").item(0)
                if (n != null) {
                    val aux = n.nodeValue
                    try {
                        frameRate = aux.toInt()
                    } catch (e: NumberFormatException) {
                        LOG.d("FormatTTML", "invalid ttp:frameRate, use default 25")
                    }
                }
                h = parts[0].toInt()
                m = parts[1].toInt()
                s = parts[2].toInt()
                f = parts[3].toFloat()
                mSeconds = h * 3600000 + m * 60000 + s * 1000 + (f * 1000 / frameRate).toInt()
            } else {
            }

        } else {
            val metric = timeExpression.substring(timeExpression.length - 1)
            timeExpression = timeExpression.substring(0, timeExpression.length - 1).replace(',', '.').trim { it <= ' ' }
            var time: Double
            try {
                time = timeExpression.toDouble()
                if (metric.equals("h", ignoreCase = true))
                    mSeconds = (time * 3600000).toInt()

                else if (metric.equals("m", ignoreCase = true))
                    mSeconds = (time * 60000).toInt()

                else if (metric.equals("s", ignoreCase = true))
                    mSeconds = (time * 1000).toInt()

                else if (metric.equals("ms", ignoreCase = true))
                    mSeconds = time.toInt()

                else if (metric.equals("f", ignoreCase = true)) {
                    var frameRate: Int
                    val n: Node? = doc.getElementsByTagName("ttp:frameRate").item(0)
                    if (n != null) {
                        val s = n.nodeValue
                        frameRate = s.toInt()
                        mSeconds = (time * 1000 / frameRate).toInt()
                    }

                } else if (metric.equals("t", ignoreCase = true)) {
                    var tickRate: Int
                    val n: Node? = doc.getElementsByTagName("ttp:tickRate").item(0)
                    if (n != null) {
                        val s = n.nodeValue
                        tickRate = s.toInt()
                        mSeconds = (time * 1000 / tickRate).toInt()
                    }

                } else {

                }
            } catch (e: NumberFormatException) {
                LOG.d("FormatTTML", "offset time parse failed, ignored")
            }
        }

        return mSeconds
    }

}
