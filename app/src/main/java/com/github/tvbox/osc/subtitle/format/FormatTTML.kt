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

            //we recover the metadata
            var node: Node? = doc.getElementsByTagName("ttm:title").item(0)
            if (node != null) tto.title = node.textContent
            node = doc.getElementsByTagName("ttm:copyright").item(0)
            if (node != null) tto.copyrigth = node.textContent
            node = doc.getElementsByTagName("ttm:desc").item(0)
            if (node != null) tto.description = node.textContent

            //we recover the styles
            val styleN: NodeList = doc.getElementsByTagName("style")
            //we recover the timed text elements
            val captionsN: NodeList = doc.getElementsByTagName("p")
            //regions of the layout could also be recovered this way

            tto.warnings += "Styling attributes are only recognized inside a style definition, to be referenced later in the captions.\n\n"
            //we parse the styles
            for (i in 0 until styleN.length) {
                var style = Style(Style.defaultID())
                node = styleN.item(i)
                val attr: NamedNodeMap = node!!.attributes
                //we get the id
                var currentAtr: Node? = attr.getNamedItem("id")
                if (currentAtr != null)
                    style.iD = currentAtr.nodeValue
                currentAtr = attr.getNamedItem("xml:id")
                if (currentAtr != null)
                    style.iD = currentAtr.nodeValue

                //we get the style it may be based upon
                currentAtr = attr.getNamedItem("style")
                if (currentAtr != null)
                    if (tto.styling!!.containsKey(currentAtr.nodeValue))
                            style = Style(style.iD, tto.styling!!.get(currentAtr.nodeValue)!!)

                //we check for background color
                currentAtr = attr.getNamedItem("tts:backgroundColor")
                if (currentAtr != null)
                    style.backgroundColor = parseColor(currentAtr.nodeValue, tto)

                //we check for color
                currentAtr = attr.getNamedItem("tts:color")
                if (currentAtr != null)
                    style.color = parseColor(currentAtr.nodeValue, tto)

                //we check for font family
                currentAtr = attr.getNamedItem("tts:fontFamily")
                if (currentAtr != null)
                    style.font = currentAtr.nodeValue

                //we check for font size
                currentAtr = attr.getNamedItem("tts:fontSize")
                if (currentAtr != null)
                    style.fontSize = currentAtr.nodeValue

                //we check for italics
                currentAtr = attr.getNamedItem("tts:fontStyle")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("italic", ignoreCase = true) || currentAtr.nodeValue.equals("oblique", ignoreCase = true))
                        style.italic = true
                    else if (currentAtr.nodeValue.equals("normal", ignoreCase = true))
                        style.italic = false

                //we check for bold
                currentAtr = attr.getNamedItem("tts:fontWeight")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("bold", ignoreCase = true))
                        style.bold = true
                    else if (currentAtr.nodeValue.equals("normal", ignoreCase = true))
                        style.bold = false

                //we check opacity (to set the alpha)
                currentAtr = attr.getNamedItem("tts:opacity")
                if (currentAtr != null) {
                    try {
                        //a number between 1.0 and 0
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
                        //ignore the alpha
                        LOG.d("FormatTTML", "alpha parse failed, keep color without alpha")
                    }
                }

                //we check for text align
                currentAtr = attr.getNamedItem("tts:textAlign")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("left", ignoreCase = true) || currentAtr.nodeValue.equals("start", ignoreCase = true))
                        style.textAlign = "bottom-left"
                    else if (currentAtr.nodeValue.equals("right", ignoreCase = true) || currentAtr.nodeValue.equals("end", ignoreCase = true))
                        style.textAlign = "bottom-right"

                //we check for underline
                currentAtr = attr.getNamedItem("tts:textDecoration")
                if (currentAtr != null)
                    if (currentAtr.nodeValue.equals("underline", ignoreCase = true))
                        style.underline = true
                    else if (currentAtr.nodeValue.equals("noUnderline", ignoreCase = true))
                        style.underline = false

                //we add the style
                tto.styling!!.put(style.iD, style)
            }

            //we parse the captions
            for (i in 0 until captionsN.length) {
                val caption = Subtitle()
                caption.content = ""
                var validCaption = true
                node = captionsN.item(i)

                val attr: NamedNodeMap = node!!.attributes
                //we get the begin time
                var currentAtr: Node? = attr.getNamedItem("begin")
                //if no begin is present, 0 is assumed
                caption.start = Time("", "")
                caption.end = Time("", "")
                if (currentAtr != null)
                    caption.start!!.mseconds = parseTimeExpression(currentAtr.nodeValue, tto, doc)

                //we get the end time, if present, duration is ignored, otherwise end is calculated from duration
                currentAtr = attr.getNamedItem("end")
                if (currentAtr != null)
                    caption.end!!.mseconds = parseTimeExpression(currentAtr.nodeValue, tto, doc)
                else {
                    currentAtr = attr.getNamedItem("dur")
                    if (currentAtr != null)
                        caption.end!!.mseconds = caption.start!!.mseconds + parseTimeExpression(currentAtr.nodeValue, tto, doc)
                    else
                        //no end or duration, invalid format, caption is discarded
                        validCaption = false
                }

                //we get the style
                currentAtr = attr.getNamedItem("style")
                if (currentAtr != null) {
                    val style = tto.styling!!.get(currentAtr.nodeValue)
                    if (style != null)
                        caption.style = style
                    else
                        //unrecognized style
                        tto.warnings += "unrecoginzed style referenced: " + currentAtr.nodeValue + "\n\n"
                }

                //we save the text
                val textN: NodeList = node.childNodes
                for (j in 0 until textN.length) {
                    if (textN.item(j).nodeName.equals("#text"))
                        caption.content += textN.item(j).textContent.trim { it <= ' ' }
                    else if (textN.item(j).nodeName.equals("br"))
                        caption.content += "<br />"

                }
                //is this check worth it?
                if (caption.content!!.replace(Regex("<br />"), "").trim { it <= ' ' }.isEmpty())
                    validCaption = false

                //and save the caption
                if (validCaption) {
                    var key = caption.start!!.mseconds
                    //in case the key is already there, we increase it by a millisecond, since no duplicates are allowed
                    while (tto.captions!!.containsKey(key)) key++
                    tto.captions!!.put(key, caption)
                }

            }


        } catch (e: Exception) {
            LOG.e("FormatTTML", e)
            //this could be a fatal error...
            throw FatalParsingException("Error during parsing: " + e.message)
        }


        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): Array<String>? {

        //first we check if the TimedTextObject had been built, otherwise...
        if (!tto.built)
            return null

        //we will write the lines in an ArrayList 
        var index = 0
        //the minimum size of the file is the number of captions and styles + lines for sections and formats and the metadata, so we'll take some extra space.
        val file = ArrayList<String>(30 + tto.styling!!.size + tto.captions!!.size)

        //identification line is placed
        file.add(index++, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        //root element is placed
        file.add(index++, "<tt xml:lang=\"" + tto.language + "\" xmlns=\"http://www.w3.org/ns/ttml\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\">")
        //head
        file.add(index++, "\t<head>")
        //metadata
        file.add(index++, "\t\t<metadata xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">")
        //title
        var title: String?
        if (tto.title == null || tto.title!!.isEmpty())
            title = tto.fileName
        else title = tto.title
        file.add(index++, "\t\t\t<ttm:title>" + title + "</ttm:title>")
        //Copyright
        if (tto.copyrigth != null && !tto.copyrigth!!.isEmpty())
            file.add(index++, "\t\t\t<ttm:copyright>" + tto.copyrigth + "</ttm:copyright>")
        //additional info
        var desc = "Converted by the Online Subtitle Converter developed by J. David Requejo\n"
        if (tto.author != null && !tto.author!!.isEmpty())
            desc += "\n Original file by: " + tto.author + "\n"
        if (tto.description != null && !tto.description!!.isEmpty())
            desc += tto.description + "\n"
        file.add(index++, "\t\t\t<ttm:desc>" + desc + "\t\t\t</ttm:desc>")

        //metadata closes
        file.add(index++, "\t\t</metadata>")
        //styling opens
        file.add(index++, "\t\t<styling>")

        var line: String
        //Next we iterate over the styles
        val itrS = tto.styling!!.values.iterator()
        while (itrS.hasNext()) {
            val style = itrS.next()
            //we add the attributes
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
            //style is ready, we close it.
            line += " />"
            //we insert it
            file.add(index++, line)
        }

        //styling closes
        file.add(index++, "\t\t</styling>")

        //head closes
        file.add(index++, "\t</head>")
        //body opens
        file.add(index++, "\t<body>")
        //unique div opens
        file.add(index++, "\t\t<div>")

        //Next we iterate over the captions
        val itrC = tto.captions!!.values.iterator()
        while (itrC.hasNext()) {
            val caption = itrC.next()
            //we open the subtitle line
            line = "\t\t\t<p begin=\"" + caption.start!!.getTime("hh:mm:ss,ms").replace(',', '.') + "\""
            line += " end=\"" + caption.end!!.getTime("hh:mm:ss,ms").replace(',', '.') + "\""
            if (caption.style != null)
                line += " style=\"" + caption.style!!.iD + "\""
            //attributes are done being inserted, if region was implemented it should be added before this.
            line += " >" + caption.content + "</p>\n"
            //we write the line
            file.add(index++, line)
        }

        //unique div closes
        file.add(index++, "\t\t</div>")
        //body closes
        file.add(index++, "\t</body>")
        //root closes
        file.add(index++, "</tt>")

        //an empty line is added
        file.add(index++, "")

        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }


    /* PRIVATE METHODS */

    /**
     * Identifies the color expression and obtains the RGBA equivalent value.
     * 
     * @param color
     * @return
     */
    private fun parseColor(color: String, tto: TimedTextObject): String {
        var value: String? = ""
        var values: Array<String>
        if (color.startsWith("#")) {
            if (color.length == 7)
                value = color.substring(1) + "ff"
            else if (color.length == 9)
                value = color.substring(1)
            else {
                //unrecognized format
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
            //it should be a named color so...
            value = Style.getRGBValue("name", color)
            //if not recognized named color
            if (value == null || value.isEmpty()) {
                value = "ffffffff"
                tto.warnings += "Unrecoginzed color: " + color + "\n\n"
            }
        }

        return value!!
    }


    /**
     * returns the number of milliseconds equivalent to this time expression
     * 
     * @param timeExpression
     * @return
     */
    private fun parseTimeExpression(timeExpression: String, tto: TimedTextObject, doc: Document): Int {
        var timeExpression = timeExpression
        var mSeconds = 0
        if (timeExpression.contains(":")) {
            //it is a clock time
            val parts = RegexUtils.getPattern(":").split(timeExpression)
            if (parts.size == 3) {
                //we have h:m:s.fraction
                var h: Int
                var m: Int
                var s: Float
                h = parts[0].toInt()
                m = parts[1].toInt()
                s = parts[2].toFloat()
                mSeconds = h * 3600000 + m * 60000 + (s * 1000).toInt()
            } else if (parts.size == 4) {
                //we have h:m:s:f.fraction�
                var h: Int
                var m: Int
                var s: Int
                var f: Float
                var frameRate = 25
                //we recover the frame rate
                val n: Node? = doc.getElementsByTagName("ttp:frameRate").item(0)
                if (n != null) {
                    //used as auxiliary string
                    val aux = n.nodeValue
                    try {
                        frameRate = aux.toInt()
                    } catch (e: NumberFormatException) {
                        //should not happen, but if it does, use default value...
                        LOG.d("FormatTTML", "invalid ttp:frameRate, use default 25")
                    }
                }
                h = parts[0].toInt()
                m = parts[1].toInt()
                s = parts[2].toInt()
                f = parts[3].toFloat()
                mSeconds = h * 3600000 + m * 60000 + s * 1000 + (f * 1000 / frameRate).toInt()
            } else {
                //unrecognized  clock time format
            }

        } else {
            //it is an offset - time, this is composed of a number and a metric
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
                    //we recover the frame rate
                    val n: Node? = doc.getElementsByTagName("ttp:frameRate").item(0)
                    if (n != null) {
                        //used as auxiliary string
                        val s = n.nodeValue
                        frameRate = s.toInt()
                        mSeconds = (time * 1000 / frameRate).toInt()
                    }

                } else if (metric.equals("t", ignoreCase = true)) {
                    var tickRate: Int
                    //we recover the tick rate
                    val n: Node? = doc.getElementsByTagName("ttp:tickRate").item(0)
                    if (n != null) {
                        //used as auxiliary string
                        val s = n.nodeValue
                        tickRate = s.toInt()
                        mSeconds = (time * 1000 / tickRate).toInt()
                    }


                } else {
                    //invalid metric

                }
            } catch (e: NumberFormatException) {
                //incorrect format for offset time
                LOG.d("FormatTTML", "offset time parse failed, ignored")
            }
        }

        return mSeconds
    }

}
