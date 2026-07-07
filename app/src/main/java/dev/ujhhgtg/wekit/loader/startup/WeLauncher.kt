package dev.ujhhgtg.wekit.loader.startup

import android.content.Context
import android.content.res.Resources
import com.tencent.mm.boot.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.core.FeaturesLoader
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.invokeOriginal
import dev.ujhhgtg.wekit.utils.reflection.int

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs

            // fix up Jetpack Compose
            Resources::class.java.getDeclaredMethod("getString", int).hookBeforeDirectly {
                result = runCatching { invokeOriginal() }.getOrNull() ?: "null"
            }
        }

        runCatching {
            FeaturesLoader.loadFeatures()
//            val exportJson = run {
//                val map = WePrefs.default.getAll()
//                val jsonObject = buildJsonObject {
//                    for ((key, value) in map) {
//                        when (value) {
//                            is Boolean -> put(key, value)
//                            is Int -> put(key, value)
//                            is Long -> put(key, value)
//                            is Float -> put(key, value)
//                            is Double -> put(key, value)
//                            is String -> put(key, value)
//                            is Set<*> -> put(key, buildJsonArray {
//                                @Suppress("UNCHECKED_CAST")
//                                (value as Set<String>).forEach { add(it) }
//                            })
//                            null -> put(key, JsonNull)
//                        }
//                    }
//                }
//                DefaultJson.encodeToString(jsonObject)
//            }
//            WeLogger.d(TAG, "prefs:\n${exportJson}")
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }
    }

    private const val TAG = "WeLauncher"
}
