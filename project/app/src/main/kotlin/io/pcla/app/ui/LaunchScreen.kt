package io.pcla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Intent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.unit.sp
import io.pcla.app.GameActivity
import io.pcla.app.GameInstaller
import io.pcla.app.NetProbe
import io.pcla.app.InProcessLauncher
import io.pcla.app.RuntimePack
import io.pcla.app.SurfaceBridge
import io.pcla.core.launch.AndroidRuntime
import io.pcla.core.launch.JavaPolicy
import io.pcla.core.launch.JavaProbe
import io.pcla.core.launch.Preflight
import io.pcla.core.net.UrlHealth
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launch page: install (download + unpack) → launch (exec java) → log.
 * It does one thing: print exactly what happens after you tap Launch.
 */
@Composable
internal fun LaunchScreen(heapMb: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf("1.16.5") }
    var stage by remember { mutableStateOf("未开始") }
    var log by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<GameInstaller.Installed?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The game window. On in-process launch this SurfaceView hands over its Surface,
    // which libpcla_surface.so passes on to the ANativeWindow kept in libpojavexec.so.
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceState by remember { mutableStateOf("还没创建") }
    // Heap shape check: one sample per second; once the window fills, classify leak vs. churn.
    var memLine by remember { mutableStateOf("采样中…") }
    var urlLine by remember { mutableStateOf("还没测过") }

    LaunchedEffect(Unit) {
        io.pcla.app.MemorySampler.markLaunchStart()
        while (true) {
            val snap = io.pcla.app.MemorySampler.sample()
            memLine = "${snap.pulse.verdict}：${snap.pulse.detail}"
            delay(1000)
        }
    }

    fun append(line: String) {
        log = if (log.length > 20000) log.takeLast(12000) + "\n" + line else log + "\n" + line
    }

    PclCard("启动") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PclField("版本", version)
            PclField("堆", "${heapMb}MB（来自档位推荐）")
            PclField("状态", stage)
            PclField("网址", urlLine)
            PclField("内存体检", memLine)
            PclButton(
                text = if (busy) "进行中…" else "① 下载并解压",
                enabled = !busy,
                highlight = true,
                onClick = {
                    append("--- 点了①：下载并解压 $version ---")
                    stage = "准备开始…"
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GameInstaller.install(context, version, heapMb) { p ->
                                    stage = buildString {
                                        append(p.stage)
                                        if (p.total > 0) append(" ${p.done}/${p.total}")
                                        if (p.bytes > 0) append(" ${p.bytes / 1048576}MB")
                                        if (p.message.isNotEmpty()) append(" · ${p.message}")
                                    }
                                }
                            }
                        }.onSuccess { installed = it; stage = "就绪，可以启动" }
                            .onFailure {
                                val msg = it.message ?: it.javaClass.simpleName
                                stage = "失败：$msg"
                                append("安装失败：$msg")
                            }
                        busy = false
                    }
                },
            )
            PclButton(
                text = "② 启动",
                enabled = !busy && installed != null,
                onClick = {
                    val ready = installed ?: return@PclButton
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GameInstaller.launch(context, ready, heapMb) { line -> append(line) }
                            }
                        }.onFailure { append("启动失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            // Confirms the bundled JRE can actually run before the 366MB of game files are needed
            PclButton(
                text = "JRE 自检",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val major = JavaPolicy.requiredMajor(version)
                                append("--- JRE 自检（$version 需要 Java $major）---")
                                val runtime = RuntimePack.ensure(context, major) { mb ->
                                    stage = "解包 JRE $major · ${mb / 1048576}MB"
                                }
                                append("java: " + runtime.javaBin.absolutePath)
                                append("存在=" + runtime.javaBin.exists() +
                                    " 可执行=" + runtime.javaBin.canExecute() +
                                    " 大小=" + runtime.javaBin.length())
                                val argv = listOf(runtime.javaBin.absolutePath) + JavaProbe.ARGS
                                val proc = ProcessBuilder(argv)
                                    .redirectErrorStream(true)
                                    .apply {
                                        environment()["LD_LIBRARY_PATH"] = RuntimePack.libraryPath(runtime)
                                        environment()["HOME"] = runtime.home.absolutePath
                                    }
                                    .start()
                                val text = proc.inputStream.bufferedReader().readText()
                                val done = proc.waitFor()
                                append("退出码 $done")
                                append(text.trim().ifEmpty { "（没有任何输出）" })
                                val out = JavaProbe.parse(text)
                                append(JavaProbe.render(out, major))
                                JavaProbe.problems(major, out).forEach { append("✗ " + it) }
                            }
                        }.onFailure { append("自检失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            // Experimental path: start the JVM in-process. A crash takes the whole app down with it, so it gets its own button off the normal flow
            PclButton(
                text = "实验·同进程启动",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                append("--- 同进程启动（实验）---")
                                append(InProcessLauncher.loadNative(context).getOrElse {
                                    "载入失败：" + (it.message ?: it.javaClass.simpleName)
                                })
                                val ready = installed
                                if (ready == null) {
                                    stage = "先点①装完版本"
                                    append("先点①装完版本再来")
                                } else {
                                    val code = InProcessLauncher.launch(context, ready, heapMb) { line -> append(line) }
                                    append("launchJVM 返回 $code")
                                }
                            }
                        }.onFailure { append("同进程启动失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            PclButton(
                text = if (surface == null) "等 Surface 就绪" else "实验·交接 Surface 后启动",
                enabled = !busy && installed != null && surface != null,
                highlight = true,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            val s = surface
                            if (s == null) {
                                append("Surface 还没创建，等屏幕那一块显示出来")
                            } else {
                                append("--- 交接 Surface 后同进程启动 ---")
                                append("native 入口：" + SurfaceBridge.describe())
                                append("走 org.lwjgl.glfw.CallbackBridge.setupBridgeWindow（真 JNI）")
                                val err = SurfaceBridge.attach(s)
                                if (err != null) {
                                    append("✗ Surface 交接失败：" + err)
                                } else {
                                    append("✓ Surface 已交给 libpojavexec.so")
                                    append(InProcessLauncher.loadNative(context).getOrElse {
                                        "载入失败：" + (it.message ?: it.javaClass.simpleName)
                                    })
                                    val code = InProcessLauncher.launch(context, installed!!, heapMb) { append(it) }
                                    append("launchJVM 返回 $code")
                                }
                            }
                        }.onFailure { append("失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            // The proper path: full-screen Surface + in-process JVM live in a separate GameActivity.
            // The 660x192 preview on this page is only for "Check"; its size and lifecycle do not fit real runs.
            PclButton(
                text = "进入游戏窗口（全屏 Surface）",
                enabled = !busy && installed != null,
                highlight = true,
                onClick = {
                    val ready = installed
                    if (ready == null) {
                        append("先点①下载并解压")
                    } else {
                        append("--- 打开游戏窗口 ${ready.versionId}，Java ${ready.javaMajor} ---")
                        runCatching {
                            context.startActivity(
                                Intent(context, GameActivity::class.java)
                                    .putExtra(GameActivity.EXTRA_VERSION, ready.versionId)
                                    .putExtra(GameActivity.EXTRA_HEAP, heapMb),
                            )
                        }.onFailure { append("打不开游戏窗口：" + (it.message ?: it.javaClass.simpleName)) }
                    }
                },
            )
            PclButton(
                text = "网址体检",
                enabled = !busy,
                onClick = {
                    // URL health: the four addresses the download flow really uses, each probed with HEAD.
                    urlLine = "测速中…"
                    scope.launch {
                        append("--- 网址体检 ---")
                        val probes = withContext(Dispatchers.IO) { NetProbe.probeAll() }
                        append(NetProbe.render(probes))
                        urlLine = UrlHealth.summary(probes)
                    }
                },
            )
            PclButton(
                text = "检查",
                enabled = !busy && installed != null,
                onClick = {
                    val ready = installed ?: return@PclButton
                    val root = ready.root
                    val nativeDir = GameInstaller.nativesPath(context, root)
                    val nativeFiles = nativeDir.split(":").flatMap { d ->
                        File(d).listFiles()?.map { it.name } ?: emptyList()
                    }
                    val checks = Preflight.run(
                        plan = ready.plan,
                        jarNames = AndroidRuntime.JARS,
                        nativePaths = nativeDir.split(":"),
                        nativeFiles = nativeFiles,
                        expectedMajor = JavaPolicy.requiredMajor(version),
                        libraryPath = nativeDir,
                    )
                    append("--- 启动前检查 ---")
                    append(Preflight.render(checks))
                    append("nativeLibraryDir 里 ${File(context.applicationInfo.nativeLibraryDir).listFiles()?.size ?: 0} 个文件")
                    append("assets 里 merged-modules：" + runCatching {
                        context.assets.open("lwjgl/3.3.3/lwjgl-3.3.3-merged-modules.jar").use { "在，${it.available()} 字节" }
                    }.getOrElse { "打不开：" + (it.message ?: "") })
                },
            )
            PclField("窗口 Surface", surfaceState)
            AndroidView(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                factory = { ctx ->
                    SurfaceView(ctx).also { view ->
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                surface = h.surface
                                surfaceState = "已创建 ${h.surfaceFrame.width()}x${h.surfaceFrame.height()}"
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                surface = h.surface
                                surfaceState = "已创建 ${w}x${hh}"
                            }
                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                surface = null
                                surfaceState = "已销毁"
                            }
                        })
                    }
                },
            )
            Text(
                text = log.ifEmpty { "日志会出现在这里" },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
