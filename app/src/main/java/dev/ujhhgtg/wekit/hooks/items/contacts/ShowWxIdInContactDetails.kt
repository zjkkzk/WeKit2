package dev.ujhhgtg.wekit.hooks.items.contacts

import android.app.Activity
import dev.ujhhgtg.wekit.hooks.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeContactPrefsScreenApi.ContactInfoItem
import dev.ujhhgtg.wekit.hooks.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast

@HookItem(name = "显示微信 ID", categories = ["联系人与群组"], description = "在联系人与群组详情页面显示微信 ID")
object ShowWxIdInContactDetails : SwitchHookItem(), IContactInfoProvider {

    private const val PREF_KEY = "wxid_display"
    private const val SEPARATOR = ";"

    override fun getContactInfoItem(activity: Activity): List<ContactInfoItem> {
        val wxId = activity.currentWxId

        return listOf(ContactInfoItem(
            key = "$PREF_KEY$SEPARATOR$wxId",
            title = "微信 ID: ${wxId ?: "获取失败"}",
            position = 1
        ))
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (!key.startsWith(PREF_KEY)) return false
        val wxId = key.substringAfter(SEPARATOR)
        copyToClipboard(activity, wxId)
        showToast(activity, "已复制")
        return true
    }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
