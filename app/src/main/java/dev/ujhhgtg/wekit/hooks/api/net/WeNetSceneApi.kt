package dev.ujhhgtg.wekit.hooks.api.net

import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "API/NetScene API", description = "提供 NetScene 发送能力")
object WeNetSceneApi : ApiHookItem(), IResolvesDex {

    fun addNetSceneToQueue(netScene: Any) {
        val queue = classMmKernel.clazz.asResolver()
            .firstMethod {
                returnType = methodAddNetSceneToQueue.method.declaringClass
            }.invoke()!!
        methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
    }

    private val classMmKernel by dexClass()

    private val methodAddNetSceneToQueue by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        classMmKernel.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
            }
        }

        methodAddNetSceneToQueue.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type=")
            }
        }
    }
}
