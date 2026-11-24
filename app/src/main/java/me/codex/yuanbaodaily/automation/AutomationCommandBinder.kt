package me.codex.yuanbaodaily.automation

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.util.Log as AndroidLog

/**
 * The module's command endpoint.
 *
 * It serves two purposes:
 * 1. the bound-service stub ([AutomationCommandService]), and
 * 2. the Binder handed to the injected host through [AutomationHostLink].
 *
 * (2) is what makes the bridge work at all on Android 11+: the host has no
 * `<queries>` entry for the module, so `ContentResolver.call` answers
 * "Unknown authority" and `bindService` returns false. A Binder reference
 * carried in a broadcast needs no package lookup, so that direction is immune.
 *
 * The caller is verified inside the transaction, where
 * `Binder.getCallingUid()` still describes the caller.
 */
internal class AutomationCommandBinder private constructor() : Binder() {
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != TRANSACTION_CALL) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val callerUid = Binder.getCallingUid()
        val method = data.readString().orEmpty()
        val input = runCatching {
            data.readBundle(AutomationCommandBinder::class.java.classLoader)
        }.getOrNull() ?: Bundle()
        val context = appContext
        val allowed = context != null &&
            AutomationCommandDispatcher.isAllowedCaller(context, callerUid)
        val output = if (allowed) {
            AutomationCommandDispatcher.dispatch(context, method, input)
                ?: Bundle().apply { putBoolean(AutomationCommandDispatcher.KEY_OK, false) }
        } else {
            AndroidLog.w(TAG, "command denied callerUid=$callerUid method=$method")
            Bundle().apply { putBoolean(AutomationCommandDispatcher.KEY_OK, false) }
        }
        AndroidLog.d(
            TAG,
            "binder command method=$method " +
                "action=${input.getString(AutomationCommandDispatcher.KEY_ACTION)} " +
                "callerUid=$callerUid ok=${output.getBoolean(AutomationCommandDispatcher.KEY_OK, false)}",
        )
        reply?.writeNoException()
        reply?.writeBundle(output)
        return true
    }

    val commandBinder: Binder get() = this

    companion object {
        const val TAG = "YuanbaoDaily"
        const val DESCRIPTOR = "me.codex.yuanbaodaily.automation.AutomationCommandBinder"
        const val TRANSACTION_CALL = 1

        val instance = AutomationCommandBinder()
    }
}
