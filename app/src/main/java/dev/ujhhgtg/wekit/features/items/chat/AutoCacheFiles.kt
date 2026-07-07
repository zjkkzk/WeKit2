package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "自动缓存文件", categories = ["聊天"], description = "监听接收到的文件消息, 自动触发微信内部下载将其缓存到本地")
object AutoCacheFiles : SwitchFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoCacheFiles"

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.FILE.code) return

        // 自己发出的文件本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L) return

        WeLogger.i(TAG, "detected file message; msgSvrId=$msgSvrId, auto caching")
        CoroutineScope(Dispatchers.IO).launch {
            val path = WeMessageApi.cacheFile(msgSvrId)
            if (path != null) {
                WeLogger.i(TAG, "cached file to $path")
            } else {
                WeLogger.e(TAG, "failed to auto-cache file msgSvrId=$msgSvrId")
            }
        }
    }
}
