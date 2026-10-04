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

        //for the clock timer
        var timer = 100f

        //if the file is .SSA or .ASS
        var isASS = false

        //variables to store the formats
        var styleFormat: Array<String>
        var dialogueFormat: Array<String>

        //first lets load the file
        val `in` = InputStreamReader(`is`)
        val br = BufferedReader(`in`)

        var line: String?
        var lineCounter = 0
        try {
            //we scour the file
            line = br.readLine()
            lineCounter++
            while (line != null) {
                line = line.trim { it <= ' ' }
                //we skip any line until we find a section [section name]
                if (line.startsWith("[")) {
                    //now we must identify the section
                    if (line.equals("[Script info]", ignoreCase = true)) {
                        //its the script info section section
                        lineCounter++
                        line = br.readLine()!!.trim { it <= ' ' }
                        //Each line is scanned for useful info until a new section is detected
                        while (!line!!.startsWith("[")) {
                            if (line.startsWith("Title:")) { //标题信息非必要
                                val titleArr = RegexUtils.getPattern(":").split(line)
                                //We have found the title
                                tto.title = if (titleArr.size > 1) titleArr[1].trim { it <= ' ' } else ""
                            } else if (line.startsWith("Original Script:")) { //作者信息非必要
                                val authorArr = RegexUtils.getPattern(":").split(line)
                                //We have found the author
                                tto.author = if (authorArr.size > 1) authorArr[1].trim { it <= ' ' } else ""
                            } else if (line.startsWith("Script Type:")) {
                                //we have found the version
                                if (RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }.equals("v4.00+", ignoreCase = true)) isASS = true
                                    //we check the type to set isASS or to warn if it comes from an older version than the studied specs
                                else if (!RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }.equals("v4.00", ignoreCase = true))
                                    tto.warnings += "Script version is older than 4.00, it may produce parsing errors."
                            } else if (line.startsWith("Timer:"))
                                //We have found the timer
                                timer = RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }.replace(',', '.').toFloat()
                            //we go to the next line
                            lineCounter++
                            line = br.readLine()!!.trim { it <= ' ' }
                        }

                    } else if (line.equals("[v4 Styles]", ignoreCase = true)
                            || line.equals("[v4 Styles+]", ignoreCase = true)
                            || line.equals("[v4+ Styles]", ignoreCase = true)) {
                        //its the Styles description section
                        if (line.contains("+") && isASS == false) {
                            //its ASS and it had not been noted
                            isASS = true
                            tto.warnings += "ScriptType should be set to v4:00+ in the [Script Info] section.\n\n"
                        }
                        lineCounter++
                        line = br.readLine()
                        //the first line should define the format
                        if (!line!!.startsWith("Format:")) {
                            //if not, we scan for the format.
                            tto.warnings += "Format: (format definition) expected at line " + line + " for the styles section\n\n"
                            while (!line!!.startsWith("Format:")) {
                                lineCounter++
                                line = br.readLine()
                            }
                        }
                        // we recover the format's fields
                        styleFormat = RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' })
                        lineCounter++
                        line = br.readLine()
                        // we parse each style until we reach a new section
                        while (!line!!.startsWith("Style:")) {
                            tto.warnings += "Style: (format definition) expected at line " + line + " for the styles section\n\n"
                            //next line
                            lineCounter++
                            line = br.readLine()
                        }
                        //we parse the style
                        style = parseStyleForASS(RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }), styleFormat, lineCounter, isASS, tto.warnings)
                        //and save the style
                        tto.styling!!.put(style.iD, style)

                    } else if (line.trim { it <= ' ' }.equals("[Events]", ignoreCase = true)) {
                        //its the events specification section
                        lineCounter++
                        line = br.readLine()
                        tto.warnings += "Only dialogue events are considered, all other events are ignored.\n\n"
                        //the first line should define the format of the dialogues
                        if (!line!!.startsWith("Format:")) {
                            //if not, we scan for the format.
                            tto.warnings += "Format: (format definition) expected at line " + line + " for the events section\n\n"
                            while (!line!!.startsWith("Format:")) {
                                lineCounter++
                                line = br.readLine()
                            }
                        }
                        // we recover the format's fields
                        dialogueFormat = RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' })
                        //next line
                        lineCounter++
                        line = br.readLine()
                        // we parse each style until we reach a new section
                        while (!line!!.startsWith("[")) {
                            //we check it is a dialogue
                            //WARNING: all other events are ignored.
                            if (line.startsWith("Dialogue:")) {
                                //we parse the dialogue
                                caption = parseDialogueForASS(RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line, 2)[1].trim { it <= ' ' }, 10), dialogueFormat, timer, tto)
                                //and save the caption
                                var key = caption.start!!.mseconds
                                //in case the key is already there, we increase it by a millisecond, since no duplicates are allowed
                                while (tto.captions!!.containsKey(key)) key++
                                tto.captions!!.put(key, caption)
                            }
                            //next line
                            lineCounter++
                            line = br.readLine()
                        }

                    } else if (line.trim { it <= ' ' }.equals("[Fonts]", ignoreCase = true) || line.trim { it <= ' ' }.equals("[Graphics]", ignoreCase = true)) {
                        //its the custom fonts or embedded graphics section
                        //these are not supported
                        tto.warnings += "The section " + line.trim { it <= ' ' } + " is not supported for conversion, all information there will be lost.\n\n"
                    } else {
                        tto.warnings += "Unrecognized section: " + line.trim { it <= ' ' } + " all information there is ignored."
                    }
                }
                line = br.readLine()
                lineCounter++
            }
            // parsed styles that are not used should be eliminated
            tto.cleanUnusedStyles()

        } catch (e: NullPointerException) {
            tto.warnings += "unexpected end of file, maybe last caption is not complete.\n\n"
        } finally {
            //we close the reader
            `is`.close()
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
        //the minimum size of the file is the number of captions and styles + lines for sections and formats and the script info, so we'll take some extra space.
        val file = ArrayList<String>(30 + tto.styling!!.size + tto.captions!!.size)

        //header is placed
        file.add(index++, "[Script Info]")
        //title next
        var title = "Title: "
        if (tto.title == null || tto.title!!.isEmpty())
            title += tto.fileName
        else title += tto.title
        file.add(index++, title)
        //author next
        var author = "Original Script: "
        if (tto.author == null || tto.author!!.isEmpty())
            author += "Unknown"
        else author += tto.author
        file.add(index++, author)
        //additional info
        if (tto.copyrigth != null && !tto.copyrigth!!.isEmpty())
            file.add(index++, "; " + tto.copyrigth)
        if (tto.description != null && !tto.description!!.isEmpty())
            file.add(index++, "; " + tto.description)
        file.add(index++, "; Converted by the Online Subtitle Converter developed by J. David Requejo")
        //mandatory info
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Script Type: V4.00+")
        else file.add(index++, "Script Type: V4.00")
        file.add(index++, "Collisions: Normal")
        file.add(index++, "Timer: 100,0000")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "WrapStyle: 1")
        //an empty line is added
        file.add(index++, "")

        //Styles section
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "[V4+ Styles]")
        else file.add(index++, "[V4 Styles]")
        //define the format
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        else file.add(index++, "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding")
        //Next we iterate over the styles
        val itrS = tto.styling!!.values.iterator()
        while (itrS.hasNext()) {
            var styleLine = "Style: "
            //new style
            val current = itrS.next()
            //name
            styleLine += current.iD + ","
            styleLine += current.font + ","
            styleLine += current.fontSize + ","
            styleLine += getColorsForASS(tto.useASSInsteadOfSSA, current)
            styleLine += getOptionsForASS(tto.useASSInsteadOfSSA, current)
            //BorderStyle, Outline, Shadow
            styleLine += "1,2,2,"
            styleLine += getAlignForASS(tto.useASSInsteadOfSSA, current.textAlign)
            //MarginL, MarginR, MarginV
            styleLine += ",0,0,0,"
            //AlphaLevel
            if (!tto.useASSInsteadOfSSA) styleLine += "0,"
            //Encoding
            styleLine += "0"

            //and we add the style definition line
            file.add(index++, styleLine)
        }
        //an empty line is added
        file.add(index++, "")

        //Events section
        file.add(index++, "[Events]")
        //define the format
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        else file.add(index++, "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        //Next we iterate over the captions
        val itrC = tto.captions!!.values.iterator()
        while (itrC.hasNext()) {
            //for each caption
            var line = "Dialogue: 0,"
            //new caption
            val current = itrC.next()
            //offset is applied
            if (tto.offset != 0) {
                current.start!!.mseconds += tto.offset
                current.end!!.mseconds += tto.offset
            }
            //start time
            line += current.start!!.getTime("h:mm:ss.cs") + ","
            //end time
            line += current.end!!.getTime("h:mm:ss.cs") + ","
            //offset is undone
            if (tto.offset != 0) {
                current.start!!.mseconds -= tto.offset
                current.end!!.mseconds -= tto.offset
            }
            //style
            if (current.style != null)
                line += current.style!!.iD
            else
                line += "Default"
            //default margins are used, no name or effect is recognized
            line += ",,0000,0000,0000,,"

            //we add the caption text with \N as line breaks  and clean of XML
            line += current.content!!.replace(Regex("<br />"), "\uFFFDN").replace(Regex("\\<.*?\\>"), "").replace('\uFFFD', '\\')
            //and we add the caption line
            file.add(index++, line)
        }
        //an empty line is added
        file.add(index++, "")

        //we return the expected file as an array of String
        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }

    /* PRIVATEMETHODS */

    /**
     * This methods transforms a format line from ASS according to a format definition into an Style object.
     *
     * @param line the format line without its declaration
     * @param styleFormat the list of attributes in this format line
     * @return a new Style object.
     */
    private fun parseStyleForASS(line: Array<String>, styleFormat: Array<String>, index: Int, isASS: Boolean, warnings: String?): Style {

        var warnings = warnings
        val newStyle = Style(Style.defaultID())
        if (line.size != styleFormat.size) {
            //both should have the same size
            warnings += "incorrectly formated line at " + index + "\n\n"
        } else {
            for (i in 0 until styleFormat.size) {
                //we go through every format parameter and save the interesting values
                if (styleFormat[i].trim { it <= ' ' }.equals("Name", ignoreCase = true)) {
                    //we save the name
                    newStyle.iD = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Fontname", ignoreCase = true)) {
                    //we save the font
                    newStyle.font = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Fontsize", ignoreCase = true)) {
                    //we save the size
                    newStyle.fontSize = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("PrimaryColour", ignoreCase = true)) {
                    //we save the color
                    val color = line[i].trim { it <= ' ' }
                    if (isASS) {
                        if (color.startsWith("&H")) newStyle.color = Style.getRGBValue("&HAABBGGRR", color)
                        else newStyle.color = Style.getRGBValue("decimalCodedAABBGGRR", color)
                    } else {
                        if (color.startsWith("&H")) newStyle.color = Style.getRGBValue("&HBBGGRR", color)
                        else newStyle.color = Style.getRGBValue("decimalCodedBBGGRR", color)
                    }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("BackColour", ignoreCase = true)) {
                    //we save the background color
                    val color = line[i].trim { it <= ' ' }
                    if (isASS) {
                        if (color.startsWith("&H")) newStyle.backgroundColor = Style.getRGBValue("&HAABBGGRR", color)
                        else newStyle.backgroundColor = Style.getRGBValue("decimalCodedAABBGGRR", color)
                    } else {
                        if (color.startsWith("&H")) newStyle.backgroundColor = Style.getRGBValue("&HBBGGRR", color)
                        else newStyle.backgroundColor = Style.getRGBValue("decimalCodedBBGGRR", color)
                    }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Bold", ignoreCase = true)) {
                    //we save if bold
                    newStyle.bold = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Italic", ignoreCase = true)) {
                    //we save if italic
                    newStyle.italic = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Underline", ignoreCase = true)) {
                    //we save if underlined
                    newStyle.underline = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Alignment", ignoreCase = true)) {
                    //we save the alignment
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

    /**
     * This methods transforms a dialogue line from ASS according to a format definition into an Caption object.
     *
     * @param line the dialogue line without its declaration
     * @param dialogueFormat the list of attributes in this dialogue line
     * @param timer % to speed or slow the clock, above 100% span of the subtitles is reduced.
     * @return a new Caption object
     */
    private fun parseDialogueForASS(line: Array<String>, dialogueFormat: Array<String>, timer: Float, tto: TimedTextObject): Subtitle {

        val newCaption = Subtitle()

        //all information from fields 10 onwards are the caption text therefore needn't be split
        val captionText = line[9]
        //text is cleaned before being inserted into the caption
        newCaption.content = captionText.replace(Regex("\\{.*?\\}"), "").replace("\n", "<br />").replace("\\N", "<br />")

        for (i in 0 until dialogueFormat.size) {
            //we go through every format parameter and save the interesting values
            if (dialogueFormat[i].trim { it <= ' ' }.equals("Style", ignoreCase = true)) {
                newCaption.lyricCurrent = "PLAYING_CENTER" == line[i].trim { it <= ' ' }
                //we save the style
                val s = tto.styling!!.get(line[i].trim { it <= ' ' })
                if (s != null)
                    newCaption.style = s
                else
                    tto.warnings += "undefined style: " + line[i].trim { it <= ' ' } + "\n\n"
            } else if (dialogueFormat[i].trim { it <= ' ' }.equals("Start", ignoreCase = true)) {
                //we save the starting time
                newCaption.start = Time("h:mm:ss.cs", line[i].trim { it <= ' ' })
            } else if (dialogueFormat[i].trim { it <= ' ' }.equals("End", ignoreCase = true)) {
                //we save the starting time
                newCaption.end = Time("h:mm:ss.cs", line[i].trim { it <= ' ' })
            }
        }

        //timer is applied
        if (timer != 100f) {
            newCaption.start!!.mseconds = (newCaption.start!!.mseconds / (timer / 100)).toInt()
            newCaption.end!!.mseconds = (newCaption.end!!.mseconds / (timer / 100)).toInt()
        }
        return newCaption
    }

    /**
     * returns a string with the correctly formated colors
     * @param useASSInsteadOfSSA true if formated for ASS
     * @return the colors in the decimal format
     */
    private fun getColorsForASS(useASSInsteadOfSSA: Boolean, style: Style): String {
        var colors: String
        if (useASSInsteadOfSSA)
            //primary color(BBGGRR) with Alpha level (00) in front + 00FFFFFF + 00000000 + background color(BBGGRR) with Alpha level (80) in front
            colors = ("00" + style.color!!.substring(4, 6) + style.color!!.substring(2, 4) + style.color!!.substring(0, 2)).toInt(16).toString() + ",16777215,0," + ("80" + style.backgroundColor!!.substring(4, 6) + style.backgroundColor!!.substring(2, 4) + style.backgroundColor!!.substring(0, 2)).toLong(16) + ","
        else {
            //primary color(BBGGRR) + FFFFFF + 000000 + background color(BBGGRR)
            val color = style.color!!.substring(4, 6) + style.color!!.substring(2, 4) + style.color!!.substring(0, 2)
            val bgcolor = style.backgroundColor!!.substring(4, 6) + style.backgroundColor!!.substring(2, 4) + style.backgroundColor!!.substring(0, 2)
            colors = color.toLong(16).toString() + ",16777215,0," + bgcolor.toLong(16) + ","
        }
        return colors
    }

    /**
     * returns a string with the correctly formated options
     * @param useASSInsteadOfSSA
     * @return
     */
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

    /**
     * converts the string explaining the alignment into the ASS equivalent integer offering bottom-center as default value
     * @param useASSInsteadOfSSA
     * @param align
     * @return
     */
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
