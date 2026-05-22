@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils.reflection

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Modifier

inline fun <T : AccessibleObject> T.makeAccessible(): T {
    this.isAccessible = true
    return this
}

inline val Member.isPublic get() = Modifier.isPublic(modifiers)
inline val Member.isPrivate get() = Modifier.isPrivate(modifiers)
inline val Member.isStatic get() = Modifier.isStatic(modifiers)
inline val Member.isAbstract get() = Modifier.isAbstract(modifiers)
