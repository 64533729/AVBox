package com.github.catvod.crawler.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject

import java.util.concurrent.Callable

class FunCall private constructor(private val jsObject: JSObject, private val name: String, private val args: Array<out Any?>) : Callable<Any?> {

    private var result: Any? = null

    @Throws(Exception::class)
    override fun call(): Any? {
        result = jsObject.getJSFunction(name).call(*args)
        if (result !is JSObject) return result
        val promise = result as JSObject
        val then = promise.getJSFunction("then")
        if (then != null) then.call(jsCallFunction)
        return result
    }

    private val jsCallFunction: JSCallFunction = object : JSCallFunction {
        override fun call(vararg args: Any?): Any? {
            result = args[0]
            return result
        }
    }

    companion object {

        @JvmStatic
        fun call(jsObject: JSObject, name: String, vararg args: Any?): FunCall {
            return FunCall(jsObject, name, args)
        }
    }
}
