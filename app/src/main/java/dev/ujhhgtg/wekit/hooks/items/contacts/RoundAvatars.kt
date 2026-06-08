package dev.ujhhgtg.wekit.hooks.items.contacts

import android.content.Context
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge

@HookItem(
    name = "圆角头像", categories = ["联系人与群组"],
    description = "自定义微信全局头像渲染的圆角弧度"
)
object RoundAvatarHook : ClickableHookItem(), IResolvesDex {

    private const val KEY_ROUND_AVATAR = "round_avatar_radius_factor"
    private const val DEFAULT_RADIUS_FACTOR = 0.5f

    private val methodLoadAvatar by dexMethod()
    private val ctorAvatarCreate by dexConstructor()
    private val methodAvatarModify by dexMethod()

    private val radiusFactor: Float
        get() = WePrefs.getFloatOrDef(KEY_ROUND_AVATAR, DEFAULT_RADIUS_FACTOR).coerceIn(0.1f, 0.5f)

    override fun onEnable() {
        methodLoadAvatar.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        ctorAvatarCreate.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        if (!methodAvatarModify.isPlaceholder) {
            methodAvatarModify.hookBefore {
                setFloatArg(3, radiusFactor)
            }
        }

        notifyCustomContactAvatarChanged()
    }

    override fun onDisable() {
        notifyCustomContactAvatarChanged()
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodLoadAvatar.find(dexKit) {
            matcher {
                paramTypes(
                    "android.widget.ImageView",
                    "java.lang.String",
                    "float",
                    "boolean"
                )
                usingEqStrings("MicroMsg.AvatarDrawable")
            }
        }

        ctorAvatarCreate.find(dexKit) {
            matcher {
                usingEqStrings("workerScope", "username")
            }
        }

        val modifyMethods = dexKit.findMethod {
            matcher {
                usingEqStrings("workerScope", "username")
            }
        }.filter { it.methodName != "<init>" }

        val modifyMethod = modifyMethods.singleOrNull()
        if (modifyMethod == null) {
            methodAvatarModify.setPlaceholderDescriptor()
        } else {
            methodAvatarModify.setDescriptor(modifyMethod)
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var value by remember { mutableFloatStateOf(radiusFactor) }

            AlertDialogContent(
                title = { Text("圆角头像") },
                text = {
                    DefaultColumn {
                        Text("圆角弧度: %.2f".format(value))
                        Slider(
                            value = value,
                            onValueChange = { value = it.coerceIn(0.1f, 0.5f) },
                            valueRange = 0.1f..0.5f,
                            steps = 39
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putFloat(KEY_ROUND_AVATAR, value.coerceIn(0.1f, 0.5f))
                        notifyCustomContactAvatarChanged()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private fun XC_MethodHook.MethodHookParam.setFloatArg(index: Int, value: Float) {
        if (index in args.indices) args[index] = value
    }

    private fun notifyCustomContactAvatarChanged() {
        runCatching {
            if (CustomContactAvatar.hasEnabled) {
                CustomContactAvatar.onRoundAvatarConfigChanged()
            }
        }
    }
}
