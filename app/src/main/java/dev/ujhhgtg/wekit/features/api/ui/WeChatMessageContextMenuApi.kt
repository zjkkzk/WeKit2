package dev.ujhhgtg.wekit.features.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.chat.MergeChatMessageContextMenuItems
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.ExtensionIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@SuppressLint("StaticFieldLeak")
@Feature(name = "聊天界面消息菜单扩展", categories = ["API"], description = "为聊天界面消息长按菜单提供添加菜单项功能")
object WeChatMessageContextMenuApi : ApiFeature(), IResolveDex {

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val imageVector: ImageVector,
        val shouldShow: (MessageInfo) -> Boolean,
        val onClick: (View, ChattingContext, MessageInfo) -> Unit
    )

    // for clearer semantics; this simply compiles to Object in JVM bytecode
    @JvmInline
    value class ChattingContext(val instance: Any) {
        val activity: Activity
            get() = instance.reflekt()
                .firstMethod {
                    returnType = Activity::class
                }.invoke()!! as Activity
    }

    private const val TAG = "WeChatMessageContextMenuApi"

    // id of the single merged entry shown when MergeChatMessageContextMenuItems is enabled
    private const val MERGED_MENU_ITEM_ID = 777000

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    private val methodCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
        }
    }
    private val methodSelectMenuItem by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag")
        }
    }
//    private val classChattingMessBox by dexClass {
//        searchPackages("com.tencent.mm.ui.chatting.component")
//        matcher {
//            usingEqStrings(
//                "MicroMsg.ChattingUI.FootComponent",
//                "onNotifyChange event %s talker %s"
//            )
//        }
//    }

    private var currentView: View? = null

    override fun onEnable() {
        methodCreateMenu.hookBefore {
            val menu = args[0]

            currentView = args[1] as View
            val tag = currentView!!.tag

            val msgInfo = WeMessageApi.getMsgInfoFromTag(tag)
            val msgInfoWrapper = MessageInfo(msgInfo)

            try {
                val addMenuItem = menu.reflekt()
                    .firstMethod {
                        parameters(Int::class, CharSequence::class, Drawable::class)
                        returnType = android.view.MenuItem::class
                    }

                val applicableItems = menuItems.values.flatten()
                    .filter { it.shouldShow(msgInfoWrapper) }

                if (MergeChatMessageContextMenuItems.isEnabled) {
                    // collapse everything into a single "WeKit" entry backed by a Compose dialog
                    if (applicableItems.isNotEmpty()) {
                        addMenuItem.invoke(MERGED_MENU_ITEM_ID, "WeKit", ExtensionIcon)
                    }
                } else {
                    for (item in applicableItems) {
                        addMenuItem.invoke(item.id, "${item.text} [K]", item.drawable)
                    }
                }
            } catch (ex: Throwable) {
                WeLogger.e(
                    TAG,
                    "exception occurred threw while providing menu items",
                    ex
                )
            }
        }

        methodSelectMenuItem.hookBefore {
            val viewOnLongClickListener = thisObject.reflekt()
                .firstField {
                    type {
                        it isSubclassOf View.OnLongClickListener::class
                    }
                }
                .get() as View.OnLongClickListener
            val chattingContext = viewOnLongClickListener.reflekt()
                .firstField {
                    type = WeMessageApi.classChattingContext.clazz
                    superclass()
                }
                .get()!!

            val tag = currentView!!.tag
            val msgInfo = WeMessageApi.getMsgInfoFromTag(tag)

            val menuItem = args[0] as android.view.MenuItem
            val msgInfoWrapper = MessageInfo(msgInfo)
            val view = currentView!!
            val context = ChattingContext(chattingContext)
            try {
                if (menuItem.itemId == MERGED_MENU_ITEM_ID) {
                    val applicableItems = menuItems.values.flatten()
                        .filter { it.shouldShow(msgInfoWrapper) }
                    showMergedMenuDialog(view, context, msgInfoWrapper, applicableItems)
                    result = null
                    return@hookBefore
                }

                for (item in menuItems.values.flatten()) {
                    if (item.id == menuItem.itemId) {
                        item.onClick(view, context, msgInfoWrapper)
                        result = null
                        return@hookBefore
                    }
                }
            } catch (ex: Throwable) {
                WeLogger.e(
                    TAG,
                    "exception occurred while handling click event",
                    ex
                )
            }
        }
    }

    // shows every applicable provider item in one dialog; clicking a row dismisses the dialog
    // and dispatches to that item's onClick, mirroring a native menu selection
    private fun showMergedMenuDialog(
        view: View,
        chattingContext: ChattingContext,
        msgInfo: MessageInfo,
        items: List<MenuItem>
    ) {
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text("WeKit") },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                    ) {
                        items(items) { item ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        imageVector = item.imageVector,
                                        contentDescription = item.text
                                    )
                                },
                                headlineContent = { Text(item.text) },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    try {
                                        item.onClick(view, chattingContext, msgInfo)
                                    } catch (ex: Throwable) {
                                        WeLogger.e(
                                            TAG,
                                            "exception occurred while handling click event",
                                            ex
                                        )
                                    }
                                }
                            )
                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } }
            )
        }
    }

    override fun onDisable() {
        currentView = null
    }
}
