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

package com.github.tvbox.osc.subtitle.model

import com.github.tvbox.osc.util.RegexUtils

class Time {

    /**
     * Constructor to create a time object.
     *
     * @param format supported formats: "hh:mm:ss,ms", "h:mm:ss.cs" and "h:m:s:f/fps"
     * @param value  string in the correct format
     */
    constructor(format: String, value: String) {
        if (format.equals("hh:mm:ss,ms", ignoreCase = true)) {
            // this type of format:  01:02:22,501 (used in .SRT)
            val h: Int
            val m: Int
            val s: Int
            val ms: Int
            h = value.substring(0, 2).toInt()
            m = value.substring(3, 5).toInt()
            s = value.substring(6, 8).toInt()
            ms = value.substring(9, 12).toInt()

            mseconds = ms + s * 1000 + m * 60000 + h * 3600000
        } else if (format.equals("h:mm:ss.cs", ignoreCase = true)) {
            // this type of format:  1:02:22.51 (used in .ASS/.SSA)
            val h: Int
            val m: Int
            val s: Int
            val cs: Int
            h = value.substring(0, 1).toInt()
            m = value.substring(2, 4).toInt()
            s = value.substring(5, 7).toInt()
            cs = value.substring(8, 10).toInt()

            mseconds = cs * 10 + s * 1000 + m * 60000 + h * 3600000
        } else if (format.equals("h:m:s:f/fps", ignoreCase = true)) {
            val h: Int
            val m: Int
            val s: Int
            val f: Int
            val fps: Float
            var args = RegexUtils.getPattern("/").split(value)
            fps = args[1].toFloat()
            args = RegexUtils.getPattern(":").split(args[0])
            h = args[0].toInt()
            m = args[1].toInt()
            s = args[2].toInt()
            f = args[3].toInt()

            mseconds = (f * 1000 / fps).toInt() + s * 1000 + m * 60000 + h * 3600000
        }
    }

    // in an integer we can store 24 days worth of milliseconds, no need for a long
    @JvmField
    var mseconds: Int = 0

    /**
     * Method to return a formatted value of the time stored
     *
     * @param  format supported formats: "hh:mm:ss,ms", "h:mm:ss.cs" and "hhmmssff/fps"
     * @return formatted time in a string
     */
    fun getTime(format: String): String {
        //we use string builder for efficiency
        val time = StringBuilder()
        var aux: String
        if (format.equals("hh:mm:ss,ms", ignoreCase = true)) {
            // this type of format:  01:02:22,501 (used in .SRT)
            val h: Int
            val m: Int
            val s: Int
            val ms: Int
            h = mseconds / 3600000
            aux = h.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            m = (mseconds / 60000) % 60
            aux = m.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            s = (mseconds / 1000) % 60
            aux = s.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(',')
            ms = mseconds % 1000
            aux = ms.toString()
            if (aux.length == 1) time.append("00")
            else if (aux.length == 2) time.append('0')
            time.append(aux)
        } else if (format.equals("h:mm:ss.cs", ignoreCase = true)) {
            // this type of format:  1:02:22.51 (used in .ASS/.SSA)
            val h: Int
            val m: Int
            val s: Int
            val cs: Int
            h = mseconds / 3600000
            aux = h.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            m = (mseconds / 60000) % 60
            aux = m.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            s = (mseconds / 1000) % 60
            aux = s.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append('.')
            cs = (mseconds / 10) % 100
            aux = cs.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
        } else if (format.startsWith("hhmmssff/")) {
            //this format is used in EBU's STL
            val h: Int
            val m: Int
            val s: Int
            val f: Int
            val fps: Float
            val args = RegexUtils.getPattern("/").split(format)
            fps = args[1].toFloat()
            //now we concatenate time
            h = mseconds / 3600000
            aux = h.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            m = (mseconds / 60000) % 60
            aux = m.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            s = (mseconds / 1000) % 60
            aux = s.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            f = (mseconds % 1000) * fps.toInt() / 1000
            aux = f.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
        } else if (format.startsWith("h:m:s:f/")) {
            //this format is used in EBU's STL
            val h: Int
            val m: Int
            val s: Int
            val f: Int
            val fps: Float
            val args = RegexUtils.getPattern("/").split(format)
            fps = args[1].toFloat()
            //now we concatenate time
            h = mseconds / 3600000
            aux = h.toString()
            //if (aux.length()==1) time.append('0');
            time.append(aux)
            time.append(':')
            m = (mseconds / 60000) % 60
            aux = m.toString()
            //if (aux.length()==1) time.append('0');
            time.append(aux)
            time.append(':')
            s = (mseconds / 1000) % 60
            aux = s.toString()
            //if (aux.length()==1) time.append('0');
            time.append(aux)
            time.append(':')
            f = (mseconds % 1000) * fps.toInt() / 1000
            aux = f.toString()
            //if (aux.length()==1) time.append('0');
            time.append(aux)
        } else if (format.startsWith("hh:mm:ss:ff/")) {
            //this format is used in SCC
            val h: Int
            val m: Int
            val s: Int
            val f: Int
            val fps: Float
            val args = RegexUtils.getPattern("/").split(format)
            fps = args[1].toFloat()
            //now we concatenate time
            h = mseconds / 3600000
            aux = h.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            m = (mseconds / 60000) % 60
            aux = m.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            s = (mseconds / 1000) % 60
            aux = s.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
            time.append(':')
            f = (mseconds % 1000) * fps.toInt() / 1000
            aux = f.toString()
            if (aux.length == 1) time.append('0')
            time.append(aux)
        }

        return time.toString()
    }
}
