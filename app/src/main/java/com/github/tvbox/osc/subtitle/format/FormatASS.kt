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

import com.github.tvbox.osc.subtitle.model.Style
import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.subtitle.model.Time
import com.github.tvbox.osc.subtitle.model.TimedTextObject
import com.github.tvbox.osc.util.RegexUtils

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList

class FormatASS : TimedTextFileFormat {

    @Throws(IOException::class)
    override fun parseFile(fileName: String, `is`: InputStream): TimedTextObject {

        val tto = TimedTextObject()
        tto.fileName = fileName

        var caption = Subtitle()
        var style: Style

        var timer = 100f

        var isASS = false

        var styleFormat: Array<String>
        var dialogueFormat: Array<String>

        val `in` = InputStreamReader(`is`)
        val br = BufferedReader(`in`)

        var line: String?
        var lineCounter = 0
        try {
            line = br.readLine()
            lineCounter++
            while (line != null) {
                line = line.trim { it <= ' ' }
                if (line.startsWith("[")) {
                    if (line.equals("[Script info]", ignoreCase = true)) {
                        lineCounter++
                        line = br.readLine()!!.trim { it <= ' ' }
                        while (!line!!.startsWith("[")) {
                            if (line.startsWith("Title:")) {
                                val titleArr = RegexUtils.getPattern(":").split(line)
                                tto.title = if (titleArr.size > 1) titleArr[1].trim { it <= ' ' } else ""
                            } else if (line.startsWith("Original Script:")) {
                                val authorArr = RegexUtils.getPattern(":").split(line)
                                tto.author = if (authorArr.size > 1) authorArr[1].trim { it <= ' ' } else ""
                            } else if (line.startsWith("Script Type:")) {
                                if (RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }.equals("v4.00+", ignoreCase = true)) isASS = true
                                else if (!RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }.equals("v4.00", ignoreCase = true))
                                    tto.warnings += "Script version is older than 4.00, it may produce parsing errors."
                            } else if (line.startsWith("Timer:"))
                                timer = RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }.replace(',', '.').toFloat()
                            lineCounter++
                            line = br.readLine()!!.trim { it <= ' ' }
                        }

                    } else if (line.equals("[v4 Styles]", ignoreCase = true)
                            || line.equals("[v4 Styles+]", ignoreCase = true)
                            || line.equals("[v4+ Styles]", ignoreCase = true)) {
                        if (line.contains("+") && isASS == false) {
                            isASS = true
                            tto.warnings += "ScriptType should be set to v4:00+ in the [Script Info] section.\n\n"
                        }
                        lineCounter++
                        line = br.readLine()
                        if (!line!!.startsWith("Format:")) {
                            tto.warnings += "Format: (format definition) expected at line " + line + " for the styles section\n\n"
                            while (!line!!.startsWith("Format:")) {
                                lineCounter++
                                line = br.readLine()
                            }
                        }
                        styleFormat = RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' })
                        lineCounter++
                        line = br.readLine()
                        while (!line!!.startsWith("Style:")) {
                            tto.warnings += "Style: (format definition) expected at line " + line + " for the styles section\n\n"
                            lineCounter++
                            line = br.readLine()
                        }
                        style = parseStyleForASS(RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }), styleFormat, lineCounter, isASS, tto.warnings)
                        tto.styling!!.put(style.iD, style)

                    } else if (line.trim { it <= ' ' }.equals("[Events]", ignoreCase = true)) {
                        lineCounter++
                        line = br.readLine()
                        tto.warnings += "Only dialogue events are considered, all other events are ignored.\n\n"
                        if (!line!!.startsWith("Format:")) {
                            tto.warnings += "Format: (format definition) expected at line " + line + " for the events section\n\n"
                            while (!line!!.startsWith("Format:")) {
                                lineCounter++
                                line = br.readLine()
                            }
                        }
                        dialogueFormat = RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' })
                        lineCounter++
                        line = br.readLine()
                        while (!line!!.startsWith("[")) {
                            if (line.startsWith("Dialogue:")) {
                                caption = parseDialogueForASS(RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line, 2)[1].trim { it <= ' ' }, 10), dialogueFormat, timer, tto)
                                var key = caption.start!!.mseconds
                                while (tto.captions!!.containsKey(key)) key++
                                tto.captions!!.put(key, caption)
                            }
                            lineCounter++
                            line = br.readLine()
                        }

                    } else if (line.trim { it <= ' ' }.equals("[Fonts]", ignoreCase = true) || line.trim { it <= ' ' }.equals("[Graphics]", ignoreCase = true)) {
                        tto.warnings += "The section " + line.trim { it <= ' ' } + " is not supported for conversion, all information there will be lost.\n\n"
                    } else {
                        tto.warnings += "Unrecognized section: " + line.trim { it <= ' ' } + " all information there is ignored."
                    }
                }
                line = br.readLine()
                lineCounter++
            }
            tto.cleanUnusedStyles()

        } catch (e: NullPointerException) {
            tto.warnings += "unexpected end of file, maybe last caption is not complete.\n\n"
        } finally {
            `is`.close()
        }

        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): Array<String>? {

        if (!tto.built)
            return null

        var index = 0
        val file = ArrayList<String>(30 + tto.styling!!.size + tto.captions!!.size)

        file.add(index++, "[Script Info]")
        var title = "Title: "
        if (tto.title == null || tto.title!!.isEmpty())
            title += tto.fileName
        else title += tto.title
        file.add(index++, title)
        var author = "Original Script: "
        if (tto.author == null || tto.author!!.isEmpty())
            author += "Unknown"
        else author += tto.author
        file.add(index++, author)
        if (tto.copyrigth != null && !tto.copyrigth!!.isEmpty())
            file.add(index++, "; " + tto.copyrigth)
        if (tto.description != null && !tto.description!!.isEmpty())
            file.add(index++, "; " + tto.description)
        file.add(index++, "; Converted by the Online Subtitle Converter developed by J. David Requejo")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Script Type: V4.00+")
        else file.add(index++, "Script Type: V4.00")
        file.add(index++, "Collisions: Normal")
        file.add(index++, "Timer: 100,0000")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "WrapStyle: 1")
        file.add(index++, "")

        if (tto.useASSInsteadOfSSA)
            file.add(index++, "[V4+ Styles]")
        else file.add(index++, "[V4 Styles]")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        else file.add(index++, "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding")
        val itrS = tto.styling!!.values.iterator()
        while (itrS.hasNext()) {
            var styleLine = "Style: "
            val current = itrS.next()
            styleLine += current.iD + ","
            styleLine += current.font + ","
            styleLine += current.fontSize + ","
            styleLine += getColorsForASS(tto.useASSInsteadOfSSA, current)
            styleLine += getOptionsForASS(tto.useASSInsteadOfSSA, current)
            styleLine += "1,2,2,"
            styleLine += getAlignForASS(tto.useASSInsteadOfSSA, current.textAlign)
            styleLine += ",0,0,0,"
            if (!tto.useASSInsteadOfSSA) styleLine += "0,"
            styleLine += "0"

            file.add(index++, styleLine)
        }
        file.add(index++, "")

        file.add(index++, "[Events]")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        else file.add(index++, "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        val itrC = tto.captions!!.values.iterator()
        while (itrC.hasNext()) {
            var line = "Dialogue: 0,"
            val current = itrC.next()
            if (tto.offset != 0) {
                current.start!!.mseconds += tto.offset
                current.end!!.mseconds += tto.offset
            }
            line += current.start!!.getTime("h:mm:ss.cs") + ","
            line += current.end!!.getTime("h:mm:ss.cs") + ","
            if (tto.offset != 0) {
                current.start!!.mseconds -= tto.offset
                current.end!!.mseconds -= tto.offset
            }
            if (current.style != null)
                line += current.style!!.iD
            else
                line += "Default"
            line += ",,0000,0000,0000,,"

            line += current.content!!.replace(Regex("<br />"), "\uFFFDN").replace(Regex("\\<.*?\\>"), "").replace('\uFFFD', '\\')
            file.add(index++, line)
        }
        file.add(index++, "")

        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }

    private fun parseStyleForASS(line: Array<String>, styleFormat: Array<String>, index: Int, isASS: Boolean, warnings: String?): Style {

        var warnings = warnings
        val newStyle = Style(Style.defaultID())
        if (line.size != styleFormat.size) {
            warnings += "incorrectly formated line at " + index + "\n\n"
        } else {
            for (i in 0 until styleFormat.size) {
                if (styleFormat[i].trim { it <= ' ' }.equals("Name", ignoreCase = true)) {
                    newStyle.iD = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Fontname", ignoreCase = true)) {
                    newStyle.font = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Fontsize", ignoreCase = true)) {
                    newStyle.fontSize = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("PrimaryColour", ignoreCase = true)) {
                    val color = line[i].trim { it <= ' ' }
                    if (isASS) {
                        if (color.startsWith("&H")) newStyle.color = Style.getRGBValue("&HAABBGGRR", color)
                        else newStyle.color = Style.getRGBValue("decimalCodedAABBGGRR", color)
                    } else {
                        if (color.startsWith("&H")) newStyle.color = Style.getRGBValue("&HBBGGRR", color)
                        else newStyle.color = Style.getRGBValue("decimalCodedBBGGRR", color)
                    }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("BackColour", ignoreCase = true)) {
                    val color = line[i].trim { it <= ' ' }
                    if (isASS) {
                        if (color.startsWith("&H")) newStyle.backgroundColor = Style.getRGBValue("&HAABBGGRR", color)
                        else newStyle.backgroundColor = Style.getRGBValue("decimalCodedAABBGGRR", color)
                    } else {
                        if (color.startsWith("&H")) newStyle.backgroundColor = Style.getRGBValue("&HBBGGRR", color)
                        else newStyle.backgroundColor = Style.getRGBValue("decimalCodedBBGGRR", color)
                    }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Bold", ignoreCase = true)) {
                    newStyle.bold = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Italic", ignoreCase = true)) {
                    newStyle.italic = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Underline", ignoreCase = true)) {
                    newStyle.underline = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Alignment", ignoreCase = true)) {
                    val placement = line[i].trim { it <= ' ' }.toInt()
                    if (isASS) {
                        when (placement) {
                            1 -> newStyle.textAlign = "bottom-left"
                            2 -> newStyle.textAlign = "bottom-center"
                            3 -> newStyle.textAlign = "bottom-right"
                            4 -> newStyle.textAlign = "mid-left"
                            5 -> newStyle.textAlign = "mid-center"
                            6 -> newStyle.textAlign = "mid-right"
                            7 -> newStyle.textAlign = "top-left"
                            8 -> newStyle.textAlign = "top-center"
                            9 -> newStyle.textAlign = "top-right"
                            else -> warnings += "undefined alignment for style at line " + index + "\n\n"
                        }
                    } else {
                        when (placement) {
                            9 -> newStyle.textAlign = "bottom-left"
                            10 -> newStyle.textAlign = "bottom-center"
                            11 -> newStyle.textAlign = "bottom-right"
                            1 -> newStyle.textAlign = "mid-left"
                            2 -> newStyle.textAlign = "mid-center"
                            3 -> newStyle.textAlign = "mid-right"
                            5 -> newStyle.textAlign = "top-left"
                            6 -> newStyle.textAlign = "top-center"
                            7 -> newStyle.textAlign = "top-right"
                            else -> warnings += "undefined alignment for style at line " + index + "\n\n"
                        }
                    }
                }

            }
        }

        return newStyle
    }

    private fun parseDialogueForASS(line: Array<String>, dialogueFormat: Array<String>, timer: Float, tto: TimedTextObject): Subtitle {

        val newCaption = Subtitle()

        val captionText = line[9]
        newCaption.content = captionText.replace(Regex("\\{.*?\\}"), "").replace("\n", "<br />").replace("\\N", "<br />")

        for (i in 0 until dialogueFormat.size) {
            if (dialogueFormat[i].trim { it <= ' ' }.equals("Style", ignoreCase = true)) {
                newCaption.lyricCurrent = "PLAYING_CENTER" == line[i].trim { it <= ' ' }
                val s = tto.styling!!.get(line[i].trim { it <= ' ' })
                if (s != null)
                    newCaption.style = s
                else
                    tto.warnings += "undefined style: " + line[i].trim { it <= ' ' } + "\n\n"
            } else if (dialogueFormat[i].trim { it <= ' ' }.equals("Start", ignoreCase = true)) {
                newCaption.start = Time("h:mm:ss.cs", line[i].trim { it <= ' ' })
            } else if (dialogueFormat[i].trim { it <= ' ' }.equals("End", ignoreCase = true)) {
                newCaption.end = Time("h:mm:ss.cs", line[i].trim { it <= ' ' })
            }
        }

        if (timer != 100f) {
            newCaption.start!!.mseconds = (newCaption.start!!.mseconds / (timer / 100)).toInt()
            newCaption.end!!.mseconds = (newCaption.end!!.mseconds / (timer / 100)).toInt()
        }
        return newCaption
    }

    private fun getColorsForASS(useASSInsteadOfSSA: Boolean, style: Style): String {
        var colors: String
        if (useASSInsteadOfSSA)
            colors = ("00" + style.color!!.substring(4, 6) + style.color!!.substring(2, 4) + style.color!!.substring(0, 2)).toInt(16).toString() + ",16777215,0," + ("80" + style.backgroundColor!!.substring(4, 6) + style.backgroundColor!!.substring(2, 4) + style.backgroundColor!!.substring(0, 2)).toLong(16) + ","
        else {
            val color = style.color!!.substring(4, 6) + style.color!!.substring(2, 4) + style.color!!.substring(0, 2)
            val bgcolor = style.backgroundColor!!.substring(4, 6) + style.backgroundColor!!.substring(2, 4) + style.backgroundColor!!.substring(0, 2)
            colors = color.toLong(16).toString() + ",16777215,0," + bgcolor.toLong(16) + ","
        }
        return colors
    }

    private fun getOptionsForASS(useASSInsteadOfSSA: Boolean, style: Style): String {
        var options: String
        if (style.bold)
            options = "-1,"
        else
            options = "0,"
        if (style.italic)
            options += "-1,"
        else
            options += "0,"
        if (useASSInsteadOfSSA) {
            if (style.underline)
                options += "-1,"
            else
                options += "0,"
            options += "0,100,100,0,0,"
        }
        return options
    }

    private fun getAlignForASS(useASSInsteadOfSSA: Boolean, align: String?): Int {
        if (useASSInsteadOfSSA) {
            var placement = 2
            if ("bottom-left" == align)
                placement = 1
            else if ("bottom-center" == align)
                placement = 2
            else if ("bottom-right" == align)
                placement = 3
            else if ("mid-left" == align)
                placement = 4
            else if ("mid-center" == align)
                placement = 5
            else if ("mid-right" == align)
                placement = 6
            else if ("top-left" == align)
                placement = 7
            else if ("top-center" == align)
                placement = 8
            else if ("top-right" == align)
                placement = 9

            return placement
        } else {

            var placement = 10
            if ("bottom-left" == align)
                placement = 9
            else if ("bottom-center" == align)
                placement = 10
            else if ("bottom-right" == align)
                placement = 11
            else if ("mid-left" == align)
                placement = 1
            else if ("mid-center" == align)
                placement = 2
            else if ("mid-right" == align)
                placement = 3
            else if ("top-left" == align)
                placement = 5
            else if ("top-center" == align)
                placement = 6
            else if ("top-right" == align)
                placement = 7

            return placement
        }
    }

}
