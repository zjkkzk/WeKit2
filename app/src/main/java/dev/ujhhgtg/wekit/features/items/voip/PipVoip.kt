package dev.ujhhgtg.wekit.features.items.voip

import android.app.Activity
import android.content.pm.ActivityInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(name = "音视频通话使用画中画", categories = ["聊天", "音视频通话"], description = "让微信的音视频通话使用原生的画中画模式而非悬浮窗 (没写完)")
object PipVoip : SwitchFeature(), IResolveDex {

    private const val TAG = "PipVoip"

//    private val stubActivityProxyClass by lazy {
//        Proxy.getProxyClass(
//            ClassLoaderProvider.classLoader!!,
//            methodGetActivityProxy.method.returnType,
//            "ig0.m0".toClass(),
//            "ig0.u0".toClass()
//        )
//    }

//    private lateinit var originalActivityProxyInstance: Any
//    private lateinit var stubActivityProxyInstance: Any

    override fun onEnable() {
//        stubActivityProxyClass // trigger lazy calculation
//
//        methodGetActivityProxy.hookAfter {
//            if (!::stubActivityProxyInstance.isInitialized) {
//                originalActivityProxyInstance = result
//                val handler = InvocationHandler { _, method, args ->
//                    WeLogger.d(TAG, "method ${method.name} invoked on StubActivityProxy with ${args.size} argument(s)\n")
//                    return@InvocationHandler runCatching {
//                        originalActivityProxyInstance.reflekt()
//                            .firstMethod {
//                                name = method.name
//                                superclass()
//                            }.invoke(*args)
//                    }.onFailure { WeLogger.e(TAG, "exception", it) }.getOrThrow()
//                }
//                stubActivityProxyInstance = stubActivityProxyClass.createInstance(handler)
//            }
//
//            result = stubActivityProxyInstance
//        }

        classVoipActivityProxy.reflekt()
            .firstMethod {
                name = "dealContentView"
            }.hookBefore {
                WeLogger.d(TAG, "dealContentView: ${args[0].javaClass}")
            }

        ActivityInfo::class.reflekt()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as ActivityInfo
                if (info.name == "${PackageNames.WECHAT}.plugin.voip.ui.VideoActivity")
                    applyFlags(info)
            }

        Activity::class.reflekt()
            .firstMethod {
                name = "onPictureInPictureModeChanged"
                parameterCount = 2
            }.hookBefore {
                if (thisObject.javaClass.simpleName != "VideoActivity") return@hookBefore

                val isInPip = args[0] as Boolean
                if (isInPip) {
                    WeLogger.i(TAG, "currently in pip")
                } else {
                    WeLogger.i(TAG, "currently not in pip")
                }
            }
    }

    private fun applyFlags(info: ActivityInfo) {
        var flags = info.flags
        flags = flags or FLAG_SUPPORTS_PICTURE_IN_PICTURE
        info.flags = flags

        info.reflekt()
            .firstField { name = "resizeMode" }
            .set(RESIZE_MODE_RESIZEABLE)
    }

    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
    private const val RESIZE_MODE_RESIZEABLE = 2

    private val classVoipActivityProxy by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ILinkVoipVideoActivityProxy-")
        }
    }
}
