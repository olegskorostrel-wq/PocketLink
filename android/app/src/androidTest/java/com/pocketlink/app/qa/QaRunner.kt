package com.pocketlink.app.qa

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.pocketlink.app.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs only in the dedicated QA emulator against an isolated host. Never bundled in the app APK. */
class QaRunner : Instrumentation() {
    private var options = Bundle()
    private lateinit var act: MainActivity
    private val report = mutableListOf<String>()
    private val messages = LinkedBlockingQueue<JSONObject>()
    private var frames = 0
    private lateinit var folder: File
    override fun onCreate(arguments: Bundle?) { options = arguments ?: Bundle(); start() }
    private fun ui(action: () -> Unit) = runOnMainSync { action() }
    private fun check(ok: Boolean, what: String) { if (!ok) throw AssertionError(what) }
    private fun test(name: String, action: () -> Unit) {
        try { action(); report += "PASS $name" }
        catch (error: Throwable) { report += "FAIL $name: ${error.javaClass.simpleName}: ${error.message}" }
        sendStatus(0, Bundle().apply { putString("stream", report.last() + "\n") })
        File(folder,"report.txt").writeText(report.joinToString("\n"))
    }
    private fun waitFor(label: String, seconds: Int = 15, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (!predicate()) { if (System.nanoTime() > deadline) throw AssertionError("Timeout: $label"); Thread.sleep(100) }
    }
    private fun request(type: String, reply: String): JSONObject {
        messages.clear(); ui { Hub.send(type) }
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        val received = mutableSetOf<String>()
        while(System.nanoTime()<end) { val msg=messages.poll(1,TimeUnit.SECONDS) ?: continue; received.add(msg.optString("t")); if(msg.optString("t")==reply) return msg }
        throw AssertionError("No response to $type; received types: $received")
    }
    private fun shot(name: String) { uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() } }
    private fun textViews(view: View): List<TextView> = (if(view is TextView) listOf(view) else emptyList()) + (if(view is ViewGroup) (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) } else emptyList())
    override fun onStart() {
        folder=File(targetContext.getExternalFilesDir(null),"qa").apply { mkdirs() }
        try {
            act=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val observer=object:Hub.Observer { override fun onHub(ev:String,data:Any?) { if(ev=="json" && data is JSONObject) messages.offer(data); if(ev=="screen") frames++ } }
            ui { Hub.observe(observer); Prefs.autoConnect=false; Prefs.clipAuto=false }
            test("offline main navigation") {
                for(index in 0..4) { ui { act.javaClass.getDeclaredMethod("select",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(act,index) }; waitForIdleSync(); shot("offline-tab-$index") }
            }
            test("TLS pairing with isolated Windows host") {
                val pin=options.getString("pairing") ?: throw AssertionError("Missing test pairing")
                ui { Hub.connect("127.0.0.1",pin) }; waitFor("online",25) { Hub.state==Hub.State.ONLINE }; waitFor("paired key saved") { Prefs.lastPc()?.key!=null }
            }
            val pages=listOf<Pair<String,(MainActivity)->Page>>("Home" to { HomePage(it) },"Remote" to { RemotePage(it) },"PC" to { ToolsPage(it) },"Screen" to { ScreenPage(it) },"Settings" to { DevicePage(it) },"Files" to { FilesPage(it) },"YouTube" to { YouTubePage(it) },"Mixer" to { MixerPage(it) },"Windows" to { DesktopPage(it) },"Webcam" to { WebcamPage(it) },"Bluetooth" to { BluetoothPage(it) },"Microphone" to { MicrophonePage(it) },"Shortcuts" to { ShortcutCatalogPage(it,{ emptyList() },{}) },"Updates" to { UpdatesPage(it) })
            for((name,factory)in pages) test("page $name renders online") {
                lateinit var page:Page
                ui { page=factory(act); act.openUtility(page) }; waitForIdleSync(); Thread.sleep(500)
                check(page.root.width>0 && page.root.height>0,"Page measured")
                if(name=="Microphone") check(Hub.state==Hub.State.ONLINE && Hub.conn!=null,"Audio enumeration preserves control connection")
                shot("page-${name.lowercase()}")
            }
            test("desktop snapshot responds") { val r=request("desktop.get","desktop.state"); check(!r.has("error"),r.optString("error")) }
            test("installed applications respond") { request("apps.get","apps.list") }
            test("process list responds") { request("proc.get","proc.list") }
            test("audio mixer responds") { request("mixer.get","mixer.list") }
            test("microphone devices enumerate without disconnect") { val r=request("microphone.devices","microphone.devices"); check(!r.has("message"),r.optString("message")); check(Hub.state==Hub.State.ONLINE,"Connection stays online") }
            test("file roots respond") { val r=request("fs.list","fs.list"); check(!r.has("error"),r.optString("error")) }
            test("monitor list responds") { request("screen.monitors","screen.monitors") }
            test("function directory search and navigation") {
                lateinit var page:FunctionDirectoryPage
                ui { page=FunctionDirectoryPage(act); act.openUtility(page) }; waitForIdleSync()
                ui { textViews(page.root).filterIsInstance<android.widget.EditText>().first().setText("выключить") }
                waitForIdleSync()
                check(textViews(page.root).any { it.text.toString()=="Питание компьютера" },"Power alias finds function")
                check(textViews(page.root).none { it.text.toString()=="YouTube — поиск и пульт" },"Unmatched entries hidden")
                ui { textViews(page.root).first { it.text.toString()=="Питание компьютера" }.parent.let { (it as View).parent as View }.performClick() }
                waitForIdleSync()
                val opened=act.javaClass.getDeclaredField("utility").apply { isAccessible=true }.get(act)
                check(opened is ToolsPage,"Function row actually opens PC tools")
                check(ToolsPage::class.java.getDeclaredField("mode").apply { isAccessible=true }.getInt(opened)==3,"Function row opens power section")
                shot("function-search")
            }
            test("settings search opens expanded destination") {
                lateinit var page:DevicePage
                ui { page=DevicePage(act,"Разрешения Android"); act.openUtility(page) }; waitForIdleSync()
                waitFor("settings destination scrolls") { (page.root as android.widget.ScrollView).scrollY > 0 }
                val heading=textViews(page.root).first { it.text.toString()=="Доступ к уведомлениям" }
                check(heading.isShown,"Permissions section is expanded")
            }
            test("appearance search opens exact block") {
                lateinit var page:DevicePage
                ui { page=DevicePage(act,"Оформление"); act.openUtility(page) }; waitForIdleSync()
                waitFor("appearance destination applied") { !DevicePage::class.java.getDeclaredField("destinationPending").apply { isAccessible=true }.getBoolean(page) }
                check(textViews(page.root).any { it.text.toString()=="Цвет оформления" && it.getGlobalVisibleRect(android.graphics.Rect()) },"Appearance controls inside viewport")
            }
            if(options.getString("camera")=="true") test("phone Camera2 capture and explicit stop") {
                ui { Prefs.cameraQuality=options.getString("quality") ?: "balanced" }
                lateinit var page:WebcamPage
                ui { page=WebcamPage(act); act.openUtility(page) }; waitForIdleSync()
                ui { textViews(page.root).first { it.text.toString()=="Использовать как веб-камеру" }.performClick() }
                waitFor("camera service starts",15) { PhoneCameraService.active }
                Thread.sleep(3500); check(Hub.state==Hub.State.ONLINE,"Capture keeps encrypted connection")
                if(options.getString("cameraHold")=="true") { sendStatus(0,Bundle().apply { putString("stream","CAMERA_READY_FOR_WINDOWS_READER\n") });Thread.sleep(25000) }
                shot("camera-capture")
                ui { textViews(page.root).first { it.text.toString()=="Остановить веб-камеру" }.performClick() }
                waitFor("camera service stops",10) { !PhoneCameraService.active }
            }
            test("screen stream frames and landscape") {
                lateinit var page:ScreenPage
                ui { page=ScreenPage(act); act.openUtility(page) }; waitForIdleSync()
                ui { textViews(page.root).first { it.text.toString()=="Старт" }.performClick() }
                waitFor("screen frames",20) { frames>=2 }
                ui { act.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                Thread.sleep(1500); check(Hub.state==Hub.State.ONLINE,"Connection survives rotation"); check(page.root.width>page.root.height,"Landscape layout"); shot("screen-landscape")
                ui { page.onHide(); act.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }; Thread.sleep(1200)
            }
            test("authenticated reconnect") {
                ui { Hub.disconnect() }; waitFor("offline") { Hub.state==Hub.State.OFF }; ui { Hub.connect("127.0.0.1",null) }; waitFor("reconnected",25) { Hub.state==Hub.State.ONLINE }
            }
            test("durable photo queue survives source loss and binds to a PC") {
                val directory="qa-outbox-"+java.util.UUID.randomUUID()
                val store=UploadOutbox(act,directory)
                val data=ByteArray(160000){(it%251).toByte()}
                val fingerprint=Prefs.lastPc()!!.fingerprint
                val item=store.enqueue("Фото с телефона.jpg",fingerprint){data.inputStream()}
                data.fill(0)
                val restored=UploadOutbox(act,directory).load().single()
                check(restored.id==item.id && restored.name=="Фото с телефона.jpg" && restored.fingerprint==fingerprint,"Queue metadata survives recreation")
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(restored.file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                check(hash==restored.hash,"Private copy keeps the original photo bytes")
                store.remove(item.id); check(store.load().isEmpty(),"Cancel removes durable photo")
            }
            test("automatic reconnect after abrupt socket loss") {
                repeat(3) {
                    val previous=Hub.conn!!
                    val socket=PcConnection::class.java.getDeclaredField("socket").apply{isAccessible=true}.get(previous) as java.net.Socket
                    val start=android.os.SystemClock.elapsedRealtime(); socket.close()
                    waitFor("automatic key reconnect",10){Hub.state==Hub.State.ONLINE && Hub.conn!==previous}
                    check(android.os.SystemClock.elapsedRealtime()-start<8000,"Fast reconnect without manual pairing")
                }
            }
            if(options.getString("silence")=="true") test("silent TCP connection recovers without waiting 45 seconds") {
                val previous=Hub.conn!!
                request("qa.silence","qa.silence.ready")
                val started=android.os.SystemClock.elapsedRealtime()
                waitFor("heartbeat reconnect",22){Hub.state==Hub.State.ONLINE && Hub.conn!==previous}
                check(android.os.SystemClock.elapsedRealtime()-started<20000,"Watchdog bounds silent connection")
                request("desktop.get","desktop.state")
            }
            test("photo queued offline sends automatically and retains its name") {
                ui { Hub.disconnect() }; waitFor("offline before share"){Hub.state==Hub.State.OFF}
                val photo=File(act.cacheDir,"qa-photo-source.bin").apply{writeBytes(ByteArray(900000){(it%239).toByte()})}
                ui { check(Hub.sendUri(android.net.Uri.fromFile(photo),"Фото QA очередь.jpg"),"Share accepted offline") }
                waitFor("photo automatically sent",35){Hub.transfers().any{it.name=="Фото QA очередь.jpg" && it.state=="done"}}
                photo.delete()
            }
            test("photo upload resumes after an interrupted data stream") {
                val uploadName="QA interrupted "+java.util.UUID.randomUUID()+".jpg"
                val photo=File(act.cacheDir,"qa-interrupted.bin").apply{writeBytes(ByteArray(8*1024*1024){(it%251).toByte()})}
                var interrupted=false
                val breaker=object:Hub.Observer { override fun onHub(ev:String,data:Any?) {
                    if(ev=="transfer" && data is Transfer && data.name==uploadName && data.done>0 && data.done<data.size && !interrupted) {
                        interrupted=true
                        Hub.conn?.let { connection -> Hub.work.execute { runCatching { (PcConnection::class.java.getDeclaredField("socket").apply{isAccessible=true}.get(connection) as java.net.Socket).close() } } }
                    }
                } }
                ui { Hub.observe(breaker); Hub.sendUri(android.net.Uri.fromFile(photo),uploadName) }
                try {
                    waitFor("interrupted photo completes",60){Hub.transfers().any{it.name==uploadName && it.state=="done"}}
                    check(interrupted,"Actual TCP stream interrupted during upload")
                } finally { ui { Hub.unobserve(breaker) }; photo.delete() }
            }
            ui { Hub.unobserve(observer); Hub.disconnect() }
        } catch(error:Throwable) { report+="FATAL ${error.javaClass.simpleName}: ${error.message}" }
        File(folder,"report.txt").writeText(report.joinToString("\n"))
        finish(if(report.any { it.startsWith("FAIL") || it.startsWith("FATAL") }) Activity.RESULT_CANCELED else Activity.RESULT_OK,Bundle().apply { putString("stream",report.joinToString("\n")+"\n") })
    }
}
