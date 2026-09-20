package com.blackhole.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase { IDLE, ANALYZING, DOWNLOADING, SAVING, COMPLETE, ERROR }
data class TransferState(val phase: Phase = Phase.IDLE, val percent: Int? = null, val detail: String = "", val message: String = "", val uri: String? = null) {
    val busy get() = phase == Phase.ANALYZING || phase == Phase.DOWNLOADING || phase == Phase.SAVING
}
object Transfer {
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()
    fun update(value: TransferState) { mutable.value = value }
}
object Links {
    fun extract(text: CharSequence?): String? = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .find(text?.toString().orEmpty())?.value?.trimEnd('.', ',', ')', ']', '}', ';')
        ?.takeIf { value ->
            val u = Uri.parse(value)
            !u.host.isNullOrBlank() && u.userInfo == null && value.length <= 4096 &&
                (u.scheme.equals("https", true) || (BuildConfig.DEBUG && u.host == "10.0.2.2"))
        }
}
