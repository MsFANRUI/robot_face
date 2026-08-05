package com.popkter.robotface

import android.app.Application
import android.util.Log

/**
 * 自定义 Application —— 在进程最早期安装全局兜底异常处理器。
 *
 * OkHttp WebSocket 内部使用 TaskRunner / Dispatcher 线程池，
 * DeflaterSink 偶发 NPE 从 runWorker() 逃逸会导致 App 崩溃。
 * 必须在任何 OkHttp 线程创建之前设置 DefaultUncaughtExceptionHandler。
 */
class RobotFaceApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 捕获 OkHttp 内部逃逸的 NPE（DeflaterSink 竞态、TaskRunner 等）
            // TaskRunner 线程名包含 "OkHttp"，其 NPE 堆栈可能不含 okio/okhttp 前缀
            val isOkHttpThread = thread.name.contains("OkHttp")
            val hasOkioStack = throwable.stackTrace.any {
                it.className.startsWith("okio.") || it.className.startsWith("okhttp3.")
            }
            if (throwable is NullPointerException && (hasOkioStack || isOkHttpThread)) {
                Log.w("RobotFaceApp",
                    "✅ 已捕获 OkHttp 内部 NPE（防崩溃）: thread=${thread.name}, msg=${throwable.message}")
                // 不调用 defaultHandler → 线程安静死亡，App 不崩溃
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
