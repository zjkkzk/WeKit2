package dev.ujhhgtg.wekit.hooks.items.moments

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.isVisible
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.ui.widget.imageview.WeImageView
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import java.lang.reflect.Field
import java.util.Locale

@HookItem(
    name = "朋友圈底部详细信息", categories = ["朋友圈"],
    description = "在朋友圈列表项底部显示详情信息"
)
object DisplayDetails : ClickableHookItem(), IResolvesDex {

    private val TAG = This.Class.simpleName

    private var textFormat by prefOption("moments_details_text_format", DEFAULT_TEXT_FORMAT)
    private var timeFormat by prefOption("moments_details_time_format", DEFAULT_TIME_FORMAT)
    private var hideGroupIcon by prefOption("moments_details_hide_group", false)

    private const val DEFAULT_TEXT_FORMAT = $$"${originalText} | ${time} "
    private const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

    private const val PH_ORIGINAL = $$"${originalText}"
    private const val PH_TIME = $$"${time}"
    private const val PH_TYPE = $$"${type}"
    private const val PH_SNS_ID = $$"${snsId}"
    private const val PH_USER_NAME = $$"${userName}"

    private const val TAG_ORIGINAL_TEXT = 0x55070001
    private const val TAG_PROCESSED_SNS_ID = 0x55070002
    private const val TAG_LIST_ATTACHED = 0x55070003

    private val timeMarkers = listOf(
        "分钟前", "小时前", "刚刚", "今天", "昨天", "前天", "天前",
        "minute", "hour", "yesterday", "today"
    )
    private val timeRegex = Regex("""\d{1,2}[:：]\d{2}""")

    override fun onEnable() {
        listOf(
            ImproveSnsTimelineUI::class.java,
            SnsUserUI::class.java
        ).forEach { clazz -> clazz.asResolver().firstMethod {
            name = "onCreate"
            parameters(Bundle::class)
        }.hookAfter {
            val activity = thisObject as Activity
            scheduleAttach(activity)
        } }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var textFormatInput by remember { mutableStateOf(textFormat) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var hideGroupIconInput by remember { mutableStateOf(hideGroupIcon) }

            AlertDialogContent(
                title = { Text("朋友圈底部信息详细") },
                text = {
                    DefaultColumn {
                        Text($$"占位符: ${originalText} ${time} ${type} ${snsId} ${userName}")
                        OutlinedTextField(
                            value = textFormatInput,
                            onValueChange = { textFormatInput = it },
                            label = { Text("文本格式") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = timeFormatInput,
                            onValueChange = { timeFormatInput = it },
                            label = { Text("时间格式") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        ListItem(
                            headlineContent = { Text("隐藏可见范围图标") },
                            trailingContent = {
                                Switch(
                                    checked = hideGroupIconInput,
                                    onCheckedChange = { hideGroupIconInput = it }
                                )
                            },
                            modifier = Modifier.clickable { hideGroupIconInput = !hideGroupIconInput }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        textFormat = textFormatInput.ifBlank { DEFAULT_TEXT_FORMAT }
                        timeFormat = timeFormatInput.ifBlank { DEFAULT_TIME_FORMAT }
                        hideGroupIcon = hideGroupIconInput
                        showToast("已保存, 重新进入朋友圈后生效")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    // ── field cache ──────────────────────────────────────────────────────────

    private lateinit var snsIdField: Field
    private lateinit var userNameField: Field
    private lateinit var createTimeField: Field
    private lateinit var typeField: Field

    private fun ensureFields(snsInfo: Any) {
        if (!::snsIdField.isInitialized) {
            snsIdField = snsInfo.asResolver()
                .firstField { name = "field_snsId"; superclass() }.self.makeAccessible()
        }
        if (!::userNameField.isInitialized) {
            userNameField = snsInfo.asResolver()
                .firstField { name = "field_userName"; superclass() }.self.makeAccessible()
        }
        if (!::createTimeField.isInitialized) {
            createTimeField = snsInfo.asResolver()
                .firstField { name = "field_createTime"; superclass() }.self.makeAccessible()
        }
        if (!::typeField.isInitialized) {
            typeField = snsInfo.asResolver()
                .firstField { name = "field_type"; superclass() }.self.makeAccessible()
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2000).forEach { delay ->
            root.postDelayed({
                runCatching { attachToLists(root) }
                    .onFailure { WeLogger.w(TAG, "attach failed, delay=${delay}ms") }
            }, delay.toLong())
        }
    }

    private fun attachToLists(root: ViewGroup) {
        val container = root.findViewWhich<WxRecyclerView> { it is WxRecyclerView } ?: error("RecyclerView not found")
        container.viewTreeObserver.addOnGlobalLayoutListener {
            for (i in 0 until container.childCount) {
                runCatching {
                    processItemView(container.getChildAt(i))
                }
            }
        }
    }

    private fun processItemView(itemView: View) {
        val snsInfo = locateSnsInfo(itemView)
        ensureFields(snsInfo)

        val snsId = (snsIdField.get(snsInfo) as? Number)?.toLong() ?: return
        val userName = (userNameField.get(snsInfo) as? String).orEmpty()
        val createTime = (createTimeField.get(snsInfo) as? Number)?.toInt() ?: 0
        val type = (typeField.get(snsInfo) as? Number)?.toInt() ?: 0

        val itemGroup = itemView as ViewGroup
        val timeTextView = itemGroup.findViewWhich<TextView> { view ->
            view is TextView && view.isVisible &&
                run {
                    val text = view.text
                    timeMarkers.any { it in text }
                } }!!

        val markedSnsId = timeTextView.getTag(TAG_PROCESSED_SNS_ID) as? Long
        if (markedSnsId != snsId) {
            val originalText = timeTextView.text?.toString().orEmpty()
            timeTextView.setTag(TAG_PROCESSED_SNS_ID, snsId)
            timeTextView.setTag(TAG_ORIGINAL_TEXT, originalText)
            timeTextView.text = buildBottomText(
                snsId = snsId,
                userName = userName,
                createTime = createTime,
                type = type,
                originalText = originalText
            )
        }

        if (hideGroupIcon) {
            val buttons = (timeTextView.parent as ViewGroup).findViewsWhich<View> {
                it is WeImageView
            }.toList()
            if (buttons.size > 1) {
                WeLogger.i(TAG, "hid visibility button")
                buttons[0].isVisible = false
            }
        }
    }

    private fun buildBottomText(
        snsId: Long,
        userName: String,
        createTime: Int,
        type: Int,
        originalText: String,
    ): String {
        val timeText = formatEpoch(createTime.toLong() * 1000, timeFormat)
        val typeText = "0x" + type.toString(16).uppercase(Locale.ROOT)

        return textFormat
            .replace(PH_ORIGINAL, originalText)
            .replace(PH_TIME, timeText)
            .replace(PH_TYPE, typeText)
            .replace(PH_SNS_ID, snsId.toString())
            .replace(PH_USER_NAME, userName)
    }

    private val classImproveSnsInfo by dexClass {
        matcher {
            usingEqStrings("ImproveInfo(name=")
        }
    }

    private val classImproveSnsInfoWrapper by dexClass {
        matcher {
            usingEqStrings("getViewType", "com.tencent.mm.plugin.sns.ui.improve.util.ImproveTypeUtil")
        }
    }

    private fun locateSnsInfo(itemView: View): Any {
        val wrapper = itemView.asResolver().firstField {
            type = "com.tencent.mm.plugin.sns.ui.improve.view.ImproveInteractionLayout"
            superclass()
        }.get() ?: itemView.asResolver().firstField {
            type = classImproveSnsInfoWrapper.clazz
            superclass()
        }.get()!!

        return wrapper.asResolver()
            .firstField { type = classImproveSnsInfo.clazz }.get()!!
    }
}
