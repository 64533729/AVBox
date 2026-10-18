package com.github.catvod.crawler.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class Async private constructor(private val converter: ((Any?) -> Any?)? = null) {

    private val future: Result = Result()

    private val success: JSCallFunction = object : JSCallFunction {
        override fun call(vararg args: Any?): Any? {
            val value = if (args != null && args.size > 0) args[0] else null
            try {
                future.complete(convert(value))
            } catch (th: Throwable) {
                future.completeExceptionally(th)
            }
            return null
        }
    }

    private val error: JSCallFunction = object : JSCallFunction {
        override fun call(vararg args: Any?): Any? {
            val msg = if (args != null && args.size > 0 && args[0] != null) args[0].toString() else ""
            future.completeExceptionally(Exception(msg))
            return null
        }
    }

    private fun call(obj: JSObject, name: String, args: Array<out Any?>): Result {
        val function = obj.getJSFunction(name)
        if (function == null) return empty()
        try {
            val result = function.call(*args)
            if (result is JSObject) then(result) else future.complete(convert(result))
        } catch (t: Throwable) {
            future.completeExceptionally(t)
        } finally {
            function.release()
        }
        return future
    }

    private fun empty(): Result {
        future.complete(null)
        return future
    }

    private fun convert(value: Any?): Any? {
        val transform = converter ?: return value
        return transform(value)
    }

    private fun then(promise: JSObject) {
        val then = promise.getJSFunction("then")
        if (then == null) {
            future.complete(convert(promise))
        } else {
            consume(then, success)
            consume(promise.getJSFunction("catch"), error)
        }
    }

    private fun consume(function: JSFunction?, callback: JSCallFunction) {
        if (function == null) return
        try {
            function.call(callback)
        } finally {
            function.release()
        }
    }

    class Result {

        private val latch: CountDownLatch = CountDownLatch(1)
        private var value: Any? = null
        private var error: Throwable? = null

        fun complete(value: Any?) {
            this.value = value
            latch.countDown()
        }

        fun completeExceptionally(error: Throwable) {
            this.error = error
            latch.countDown()
        }

        @Throws(Exception::class)
        fun get(): Any? {
            return get(0)
        }

        @Throws(Exception::class)
        fun get(timeoutMs: Long): Any? {
            if (timeoutMs > 0) {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw TimeoutException("async await timeout " + timeoutMs + "ms")
                }
            } else {
                latch.await()
            }
            if (error != null) {
                if (error is Exception) throw (error as Exception)
                throw Exception(error)
            }
            return value
        }
    }

    companion object {

        @JvmStatic
        fun run(obj: JSObject, name: String, args: Array<out Any?>): Result {
            return Async().call(obj, name, args)
        }

        @JvmStatic
        fun run(obj: JSObject, name: String, args: Array<out Any?>, converter: ((Any?) -> Any?)?): Result {
            return Async(converter).call(obj, name, args)
        }
    }
}
