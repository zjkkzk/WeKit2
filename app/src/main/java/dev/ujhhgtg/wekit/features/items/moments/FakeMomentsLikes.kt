package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.utils.StarIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

@Feature(name = "伪集赞", categories = ["朋友圈"], description = "自定义朋友圈点赞用户列表")
object FakeMomentsLikes : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "FakeMomentsLikes"

    // 存储每个朋友圈动态的伪点赞用户配置 (snsId -> Set<WxId>)
    private val fakeLikeWxIds = mutableMapOf<Long, Set<String>>()
    private lateinit var parseFromMethod: Method
    private lateinit var snsUserProtobufClass: Class<*>
    private lateinit var snsUserProtobufClassWxIdField: Field

    override fun onEnable() {
        snsUserProtobufClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
        snsUserProtobufClassWxIdField = snsUserProtobufClass.reflekt().firstField { type = String::class }.self
        parseFromMethod = SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
        WeMomentsContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777006,
                "伪点赞",
                StarIcon,
                { _, _ -> true }
            ) { moment ->
                val contacts = WeDatabaseApi.getContacts()
                val snsInfo = moment.snsInfo!!
                val snsId = snsInfo.reflekt().getField("field_snsId", true) as Long

                val currentSelected = fakeLikeWxIds[snsId] ?: emptySet()

                showComposeDialog(moment.activity) {
                    ContactsSelector(
                        title = "选择伪点赞用户",
                        contacts = contacts,
                        initialSelectedWxIds = currentSelected,
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxids ->
                            if (selectedWxids.isEmpty()) {
                                fakeLikeWxIds.remove(snsId)
                                showToast("已清除伪点赞配置")
                            } else {
                                fakeLikeWxIds[snsId] = selectedWxids
                                showToast("已设置 ${selectedWxids.size} 个伪点赞")
                            }
                            onDismiss()
                        }
                    )
                }
            }
        )
    }

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        try {
            injectFakeLikes(table, values)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to handle database update", e)
        }
    }

    private fun injectFakeLikes(tableName: String, values: ContentValues) = runCatching {
        if (tableName != "SnsInfo") return@runCatching
        val snsId = values.get("snsId") as? Long ?: return@runCatching
        val fakeWxIds = fakeLikeWxIds[snsId] ?: emptySet()
        if (fakeWxIds.isEmpty()) return@runCatching

        val snsObj = SnsObject()
        parseFromMethod.invoke(snsObj, values.get("attrBuf") as? ByteArray ?: return@runCatching)

        val fakeList = LinkedList<Any>().apply {
            fakeWxIds.forEach { wxid ->
                snsUserProtobufClass.createInstance().apply {
                    snsUserProtobufClassWxIdField.set(this, wxid)
                    add(this)
                }
            }
        }

        snsObj.LikeUserList = fakeList
        snsObj.LikeUserListCount = fakeList.size
        snsObj.LikeCount = fakeList.size
        snsObj.LikeFlag = 1

        values.put("attrBuf", snsObj.toByteArray())
        WeLogger.i(TAG, "成功为朋友圈 $snsId 注入 ${fakeList.size} 个伪点赞")
    }.onFailure { WeLogger.e(TAG, "注入伪点赞失败", it) }
}
