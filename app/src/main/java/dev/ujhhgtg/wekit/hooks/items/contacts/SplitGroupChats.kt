package dev.ujhhgtg.wekit.hooks.items.contacts

import android.content.Context
import android.content.Intent
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(name = "分裂群组", categories = ["联系人与群组", "娱乐"], description = "让群聊一分为二; 在假群聊中发送的红包即为假红包")
object SplitGroupChats : ClickableHookItem() {

    private val TAG = This.Class.simpleName

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            SingleContactSelector(
                "分裂群组",
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) {
                onDismiss()
                jumpToSplitChatroom(context, it)
            }
        }
    }

    private fun jumpToSplitChatroom(context: Context, wxId: String) {
        runCatching {
            val rawId = wxId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"
            WeLogger.i(TAG, "launching ChattingUI for chatroom: $wxId")

            val intent = Intent(context, ChattingUI::class.java).apply {
                putExtra("Chat_User", targetSplitId)
                putExtra("Chat_Mode", 1)
            }

            context.startActivity(intent)
        }.onFailure { WeLogger.e(TAG, "exception occured", it) }
    }

    override val noSwitchWidget = true
}
