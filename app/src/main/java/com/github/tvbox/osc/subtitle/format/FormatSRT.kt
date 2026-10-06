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

import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.subtitle.model.Time
import com.github.tvbox.osc.subtitle.model.TimedTextObject
import com.github.tvbox.osc.util.RegexUtils

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList

class FormatSRT : TimedTextFileFormat {

    @Throws(IOException::class)
    override fun parseFile(fileName: String, `is`: InputStream): TimedTextObject {

        val tto = TimedTextObject()
        var caption = Subtitle()
        var captionNumber = 1
        var allGood: Boolean

        val `in` = InputStreamReader(`is`)
        val br = BufferedReader(`in`)

        tto.fileName = fileName

        var line: String? = br.readLine()
        var lineCounter = 0
        try {
            while (line != null) {
                line = line.trim { it <= ' ' }
                lineCounter++
                if (!line.isEmpty()) {
                    allGood = false
                    try {
                        val num = line.toInt()
                        if (num != captionNumber)
                            throw Exception() else {
                            captionNumber++
                            allGood = true
                        }
                    } catch (e: Exception) {
                        tto.warnings += captionNumber.toString() + " expected at line " + lineCounter
                        tto.warnings += "\n skipping to next line\n\n"
                    }
                    if (allGood) {
                        try {
                            lineCounter++
                            line = br.readLine()!!.trim { it <= ' ' }
                            val start = line.substring(0, 12)
                            val end = line.substring(line.length - 12, line.length)
                            var time = Time("hh:mm:ss,ms", start)
                            caption.start = time
                            time = Time("hh:mm:ss,ms", end)
                            caption.end = time
                        } catch (e: Exception) {
                            tto.warnings += "incorrect time format at line " + lineCounter
                            allGood = false
                        }
                    }
                    if (allGood) {
                        lineCounter++
                        line = br.readLine()!!.trim { it <= ' ' }
                        var text = ""
                        while (!line!!.isEmpty()) {
                            text += line + "<br />"
                            line = br.readLine()!!.trim { it <= ' ' }
                            lineCounter++
                        }
                        caption.content = text
                        var key = caption.start!!.mseconds
                        while (tto.captions!!.containsKey(key)) key++
                        if (key != caption.start!!.mseconds)
                            tto.warnings += "caption with same start time found...\n\n"
                        tto.captions!!.put(key, caption)
                    }
                    while (!line!!.isEmpty()) {
                        line = br.readLine()!!.trim { it <= ' ' }
                        lineCounter++
                    }
                    caption = Subtitle()
                }
                line = br.readLine()
            }

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
        val file = ArrayList<String>(5 * tto.captions!!.size)
        val c = tto.captions!!.values
        val itr = c.iterator()
        var captionNumber = 1

        while (itr.hasNext()) {
            val current = itr.next()
            file.add(index++, "" + captionNumber++)
            if (tto.offset != 0) {
                current.start!!.mseconds += tto.offset
                current.end!!.mseconds += tto.offset
            }
            file.add(index++, current.start!!.getTime("hh:mm:ss,ms") + " --> " + current.end!!.getTime("hh:mm:ss,ms"))
            if (tto.offset != 0) {
                current.start!!.mseconds -= tto.offset
                current.end!!.mseconds -= tto.offset
            }
            val lines = cleanTextForSRT(current)
            var i = 0
            while (i < lines.size)
                file.add(index++, "" + lines[i++])
            file.add(index++, "")
        }

        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }

    private fun cleanTextForSRT(current: Subtitle): Array<String> {
        val text = current.content
        val lines = RegexUtils.getPattern("<br />").split(text!!)
        for (i in 0 until lines.size) {
            lines[i] = lines[i].replace(Regex("\\<.*?\\>"), "")
        }
        return lines
    }

}
