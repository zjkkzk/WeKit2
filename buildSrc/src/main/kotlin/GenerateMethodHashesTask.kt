import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

abstract class GenerateMethodHashesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val srcDir = sourceDir.get().asFile
        val outDir = outputDir.get().asFile
        val outputFile = outDir.resolve("${namespace.get().replace(".", "/")}/dexkit/cache/GeneratedMethodHashes.kt")

        val hashMap = mutableMapOf<String, String>()

        // Pre-filter files containing the token to save time, then strictly validate inside
        srcDir.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("IResolvesDex") }.forEach { file ->
            val content = file.readText()
            val packageName = Regex("""package\s+([\w.]+)""").find(content)?.groupValues?.get(1)

            // Locate the main class or object declaration
            val classMatch = Regex("""\b(?:class|object)\s+(\w+)\b""").find(content) ?: return@forEach
            val className = classMatch.groupValues[1]

            // Extract the class signature declaration up to its opening body brace
            val startIndex = classMatch.range.first
            val braceIndex = content.indexOf('{', startIndex)
            if (braceIndex == -1) return@forEach
            val classSignature = content.substring(startIndex, braceIndex)

            // Strict check: Must have an inheritance colon ':' and explicitly list 'IResolvesDex'
            if (!classSignature.contains(":") || !Regex("""\bIResolvesDex\b""").containsMatchIn(classSignature)) {
                return@forEach // Skip files that only import or reference the interface internally
            }

            val fullClassName = if (packageName != null) "$packageName.$className" else className
            val blocks = mutableListOf<String>()

            // 1. Extract resolveDex method body if it exists
            val resolveDexMatch = Regex("""override\s+fun\s+resolveDex\s*\(""").find(content)
            if (resolveDexMatch != null) {
                val start = content.indexOf('{', resolveDexMatch.range.last)
                if (start != -1) {
                    var count = 0
                    for (i in start until content.length) {
                        if (content[i] == '{') count++ else if (content[i] == '}') count--
                        if (count == 0) {
                            blocks.add(content.substring(start, i + 1))
                            break
                        }
                    }
                }
            }

            // 2. Extract inline search blocks if they exist (by dexClass, by dexMethod, by dexConstructor)
            val inlineKeywordRegex = Regex("""\bby\s+dex(?:Class|Method|Constructor)\b""")
            val separatorRegex = Regex("""\b(val|fun|private|public|internal|class|object|override)\b""")

            inlineKeywordRegex.findAll(content).forEach { match ->
                val startScan = match.range.last + 1
                val nextOpenBrace = content.indexOf('{', startScan)
                if (nextOpenBrace != -1) {
                    val intermediate = content.substring(startScan, nextOpenBrace)
                    if (!separatorRegex.containsMatchIn(intermediate)) {
                        var count = 0
                        for (i in nextOpenBrace until content.length) {
                            if (content[i] == '{') count++ else if (content[i] == '}') count--
                            if (count == 0) {
                                blocks.add(content.substring(nextOpenBrace, i + 1))
                                break
                            }
                        }
                    }
                }
            }

            // Guardrail check only applies to actual implementations now
            if (blocks.isEmpty()) {
                error("Class $fullClassName implements IResolvesDex but has neither a resolveDex() body nor any inline dex blocks.")
            }

            val combinedBody = blocks.joinToString(separator = "\n")
            val hash = MessageDigest.getInstance("MD5").digest(combinedBody.toByteArray()).joinToString("") { "%02x".format(it) }
            hashMap[fullClassName] = hash
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package ${namespace.get()}.dexkit.cache

            object GeneratedMethodHashes {
                val HASHES = mapOf(${hashMap.entries.sortedBy { it.key }.joinToString(", \n") { "\"${it.key}\" to \"${it.value}\"" }})
            }
        """.trimIndent()
        )
    }
}
