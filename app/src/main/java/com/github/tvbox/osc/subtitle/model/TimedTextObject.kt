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

import com.github.tvbox.osc.subtitle.format.FormatASS
import com.github.tvbox.osc.subtitle.format.FormatSCC
import com.github.tvbox.osc.subtitle.format.FormatSRT
import com.github.tvbox.osc.subtitle.format.FormatSTL
import com.github.tvbox.osc.subtitle.format.FormatTTML

import java.util.Hashtable
import java.util.TreeMap

class TimedTextObject {

    /*
     * Attributes
     *
     */
    //meta info
    @JvmField
    var title: String? = ""

    @JvmField
    var description: String? = ""

    @JvmField
    var copyrigth: String? = ""

    @JvmField
    var author: String? = ""

    @JvmField
    var fileName: String? = ""

    @JvmField
    var language: String? = ""

    //list of styles (id, reference)
    @JvmField
    var styling: Hashtable<String, Style>? = null

    //list of layouts (id, reference)
    @JvmField
    var layout: Hashtable<String, Region>? = null

    //list of captions (begin time, reference)
    //represented by a tree map to maintain order
    @JvmField
    var captions: TreeMap<Int, Subtitle>? = null

    //to store non fatal errors produced during parsing
    @JvmField
    var warnings: String? = null

    //**** OPTIONS *****
    //to know whether file should be saved as .ASS or .SSA
    @JvmField
    var useASSInsteadOfSSA: Boolean = true

    //to delay or advance the subtitles, parsed into +/- milliseconds
    @JvmField
    var offset: Int = 0

    //to know if a parsing method has been applied
    @JvmField
    var built: Boolean = false

    /**
     * Protected constructor so it can't be created from outside
     */
    constructor() {
        styling = Hashtable<String, Style>()
        layout = Hashtable<String, Region>()
        captions = TreeMap<Int, Subtitle>()

        warnings = "List of non fatal errors produced during parsing:\n\n"
    }

    /*
     * Writing Methods
     *
     */
    /**
     * Method to generate the .SRT file
     *
     * @return an array of strings where each String represents a line
     */
    fun toSRT(): Array<String>? {
        return FormatSRT().toFile(this)
    }

    /**
     * Method to generate the .ASS file
     *
     * @return an array of strings where each String represents a line
     */
    fun toASS(): Array<String>? {
        return FormatASS().toFile(this)
    }

    /**
     * Method to generate the .STL file
     */
    fun toSTL(): ByteArray? {
        return FormatSTL().toFile(this)
    }

    /**
     * Method to generate the .SCC file
     * @return
     */
    fun toSCC(): Array<String>? {
        return FormatSCC().toFile(this)
    }

    /**
     * Method to generate the .XML file
     * @return
     */
    fun toTTML(): Array<String>? {
        return FormatTTML().toFile(this)
    }

    /*
     * PROTECTED METHODS
     *
     */

    /**
     * This method simply checks the style list and eliminate any style not referenced by any caption
     * This might come useful when default styles get created and cover too much.
     * It require a unique iteration through all captions.
     *
     */
    fun cleanUnusedStyles() {
        //here all used styles will be stored
        val usedStyles = Hashtable<String, Style>()
        //we iterate over the captions
        val itrC = captions!!.values.iterator()
        while (itrC.hasNext()) {
            //new caption
            val current = itrC.next()
            //if it has a style
            val style = current.style
            if (style != null) {
                val iD = style.iD!!
                //if we haven't saved it yet
                if (!usedStyles.containsKey(iD)) usedStyles.put(iD, style)
            }
        }
        //we saved the used styles
        this.styling = usedStyles
    }
}
