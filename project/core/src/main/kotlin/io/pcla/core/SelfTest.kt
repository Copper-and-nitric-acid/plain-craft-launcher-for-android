package io.pcla.core

import io.pcla.core.asset.AssetIndex
import io.pcla.core.bench.GcLogParser
import io.pcla.core.bench.GcReport
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.ProfileTable
import io.pcla.core.game.GamePlan
import io.pcla.core.game.GameVerifier
import io.pcla.core.game.NativeExtractor
import io.pcla.core.launch.AndroidRuntime
import io.pcla.core.launch.AwtRuntime
import io.pcla.core.launch.Preflight
import io.pcla.core.launch.ClasspathBuilder
import io.pcla.core.launch.DegradeLadder
import io.pcla.core.launch.LaunchCommand
import io.pcla.core.launch.JavaPolicy
import io.pcla.core.launch.JvmTuner
import io.pcla.core.launch.JavaProbe
import io.pcla.core.launch.Platform
import io.pcla.core.launch.VersionMeta
import io.pcla.core.version.VersionManifest
import io.pcla.core.net.MemoryPulse
import io.pcla.core.net.Probe
import io.pcla.core.net.UrlHealth
import io.pcla.core.net.Verdict
import io.pcla.core.launch.Lever
import io.pcla.core.launch.RunObservation
import io.pcla.core.launch.LaunchTarget
import io.pcla.core.launch.Loader
import io.pcla.core.launch.Severity
import io.pcla.core.launch.VersionPolicy
import java.io.File

/**
 * Self-check harness that does not depend on JUnit.
 *
 * Why hand-written: the core module must run end-to-end in Termux (no Android SDK,
 * no Gradle) with one command — that is the baseline for "verifiable on the phone".
 *
 *   kotlinc core/src/main/kotlin -include-runtime -d core.jar
 *   java -jar core.jar device_profiles.json
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        println("  ✓ $name")
    } else {
        failed++
        println("  ✗ $name ${if (detail.isEmpty()) "" else "→ $detail"}")
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual, "期望 $expected，实际 $actual")

fun main(args: Array<String>) {
    // Subcommand: java -jar core.jar gc <gc.log>
    if (args.firstOrNull() == "gc") {
        val path = args.getOrNull(1)
        if (path == null) {
            println("用法: java -jar core.jar gc <gc.log>")
            kotlin.system.exitProcess(2)
        }
        println(GcReport.render(GcLogParser.parse(File(path).readText())))
        return
    }

    // Subcommand: java -jar core.jar degrade <json> <version> <current RD> <measured fps> [sample s] [heap MB] [GC share %] [reclaim %] [available MB]
    if (args.firstOrNull() == "degrade") {
        val json = File(args.getOrNull(1) ?: "device_profiles.json").readText()
        val table = ProfileTable.parse(json)
        val version = args.getOrNull(2) ?: "1.16.5"
        val rd = args.getOrNull(3)?.toIntOrNull() ?: 17
        val fps = args.getOrNull(4)?.toDoubleOrNull() ?: 18.0
        val obs = RunObservation(
            avgFps = fps,
            sampleSeconds = args.getOrNull(5)?.toIntOrNull() ?: 60,
            renderDistance = rd,
            heapMb = args.getOrNull(6)?.toIntOrNull() ?: 2048,
            gcPausePct = args.getOrNull(7)?.toDoubleOrNull(),
            gcReclaimPct = args.getOrNull(8)?.toDoubleOrNull(),
            memAvailableMb = args.getOrNull(9)?.toIntOrNull(),
        )
        val spec = DeviceSpec("MT6833V|PHJ110", "PHJ110", "Mali-G57 MC2", "OpenGL ES 3.2", 7460L)
        val profile = table.match(spec)
        val tuned = JvmTuner.tune(spec, profile, LaunchTarget(version, Loader.FORGE, 0, rd))
        val plan = DegradeLadder.plan(obs, spec, profile, version, tuned)
        println("=== 降级建议：$version / RD$rd / ${obs.avgFps}fps ===")
        if (plan.actions.isEmpty()) println("  无需动作") else plan.actions.forEachIndexed { i, a ->
            println("  ${i + 1}. [${a.lever}] ${a.from} → ${a.to}")
            println("     理由：${a.reason}")
            println("     预期：${a.expected}")
        }
        println("  堆：${plan.heapVerdict}")
        plan.notes.forEach { println("  注：$it") }
        return
    }

    val jsonPath = args.firstOrNull() ?: "device_profiles.json"
    val json = File(jsonPath).readText()

    println("=== 1. 档位表解析 ===")
    val table = ProfileTable.parse(json)
    println("  解析到 ${table.profiles.size} 个档位：${table.profiles.joinToString { it.id }}")
    check("包含 generic 兜底", table.byId("generic") != null)

    val spec = DeviceSpec(
        socKey = "MT6833V/ZA|MT6833|MT6833V|PHJ110",
        deviceModel = "OPPO PHJ110",
        glRenderer = "Mali-G57 MC2",
        glVersion = "OpenGL ES 3.2 v1.r32p1",
        ramMb = 7634,
    )

    println("\n=== 2. 机型匹配 ===")
    val profile = table.match(spec)
    eq("匹配到 mt6833v", "mt6833v", profile.id)
    eq("未知机型回落 generic", "generic",
        table.match(spec.copy(socKey = "XYZ|FOO", deviceModel = "Nope", glRenderer = "Adreno 610")).id)
    check("仅 GPU 命中不足以认定 SoC",
        table.match(spec.copy(socKey = "UNKNOWN", deviceModel = "UNKNOWN")).id == "generic")

    println("\n=== 3. 版本推荐（按实测分数排序） ===")
    val rec = VersionPolicy.pick(profile)
    eq("推荐版本", "1.16.5", rec.version)
    println("  理由：${rec.reason}")
    println("  备选：${rec.alternatives}")
    val ranked = VersionPolicy.ranking(profile)
    println("  排序：" + ranked.joinToString(" > ") { "${it.first}(${it.second.score})" })
    eq("排序顺序", listOf("1.16.5", "1.20.1", "1.12.2", "1.18"), ranked.map { it.first })

    println("\n=== 4. JVM 参数生成（RD=4，无 mod） ===")
    val cases = listOf("1.12.2", "1.16.5", "1.18", "1.19.2", "1.20.1")
    // Default heap = the baseline this device measured (2048). 1.18 once computed 3072
    // from "version factor × sweet spot", but that is not what an 8GB machine should
    // get by default: raising the heap waits for runtime GC evidence (see DegradeLadder).
    val expectedHeap = mapOf("1.12.2" to 2048, "1.16.5" to 2048, "1.18" to 2048, "1.19.2" to 2048, "1.20.1" to 2048)
    for (v in cases) {
        val cfg = JvmTuner.tune(spec, profile, LaunchTarget(v, Loader.FORGE, 0, 4))
        val block = cfg.diagnostics.filter { it.severity == Severity.BLOCK }
        println("  %-7s heap=%4dMB  blocked=%-5s  %s".format(v, cfg.heapMb, block.isNotEmpty(), block.firstOrNull()?.code ?: ""))
        eq("$v 堆大小", expectedHeap[v], cfg.heapMb)
    }

    println("\n=== 5. 风险拦截 ===")
    val rd17 = JvmTuner.tune(spec, profile, LaunchTarget("1.16.5", Loader.FORGE, 0, 17))
    check("RD17 被标记为 BLOCK", rd17.blocked)
    eq("RD17 诊断码", "RD_BAN", rd17.diagnostics.first { it.severity == Severity.BLOCK }.code)
    // Render distance costs VRAM and CPU, not heap. Measured: 1.18 at RD17 with 2048MB had no OOM.
    check("RD17 不改变堆", rd17.heapMb == 2048, "实际 ${rd17.heapMb}")

    val avoid = JvmTuner.tune(spec, profile, LaunchTarget("1.19.2", Loader.FORGE, 0, 4))
    check("1.19.2 被标记为 BLOCK", avoid.blocked)

    val greedy = JvmTuner.tune(spec, profile, LaunchTarget("1.18", Loader.FORGE, 0, 4, heapOverrideMb = 8192))
    check("超安全线被压回", greedy.heapMb <= 3072, "实际 ${greedy.heapMb}")
    eq("安全上限受机型实测兜住", 3072, JvmTuner.safeCeilingMb(spec, profile))
    check("超安全线有告警", greedy.diagnostics.any { it.code == "HEAP_CLAMPED" })

    println("\n=== 6. GC 日志解析（真实日志片段） ===")
    val sample = """
        [309.500s][info][gc] GC(226) Pause Young (Normal) (G1 Evacuation Pause) 980M->941M(2048M) 180.000ms
        [310.012s][info][gc] GC(227) Pause Young (Normal) (G1 Evacuation Pause) 1004M->948M(2048M) 135.380ms
        [310.669s][info][gc] GC(228) Pause Young (Concurrent Start) (G1 Evacuation Pause) 1037M->973M(2048M) 106.334ms
        [310.670s][info][gc] GC(229) Concurrent Mark Cycle
        [316.412s][info][gc] GC(235) Pause Young (Normal) (G1 Evacuation Pause) 1197M->1144M(2048M) 297.910ms
        [317.407s][info][gc] GC(236) Pause Young (Normal) (G1 Evacuation Pause) 1233M->1176M(2048M) 290.534ms
        [318.859s][info][gc] GC(229) Concurrent Mark Cycle 8189.169ms
        [674.638s][info][gc] GC(311) Pause Young (Mixed) (G1 Evacuation Pause) 632M->528M(2048M) 48.514ms
    """.trimIndent()
    val events = GcLogParser.parse(sample)
    eq("事件数", 7, events.size)
    eq("其中并发事件", 1, events.count { !it.stopTheWorld })
    // GC(229)'s start line has no duration; it is "the start of the same cycle", so counting it again doubles the concurrent cycle count
    check("无时长的并发起始行被丢弃", events.none { it.atMillis == 310670L })
    val sum = GcLogParser.summarize(events)
    eq("最长 STW 停顿", 297.91, sum.maxMs)
    eq("最长并发周期", 8189.169, sum.maxConcurrentMs)
    eq("堆回收生效(1197→1144)", true, events.any { it.heapBeforeMb == 1197 && it.heapAfterMb == 1144 })
    check("簇检测命中 300s 窗口", sum.clusters.any { it.startSeconds == 300 })

    println("\n=== 7. 降级阶梯（帧率低于 20 时先动什么） ===")
    // Scenario A: 1.16.5 at RD17, GC is clean → must touch render distance first, never the heap
    val planA = DegradeLadder.plan(
        RunObservation(avgFps = 18.0, sampleSeconds = 60, renderDistance = 17, heapMb = 2048,
            gcPausePct = 0.4, liveSetMb = 1100),
        spec, profile, "1.16.5",
    )
    eq("第一步是渲染距离", Lever.RENDER_DISTANCE, planA.actions.first().lever)
    eq("RD 17 降到 8", "8", planA.actions.first().to)
    check("GC 干净时不缩堆", planA.heapVerdict.contains("缩堆无效"), planA.heapVerdict)
    check("不掉进缩堆陷阱", planA.actions.none { it.lever == Lever.HEAP })

    // Scenario B: 1.18 live set maxed out + GC eats 9% → the direction is to add heap, not shrink
    val tuned118 = JvmTuner.tune(spec, profile, LaunchTarget("1.18", Loader.FORGE, 0, 4))
    val planB = DegradeLadder.plan(
        RunObservation(avgFps = 15.0, sampleSeconds = 60, renderDistance = 4, heapMb = 2048,
            gcPausePct = 9.0, gcReclaimPct = 5.0, liveSetMb = 950),
        spec, profile, "1.18", tuned118,
    )
    val heapB = planB.actions.firstOrNull { it.lever == Lever.HEAP }
    check("GC 吃帧时建议加堆", heapB != null && heapB.to == "3072", heapB?.to ?: "无动作")

    // Scenario C: low-RAM device + tight available memory → only then should the heap shrink (the user's 1024 holds here)
    val spec3g = spec.copy(ramMb = 3072L)
    val planC = DegradeLadder.plan(
        RunObservation(avgFps = 15.0, sampleSeconds = 60, renderDistance = 4, heapMb = 2048,
            gcPausePct = 1.0, liveSetMb = 400, memAvailableMb = 700),
        spec3g, profile, "1.16.5",
    )
    val heapC = planC.actions.firstOrNull { it.lever == Lever.HEAP }
    check("内存吃紧才缩堆", heapC != null && heapC.to == "1075", heapC?.to ?: "无动作")
    check("缩堆理由写的是内存不是帧率", planC.heapVerdict.contains("内存"), planC.heapVerdict)

    // Scenario D: sample too short → draw no conclusion
    val planD = DegradeLadder.plan(
        RunObservation(avgFps = 12.0, sampleSeconds = 12, renderDistance = 17, heapMb = 2048),
        spec, profile, "1.16.5",
    )
    check("采样不足不下结论", planD.isEmpty, planD.heapVerdict)

    // Java version policy: getting it wrong yields Unsupported class file major version
    eq("1.12.2 → Java 8", 8, JavaPolicy.requiredMajor("1.12.2"))
    eq("1.16.5 → Java 8", 8, JavaPolicy.requiredMajor("1.16.5"))
    eq("1.18 → Java 17", 17, JavaPolicy.requiredMajor("1.18"))
    eq("1.19.2 → Java 17", 17, JavaPolicy.requiredMajor("1.19.2"))
    eq("1.20.1 → Java 17", 17, JavaPolicy.requiredMajor("1.20.1"))
    eq("1.20.5 → Java 21", 21, JavaPolicy.requiredMajor("1.20.5"))
    eq("1.21.4 → Java 21", 21, JavaPolicy.requiredMajor("1.21.4"))
    eq("快照版本回落 Java 8", 8, JavaPolicy.requiredMajor("23w45a"))

    // Version json → launch command line
    val metaJson = """
        {"id":"1.16.5","mainClass":"net.minecraft.client.main.Main","assets":"1.16",
         "javaVersion":{"majorVersion":8},
         "arguments":{"jvm":["-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"],
                      "game":["--username","${'$'}{auth_player_name}"]},
         "libraries":[
          {"name":"org.lwjgl:lwjgl:3.2.2","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar","url":"https://x/lwjgl.jar","sha1":"x"}},
           "rules":[{"action":"allow","os":{"name":"linux"}},{"action":"disallow","os":{"name":"windows"}}]},
          {"name":"org.lwjgl:lwjgl:3.2.2:natives-linux","downloads":{"classifiers":{"natives-linux":{"path":"org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-linux.jar","url":"https://x/nat.jar","sha1":"y"}}},"natives":{"linux":"natives-linux"}},
          {"name":"com.example:native-ish:1.0:natives-linux","downloads":{"classifiers":{"natives-linux":{"path":"com/example/native-ish/1.0/native-ish-1.0-natives-linux.jar","url":"https://x/e.jar","sha1":"z"}}},"natives":{"linux":"natives-linux"}},
          {"name":"com.sun:winonly:1.0","downloads":{"artifact":{"path":"com/sun/winonly/1.0/winonly-1.0.jar"}},"rules":[{"action":"allow","os":{"name":"windows"}}]}
         ]}
    """
    val meta = VersionMeta.parse(metaJson)
    eq("解析出 mainClass", "net.minecraft.client.main.Main", meta.mainClass)
    eq("解析出 java 大版本", 8, meta.javaMajor)
    eq("库声明数量", 4, meta.libraries.size)
    check("windows 专属库被 rules 排除", !Platform.allows(meta.libraries[3].rules))
    val ph = ClasspathBuilder.placeholders("CP", "/n", "/g", "/a", "1.16", "1.16.5")
    val plan = ClasspathBuilder.build(meta, "/libs", "/g/client.jar", ph)
    check("classpath 不掺清单里的 lwjgl", !plan.classpath.contains("lwjgl-3.2.2.jar"))
    check("classpath 含 client.jar", plan.classpath.endsWith("client.jar"))
    check("windows 库不进 classpath", !plan.classpath.contains("winonly"))
    eq("natives 只收 1 个", 1, plan.nativeJars.size)
    check("natives 路径正确", plan.nativeJars[0].endsWith("native-ish-1.0-natives-linux.jar"))
    eq("无未替换占位符", 0, plan.unresolved.size)
    check("natives_directory 已替换", plan.jvmArgs.any { it == "-Djava.library.path=/n" })
    check("玩家名已替换", plan.gameArgs.contains("PCLA"))
    check("启动前检查无问题", ClasspathBuilder.problems(plan).isEmpty(), ClasspathBuilder.problems(plan).joinToString())

    // Version manifest and asset index
    val manifest = VersionManifest.parse("""
        {"latest":{"release":"1.21.4","snapshot":"25w45a"},
         "versions":[{"id":"1.16.5","type":"release","url":"https://x/1.16.5.json"},
                     {"id":"25w45a","type":"snapshot","url":"https://x/25w45a.json"}]}
    """)
    eq("manifest 版本数", 2, manifest.size)
    eq("命中 release", "1.16.5", VersionManifest.find(manifest, "1.16.5")?.id)
    check("未知版本返回 null", VersionManifest.find(manifest, "9.9.9") == null)
    val assets = AssetIndex.parse("""
        {"objects":{"icons/icon_16x16.png":{"hash":"ab12cd34","size":143}}}
    """)
    eq("asset 对象数", 1, assets.size)
    eq("asset 分片路径", "objects/ab/ab12cd34", AssetIndex.relativePath(assets[0].hash))

    // Full command line
    val argv = LaunchCommand.build("/rt/bin/java", plan, 2048, "/natives")
    eq("argv 第一项是 java", "/rt/bin/java", argv[0])
    check("带 -Xmx2048M", argv.contains("-Xmx2048M"))
    val cpCount = argv.count { it == plan.classpath }
    check("classpath 不重复", cpCount == 1, "count=$cpCount argv=${LaunchCommand.render(argv)}")
    val classic = plan.copy(jvmArgs = plan.jvmArgs.filterNot { it == "-cp" || it == plan.classpath })
    val argv2 = LaunchCommand.build("/rt/bin/java", classic, 2048, "/natives")
    check("缺 -cp 时自动补", argv2.contains("-cp") && argv2.contains(classic.classpath))
    check("mainClass 在 gameArgs 前", argv.indexOf(plan.mainClass) < argv.indexOf("--username"))
    check("空格参数渲染时加引号", LaunchCommand.render(listOf("a b")).startsWith("\""))

    // Which files to download
    val gp = GamePlan.build(meta, assets, "1.16.5", clientUrl = "https://x/client.jar")
    // The sample allows two through: lwjgl 3.2.2 (has an artifact) and com.example:native-ish (classifier only)
    eq("库文件数", 1, gp.libraries.size)
    eq("natives 文件数", 2, gp.natives.size)
    eq("asset 文件数", 1, gp.assets.size)
    eq("文件总数（含 client.jar）", 5, gp.count)
    eq("asset 分片 URL", "https://resources.download.minecraft.net/ab/ab12cd34", gp.assets[0].url)
    eq("asset 落盘路径", "objects/ab/ab12cd34", gp.assets[0].relativePath)
    eq("client.jar 路径", "versions/1.16.5/1.16.5.jar", gp.clientJar?.relativePath)

    // Hash recheck: report only, never delete files
    val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "pcla-selftest").apply { deleteRecursively(); mkdirs() }
    val fake = java.io.File(tmp, "objects/ab/ab12cd34").apply { parentFile.mkdirs(); writeText("x") }
    val bad = GameVerifier.verify(gp.assets, tmp) { "deadbeef" }
    eq("哈希不符被发现", 1, bad.size)
    val good = GameVerifier.verify(gp.assets, tmp) { "ab12cd34" }
    eq("哈希一致时无告警", 0, good.size)
    check("根因文件确实存在", fake.isFile)

    // Natives extraction: only .so, skipping META-INF and plain files
    val jar = java.io.File(tmp, "natives-test.jar")
    java.util.zip.ZipOutputStream(jar.outputStream()).use { zos ->
        listOf("META-INF/MANIFEST.MF" to "m", "libtest.so" to "s", "readme.txt" to "r", "sub/libother.so" to "o")
            .forEach { (n, c) ->
                zos.putNextEntry(java.util.zip.ZipEntry(n)); zos.write(c.toByteArray()); zos.closeEntry()
            }
    }
    val nativesDir = java.io.File(tmp, "natives")
    val nr = NativeExtractor.extract(jar, nativesDir)
    eq("解出 2 个 so", 2, nr.extracted)
    eq("跳过的条目数", 1, nr.skipped)
    check("libtest.so 落盘", java.io.File(nativesDir, "libtest.so").isFile)
    check("子目录里的 so 也平铺出来", java.io.File(nativesDir, "libother.so").isFile)
    check("so 带可执行位", java.io.File(nativesDir, "libtest.so").canExecute())
    check("META-INF 不落盘", !java.io.File(nativesDir, "MANIFEST.MF").exists())

    // Swap LWJGL on the phone: strip every org.lwjgl from the manifest, bundled jars go first
    val lmeta = VersionMeta.parse(
        """{"id":"t","mainClass":"net.minecraft.client.main.Main","arguments":{"game":["--username","PCLA","--version","1.16.5","--gameDir","/g","--assetsDir","/a","--assetIndex","1.16"]},"libraries":["""
            + """{"name":"org.lwjgl:lwjgl:3.2.2","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar","url":"https://x/lwjgl.jar"}}},"""
            + """{"name":"org.lwjgl:lwjgl-glfw:3.2.2","downloads":{"classifiers":{"natives-linux":{"path":"org/lwjgl/lwjgl-glfw/3.2.2/lwjgl-glfw-3.2.2-natives-linux.jar","url":"https://x/g.jar"}}}},"""
            + """{"name":"io.netty:netty-all:4.1.25","downloads":{"artifact":{"path":"io/netty/netty-all/4.1.25/netty-all-4.1.25.jar","url":"https://x/n.jar"}}}"""
            + """]}"""
    )
    val (stripped, dropped) = AndroidRuntime.strip(lmeta)
    eq("去掉 2 条 org.lwjgl", 2, dropped)
    eq("剩下 1 条", 1, stripped.libraries.size)
    check("剩下来的是 netty", stripped.libraries.single().name.contains("netty"))
    val lplace = ClasspathBuilder.placeholders("", "/n", "/g", "/a", "1.16", "1.16.5")
    val lplan = ClasspathBuilder.build(stripped, "/libs", "/c.jar", lplace, listOf("/x/lwjgl.jar", "/x/lwjgl-stb.jar"))
    check("随包 jar 在 classpath 最前", lplan.classpath.startsWith("/x/lwjgl.jar:/x/lwjgl-stb.jar"))
    check("没有下载 lwjgl 的 natives", lplan.nativeJars.none { AndroidRuntime.isLwjgl(it) })
    check("netty 也在 classpath", lplan.classpath.contains("/libs/io/netty/netty-all"))
    eq("随包 jar 有 9 个", 9, AndroidRuntime.JARS.size)
    check("merged-modules 排第一", AndroidRuntime.JARS.first().contains("merged-modules"))
    check("随包 so 名单覆盖 opengl", AndroidRuntime.NATIVES.any { it.contains("opengl") })

    // Preflight: deliberately build one "good" plan and one "bad" one
    val goodPlan = ClasspathBuilder.build(
        stripped, "/libs", "/c.jar",
        ClasspathBuilder.placeholders("", "/n:/m:/j", "/g", "/a", "1.16", "1.16.5"),
        listOf("/x/lwjgl-3.3.3-merged-modules.jar") + AwtRuntime.JARS.map { "/x/" + it },
    )
    val goodChecks = Preflight.run(goodPlan, AndroidRuntime.JARS, listOf("/n", "/m", "/j"), listOf("libpojavexec.so", "libgl4es_114.so"), 8, "/n:/m:/j")
    check("好计划全部通过", goodChecks.all { it.ok })
    val badPlan = ClasspathBuilder.build(stripped, "/libs", "/c.jar", ClasspathBuilder.placeholders("", "/n", "/g", "/a", "1.16", "1.16.5"))
    val badChecks = Preflight.run(badPlan, emptyList(), listOf("/n"), emptyList(), 17)
    check("少了 merged-modules 会被抓出来", badChecks.any { !it.ok && it.name.contains("merged-modules") })
    check("版本错配会被抓出来", badChecks.any { !it.ok && it.name.contains("Java 大版本") })
    check("自检渲染带 ✓/✗", Preflight.render(goodChecks).startsWith("✓"))

    // AWT: the Caciocavallo jars and three launch properties
    eq("cacio 两个 jar", 2, AwtRuntime.JARS.size)
    check("cacio 顺序 shared 在前", AwtRuntime.JARS.first().contains("shared"))
    val props = AwtRuntime.jvmProps(1280, 720)
    check("关掉 headless", props.contains("-Djava.awt.headless=false"))
    check("指定 CTC 工具包", props.any { it.contains(AwtRuntime.TOOLKIT) })
    check("带上屏幕尺寸", props.any { it.startsWith("-Dcacio.managed.screensize=1280x720") })
    val awtPlan = ClasspathBuilder.build(stripped, "/libs", "/c.jar", lplace, AwtRuntime.JARS.map { "/x/" + it })
    check("cacio 在 classpath 里", awtPlan.classpath.contains("cacio-"))
    val awtChecks = Preflight.run(awtPlan, AwtRuntime.JARS, listOf("/n", "/m", "/j"), emptyList(), 8, "/n:/m:/j")
    check("AWT 检查项存在", awtChecks.any { it.name.contains("AWT") })



    // org.lwjgl 3.2.2 from the manifest must be stripped, or it loads mixed with the bundled 3.3.3
    check("lwjgl 3.2.2 不进 classpath", !plan.classpath.contains("lwjgl/3.2.2"))
    check("lwjgl natives 也不进", plan.nativeJars.none { it.contains("3.2.2") })
    val keepLwjgl = ClasspathBuilder.build(lmeta, "/libs", "/c.jar", lplace, emptyList(), emptySet())
    check("关掉剔除后能拿回来", keepLwjgl.classpath.contains("lwjgl/3.2.2"))

    // Version detection from java -version: a broken JRE unpack shows up in this line first
    val v8 = JavaProbe.parse("""
        openjdk version "1.8.0_442"
        OpenJDK Runtime Environment (build 1.8.0_442-b06)
    """.trimIndent())
    eq("认出 Java 8 的版本号", "1.8.0_442", v8.version)
    eq("1.8.0_442 → 大版本 8", 8, v8.major)
    val v17 = JavaProbe.parse("openjdk version \"17.0.9\" 2023-10-17\nOpenJDK 64-Bit Server VM")
    eq("17.0.9 → 大版本 17", 17, v17.major)
    eq("21 → 大版本 21", 21, JavaProbe.majorOf("21"))
    eq("认不出返回 null", null, JavaProbe.majorOf("abc"))
    val empty = JavaProbe.parse("Error: could not find libjli.so")
    check("没有版本行就算失败", !empty.ok)
    check("缺失的报错只说一句", JavaProbe.problems(8, empty).size == 1)
    check("版本对得上没问题", JavaProbe.problems(8, v8).isEmpty())
    val tooHigh = JavaProbe.problems(8, v17)
    check("高版本被抓出来", tooHigh.any { it.contains("对不上") })
    check("高版本提示 class file major", tooHigh.any { it.contains("Unsupported class file major version") })
    check("渲染带需要的大版本", JavaProbe.render(v8, 8).contains("8"))

    // URL health check: OK / 404 / server down / timeout — the four failure modes must read differently
    val okProbe = Probe("清单", "https://a", 200, 42)
    check("200 算可用", okProbe.ok)
    check("可用带毫秒", UrlHealth.classify(okProbe).contains("42ms"))
    check("404 说明文件没了", UrlHealth.classify(Probe("x", "u", 404)).contains("文件没了"))
    check("超时不可用", !UrlHealth.classify(Probe("x", "u", 0, 6000, "timeout")).startsWith("可用"))
    val mixed = listOf(okProbe, Probe("挂了", "https://b", 500))
    check("混合时给 n/m 可用", UrlHealth.summary(mixed).startsWith("1/2 可用"))
    check("最快的是它", UrlHealth.summary(mixed).contains("清单"))
    check("全挂的总结", UrlHealth.summary(listOf(Probe("x", "u", 0, 0, "timeout"))).contains("全部"))

    // Heap shape: a high plateau that never drops means a leak; violent swings mean churn; fixes differ
    val const = MemoryPulse.classify(listOf(1800, 1805, 1802, 1804), 2048)
    check("高位不动判便秘", const.verdict == Verdict.便秘)
    check("便秘说居高不下", const.detail.contains("居高不下"))
    val churn = MemoryPulse.classify(listOf(200, 900, 300, 950, 250), 2048)
    check("剧烈抖动判拉稀", churn.verdict == Verdict.拉稀)
    check("拉稀说变化太快", churn.detail.contains("变化太快"))
    check("采样太少先别下结论", MemoryPulse.classify(listOf(1), 2048).verdict == Verdict.正常)
    check("低占用判正常", MemoryPulse.classify(listOf(100, 120, 110), 2048).verdict == Verdict.正常)

    println("\n========================================")
    println("通过 $passed 项，失败 $failed 项")
    if (failed > 0) kotlin.system.exitProcess(1)
}
