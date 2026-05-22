package dev.ujhhgtg.wekit.utils.reflection

inline val int get() = Int::class.javaPrimitiveType!!

inline val bool get() = Boolean::class.javaPrimitiveType!!

inline val byte get() = Byte::class.javaPrimitiveType!!

inline val short get() = Short::class.javaPrimitiveType!!

inline val long get() = Long::class.javaPrimitiveType!!

inline val float get() = Float::class.javaPrimitiveType!!

inline val double get() = Double::class.javaPrimitiveType!!

inline val char get() = Char::class.javaPrimitiveType!!

inline val BInt get() = Int::class.java

inline val BBool get() = Boolean::class.java

inline val BByte get() = Byte::class.java

inline val BShort get() = Short::class.java

inline val BLong get() = Long::class.java

inline val BFloat get() = Float::class.java

inline val BDouble get() = Double::class.java

inline val BChar get() = Char::class.java

inline val BString get() = String::class.java
