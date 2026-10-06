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

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.subtitle.exception.FatalParsingException
import com.github.tvbox.osc.subtitle.model.Style
import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.subtitle.model.Time
import com.github.tvbox.osc.subtitle.model.TimedTextObject
import com.github.tvbox.osc.util.RegexUtils

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class FormatSTL : TimedTextFileFormat {

    @Throws(IOException::class, FatalParsingException::class)
    override fun parseFile(fileName: String, `is`: InputStream): TimedTextObject {

        val tto = TimedTextObject()
        tto.fileName = fileName

        val gsiBlock = ByteArray(1024)
        val ttiBlock = ByteArray(128)

        try {
            createSTLStyles(tto)

            var bytesRead: Int
            bytesRead = `is`.read(gsiBlock)
            if (bytesRead < 1024)
                throw FatalParsingException("The file must contain at least a GSI block")
            val dfc = byteArrayOf(gsiBlock[6], gsiBlock[7])
            val fps = String(dfc, Charset.defaultCharset()).toInt()
            val cct = byteArrayOf(gsiBlock[12], gsiBlock[13])
            val table = String(cct, Charset.defaultCharset()).toInt()
            val opt = ByteArray(32)
            System.arraycopy(gsiBlock, 16, opt, 0, 32)
            val title = String(opt, Charset.defaultCharset())
            val oet = ByteArray(32)
            System.arraycopy(gsiBlock, 48, oet, 0, 32)
            val episodeTitle = String(oet, Charset.defaultCharset())
            val tnb = byteArrayOf(gsiBlock[238], gsiBlock[239], gsiBlock[240], gsiBlock[241], gsiBlock[242])
            val numberOfTTIBlocks = String(tnb, Charset.defaultCharset()).toInt()
            val tns = byteArrayOf(gsiBlock[243], gsiBlock[244], gsiBlock[245], gsiBlock[246], gsiBlock[247])
            val numberOfSubtitles = String(tns, Charset.defaultCharset()).toInt()

            tto.title = (title.trim { it <= ' ' } + " " + episodeTitle.trim { it <= ' ' }).trim { it <= ' ' }
            if (table > 4 || table < 0)
                tto.warnings += "Invalid Character Code table number, corrupt data? will try to parse anyways assuming it is latin.\n\n"
            else if (table != 0)
                tto.warnings += "Only latin alphabet supported for import from STL, other languages may produce unexpected results.\n\n"

            var subtitleNumber = 0
            var additionalText = false
            var currentCaption: Subtitle? = null
            for (i in 0 until numberOfTTIBlocks) {
                bytesRead = `is`.read(ttiBlock)
                if (bytesRead < 128) {
                    tto.warnings += "Unexpected end of file, " + i + " blocks read, expecting " + numberOfTTIBlocks + " blocks in total.\n\n"
                    break
                }

                if (!additionalText)
                    currentCaption = Subtitle()

                val currentSubNumber = ttiBlock[1] + 256 * ttiBlock[2]
                if (currentSubNumber != subtitleNumber)
                    tto.warnings += "Unexpected subtitle number at TTI block " + i + ". Parsing proceeds...\n\n"
                val ebn = ttiBlock[3].toInt()
                if (ebn != -1)
                    additionalText = true
                else additionalText = false

                val startTime = "" + ttiBlock[5] + ":" + ttiBlock[6] + ":" + ttiBlock[7] + ":" + ttiBlock[8]
                val endTime = "" + ttiBlock[9] + ":" + ttiBlock[10] + ":" + ttiBlock[11] + ":" + ttiBlock[12]
                val justification = ttiBlock[14].toInt()
                if (ttiBlock[15].toInt() == 0) {
                    val textField = ByteArray(112)
                    System.arraycopy(ttiBlock, 16, textField, 0, 112)

                    if (additionalText)
                        parseTextForSTL(currentCaption!!, textField, justification, tto)
                    else {
                        currentCaption!!.start = Time("h:m:s:f/fps", startTime + "/" + fps)
                        currentCaption.end = Time("h:m:s:f/fps", endTime + "/" + fps)
                        parseTextForSTL(currentCaption, textField, justification, tto)
                    }
                }
                if (!additionalText)
                    subtitleNumber++

            }
            if (subtitleNumber != numberOfSubtitles)
                tto.warnings += "Number of parsed subtitles (" + subtitleNumber + ") different from expected number of subtitles (" + numberOfSubtitles + ").\n\n"

            `is`.close()

            tto.cleanUnusedStyles()

        } catch (e: Exception) {
            LOG.e("FormatSTL", e)
            throw FatalParsingException("Format error in the file, migth be due to corrupt data.\n" + e.message)
        }

        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): ByteArray? {

        if (!tto.built)
            return null

        var currentC: Subtitle

        val gsiBlock = ByteArray(1024)
        var ttiBlock = ByteArray(128)

        val file = ByteArray(1024 + 128 * tto.captions!!.size)

        var extra = "850STL25.0110000".toByteArray(Charset.defaultCharset())
        System.arraycopy(extra, 0, gsiBlock, 0, extra.size)
        extra = if (tto.title != null) tto.title!!.toByteArray(Charset.defaultCharset()) else tto.fileName!!.toByteArray(Charset.defaultCharset())
        for (i in 0 until 224 - 16) {
            if (i < extra.size)
                gsiBlock[i + 16] = extra[i]
            else
                gsiBlock[i + 16] = 32

        }
        val dateFormat: DateFormat = SimpleDateFormat("yyMMdd")
        val date = Date()
        var aux = dateFormat.format(date)
        aux += aux + "00"
        var aux2 = "" + tto.captions!!.size
        while (aux2.length < 5) aux2 = "0" + aux2
        aux += aux2 + aux2 + "0013216100000000"
        aux += tto.captions!!.get(tto.captions!!.firstKey())!!.start!!.getTime("hhmmssff/25")
        aux += "11OOO"
        extra = aux.toByteArray(Charset.defaultCharset())
        System.arraycopy(extra, 0, gsiBlock, 224, extra.size)
        for (i in 277 until 1024) {
            gsiBlock[i] = 32
        }

        System.arraycopy(gsiBlock, 0, file, 0, gsiBlock.size)

        val itrC = tto.captions!!.values.iterator()
        var subtitleNumber = 0
        while (itrC.hasNext()) {
            currentC = itrC.next()
            ttiBlock[0] = 0
            ttiBlock[1] = (subtitleNumber % 256).toByte()
            ttiBlock[2] = (subtitleNumber / 256).toByte()
            ttiBlock[3] = 0xff.toByte()
            ttiBlock[4] = 0
            var timeCode = RegexUtils.getPattern(":").split(currentC.start!!.getTime("h:m:s:f/25"))
            ttiBlock[5] = timeCode[0].toByte()
            ttiBlock[6] = timeCode[1].toByte()
            ttiBlock[7] = timeCode[2].toByte()
            ttiBlock[8] = timeCode[3].toByte()
            timeCode = RegexUtils.getPattern(":").split(currentC.end!!.getTime("h:m:s:f/25"))
            ttiBlock[9] = timeCode[0].toByte()
            ttiBlock[10] = timeCode[1].toByte()
            ttiBlock[11] = timeCode[2].toByte()
            ttiBlock[12] = timeCode[3].toByte()
            ttiBlock[13] = 18
            if (currentC.style != null) {
                if (currentC.style!!.textAlign!!.contains("left"))
                    ttiBlock[14] = 1
                else if (currentC.style!!.textAlign!!.contains("right"))
                    ttiBlock[14] = 3
            } else ttiBlock[14] = 2
            ttiBlock[15] = 0
            val lines = RegexUtils.getPattern("<br />").split(currentC.content!!)
            var pos = 16
            for (i in 0 until lines.size)
                lines[i] = lines[i].replace(Regex("\\<.*?\\>"), "")
            if (currentC.style != null) {
                val style = currentC.style!!
                if (style.italic)
                    ttiBlock[pos++] = 0x80.toByte()
                else ttiBlock[pos++] = 0x81.toByte()
                if (style.underline)
                    ttiBlock[pos++] = 0x82.toByte()
                else ttiBlock[pos++] = 0x83.toByte()

                val color = style.color!!.substring(0, 6)
                if (color.equals("000000", ignoreCase = true))
                    ttiBlock[pos++] = 0x00.toByte()
                else if (color.equals("0000ff", ignoreCase = true))
                    ttiBlock[pos++] = 0x04.toByte()
                else if (color.equals("00ffff", ignoreCase = true))
                    ttiBlock[pos++] = 0x06.toByte()
                else if (color.equals("00ff00", ignoreCase = true))
                    ttiBlock[pos++] = 0x02.toByte()
                else if (color.equals("ff0000", ignoreCase = true))
                    ttiBlock[pos++] = 0x01.toByte()
                else if (color.equals("ffff00", ignoreCase = true))
                    ttiBlock[pos++] = 0x03.toByte()
                else if (color.equals("ff00ff", ignoreCase = true))
                    ttiBlock[pos++] = 0x05.toByte()
                else ttiBlock[pos++] = 0x07.toByte()

            }

            for (i in 0 until lines.size) {

                val chars = lines[i].toCharArray()
                for (j in 0 until chars.size) {
                    if (pos > 126)
                        break
                    if (chars[j].code >= 0x20 && chars[j].code <= 0x7f)
                        ttiBlock[pos++] = chars[j].code.toByte()
                }

                if (i + 1 < lines.size)
                    ttiBlock[pos++] = 0x8A.toByte()
            }

            while (pos < 128)
                ttiBlock[pos++] = 0x8F.toByte()

            System.arraycopy(ttiBlock, 0, file, 1024 + subtitleNumber * 128, ttiBlock.size)
            ttiBlock = ByteArray(128)
            subtitleNumber++
        }

        return file
    }

    private fun parseTextForSTL(currentCaption: Subtitle, textField: ByteArray, justification: Int, tto: TimedTextObject) {

        var italics = false
        var underline = false
        var color = "white"
        var style: Style?
        var text = ""

        var i = 0
        while (i < textField.size) {

            if (textField[i].toInt() < 0) {
                if (textField[i].toInt() <= -113) {
                    if (i + 1 < textField.size && textField[i] == textField[i + 1])
                        i++
                    when (textField[i].toInt()) {
                        -128 -> {
                            italics = true
                        }
                        -127 -> {
                            italics = false
                        }
                        -126 -> {
                            underline = true
                        }
                        -125 -> {
                            underline = false
                        }
                        -124 -> {
                        }
                        -123 -> {
                        }
                        -118 -> {
                            currentCaption.content += text + "<br />"
                            text = ""
                        }
                        -113 -> {
                            currentCaption.content += text
                            text = ""
                            if (underline)
                                color += "U"
                            if (italics)
                                color += "I"
                            style = tto.styling!!.get(color)

                            if (justification == 1) {
                                color += "L"
                                if (tto.styling!!.get(color) == null) {
                                    style = Style(color, style!!)
                                    style.textAlign = "bottom-left"
                                    tto.styling!!.put(color, style)
                                } else
                                    style = tto.styling!!.get(color)
                            } else if (justification == 3) {
                                color += "R"
                                if (tto.styling!!.get(color) == null) {
                                    style = Style(color, style!!)
                                    style.textAlign = "bottom-rigth"
                                    tto.styling!!.put(color, style)
                                } else
                                    style = tto.styling!!.get(color)
                            }

                            currentCaption.style = style
                            var key = currentCaption.start!!.mseconds
                            while (tto.captions!!.containsKey(key)) key++
                            tto.captions!!.put(key, currentCaption)
                            i = textField.size
                        }
                        else -> {
                        }
                    }
                } else {
                }

            } else if (textField[i].toInt() < 32) {
                if (i + 1 < textField.size && textField[i] == textField[i + 1])
                    i++
                when (textField[i].toInt()) {
                    7 -> {
                        color = "white"
                    }
                    2 -> {
                        color = "green"
                    }
                    4 -> {
                        color = "blue"
                    }
                    6 -> {
                        color = "cyan"
                    }
                    1 -> {
                        color = "red"
                    }
                    3 -> {
                        color = "yellow"
                    }
                    5 -> {
                        color = "magenta"
                    }
                    0 -> {
                        color = "black"
                    }
                    else -> {
                    }
                }

            } else {
                val x = byteArrayOf(textField[i])
                text += String(x, Charset.defaultCharset())
            }

            i++
        }

    }

    private fun createSTLStyles(tto: TimedTextObject) {
        var style: Style

        style = Style("white")
        style.color = Style.getRGBValue("name", "white")
        tto.styling!!.put(style.iD, style)

        style = Style("whiteU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("whiteUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("whiteI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("green")
        style.color = Style.getRGBValue("name", "green")
        tto.styling!!.put(style.iD, style)

        style = Style("greenU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("greenUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("greenI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("blue")
        style.color = Style.getRGBValue("name", "blue")
        tto.styling!!.put(style.iD, style)

        style = Style("blueU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("blueUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("blueI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("cyan")
        style.color = Style.getRGBValue("name", "cyan")
        tto.styling!!.put(style.iD, style)

        style = Style("cyanU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("cyanUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("cyanI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("red")
        style.color = Style.getRGBValue("name", "red")
        tto.styling!!.put(style.iD, style)

        style = Style("redU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("redUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("redI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("yellow")
        style.color = Style.getRGBValue("name", "yellow")
        tto.styling!!.put(style.iD, style)

        style = Style("yellowU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("yellowUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("yellowI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("magenta")
        style.color = Style.getRGBValue("name", "magenta")
        tto.styling!!.put(style.iD, style)

        style = Style("magentaU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("magentaUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("magentaI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

        style = Style("black")
        style.color = Style.getRGBValue("name", "black")
        tto.styling!!.put(style.iD, style)

        style = Style("blackU", style)
        style.underline = true
        tto.styling!!.put(style.iD, style)

        style = Style("blackUI", style)
        style.italic = true
        tto.styling!!.put(style.iD, style)

        style = Style("blackI", style)
        style.underline = false
        tto.styling!!.put(style.iD, style)

    }

}
