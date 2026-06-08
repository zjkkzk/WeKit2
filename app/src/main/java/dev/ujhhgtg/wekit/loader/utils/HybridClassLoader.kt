package dev.ujhhgtg.wekit.loader.utils

import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

object HybridClassLoader : ClassLoader(ClassLoaders.BOOT) {

    private val bootClassLoader = ClassLoaders.BOOT
    lateinit var moduleParentClassLoader: ClassLoader
    lateinit var hostClassLoader: ClassLoader

    @Volatile
    var prioritizeHostClasses = false

    override fun findClass(name: String): Class<*> {
        try {
            return bootClassLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
        }

        if (!prioritizeHostClasses) {
            try {
                return moduleParentClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }

            try {
                return hostClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        else {
            try {
                return hostClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }

            try {
                return moduleParentClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }

        throw ClassNotFoundException(name)
    }
}
