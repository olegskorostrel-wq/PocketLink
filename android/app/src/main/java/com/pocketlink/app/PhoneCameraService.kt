package com.pocketlink.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import java.io.ByteArrayOutputStream

/** Camera frames are sent only after a foreground user action and Android camera permission. */
class PhoneCameraService : Service() {
    companion object {
        @Volatile var active = false
        fun start(ctx:Context,facing:String) { val intent=Intent(ctx,PhoneCameraService::class.java).putExtra("facing",facing); ctx.startForegroundService(intent) }
        fun stop(ctx:Context) { ctx.stopService(Intent(ctx,PhoneCameraService::class.java)) }
    }
    private var worker:HandlerThread?=null
    private var camera:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var reader:ImageReader?=null
    private var lastFrame=0L
    private var generation=0
    private val observer=object:Hub.Observer { override fun onHub(ev:String,data:Any?) {
        if(ev=="state" && Hub.state!=Hub.State.ONLINE) stopSelf()
        if(ev=="json" && data is org.json.JSONObject && data.optString("t")=="webcam.state" && !data.optBoolean("ready")) stopSelf()
    } }
    override fun onBind(intent:Intent?) = null
    override fun onCreate() { super.onCreate(); Hub.observe(observer) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") { stopSelf(); Hub.send("camera.stop"); return START_NOT_STICKY }
        if(intent==null || Hub.state!=Hub.State.ONLINE || checkSelfPermission(Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("camera","Камера PocketLink",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,3,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,4,Intent(this,PhoneCameraService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val notice=Notification.Builder(this,"camera").setSmallIcon(Notifier.smallIcon(this)).setLargeIcon(Notifier.brandIcon(this)).setContentTitle("Камера передаётся на ПК").setContentText("PocketLink использует камеру").setContentIntent(open).addAction(Notification.Action.Builder(0,"Остановить",stop).build()).setOngoing(true).build()
        try { if(Build.VERSION.SDK_INT>=29) startForeground(3,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(3,notice) }
        catch(error:Exception){ fail("Android запретил запуск камеры. Откройте PocketLink на телефоне и нажмите запуск ещё раз.");return START_NOT_STICKY }
        closeCamera();active=true;val current=++generation
        worker=HandlerThread("PocketLink-camera").apply { start() }; val handler=Handler(worker!!.looper)
        val cameras=getSystemService(CameraManager::class.java)
        try {
            val wanted=if(intent.getStringExtra("facing")=="front")CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id=cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==wanted } ?: cameras.cameraIdList.firstOrNull() ?: throw IllegalStateException("Камера не найдена")
            val characteristics=cameras.getCameraCharacteristics(id)
            val preset=when(Prefs.cameraQuality){"economy"->intArrayOf(640,480,10,65);"quality"->intArrayOf(1920,1080,30,85);else->intArrayOf(1280,720,15,75)}
            val sizes=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width<=preset[0] && it.height<=preset[1] } ?: emptyList()
            val size=sizes.minByOrNull { kotlin.math.abs(it.width*it.height-preset[0]*preset[1]) } ?: android.util.Size(640,480)
            val orientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val front=characteristics.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT
            reader=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,2).apply { setOnImageAvailableListener({ source ->
                val image=runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrame<1000/preset[2] || current!=generation || Hub.state!=Hub.State.ONLINE || Hub.conn?.cameraBusy!=false)return@use
                    lastFrame=now
                    runCatching {
                        val bytes=nv21(image);val output=ByteArrayOutputStream()
                        val yuv=YuvImage(bytes,ImageFormat.NV21,image.width,image.height,null)
                        var compression=preset[3]
                        do { output.reset(); yuv.compressToJpeg(Rect(0,0,image.width,image.height),compression,output); compression-=15 } while(output.size()>850000 && compression>=40)
                        val rotation=(getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation)*90
                        val upright=if(front)(orientation+rotation)%360 else (orientation-rotation+360)%360
                        Hub.conn?.sendCamera(output.toByteArray(),upright,front)
                    }
                }
            },handler) }
            cameras.openCamera(id,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice){
                    if(current!=generation){device.close();return};camera=device
                    device.createCaptureSession(listOf(reader!!.surface),object:CameraCaptureSession.StateCallback(){
                        override fun onConfigured(value:CameraCaptureSession){ if(current!=generation){value.close();return};session=value
                            try{val capture=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{addTarget(reader!!.surface);set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                val ranges=characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.filter{it.upper>=preset[2]}
                                ranges?.minByOrNull{it.upper-preset[2]+kotlin.math.abs(it.lower-preset[2])}?.let{set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,it)}
                            };value.setRepeatingRequest(capture.build(),null,handler); Hub.send("camera.state","running" to true,"message" to "Камера телефона передаётся через PocketLink")}
                            catch(error:Exception){fail("Не удалось запустить передачу камеры: "+error.javaClass.simpleName)}
                        }
                        override fun onConfigureFailed(value:CameraCaptureSession){ if(current==generation)fail("Камера не поддерживает выбранный режим. Попробуйте другую камеру.") }
                    },handler)
                }
                override fun onDisconnected(device:CameraDevice){device.close();if(current==generation)fail("Камера отключилась")}
                override fun onError(device:CameraDevice,error:Int){device.close();if(current==generation)fail(if(error==ERROR_CAMERA_IN_USE || error==ERROR_MAX_CAMERAS_IN_USE)"Камера занята другим приложением. Закройте его и повторите." else "Android сообщил об ошибке камеры: $error")}
            },handler)
        } catch(error:Exception){ fail("Не удалось открыть камеру: "+(error.message ?: error.javaClass.simpleName)) }
        return START_NOT_STICKY
    }
    private fun fail(message:String){Hub.send("camera.state","running" to false,"message" to message);Hub.toast(message);stopSelf()}
    private fun closeCamera(){generation++;runCatching{session?.close()};runCatching{camera?.close()};runCatching{reader?.close()};session=null;camera=null;reader=null;worker?.quitSafely();worker=null}
    override fun onDestroy(){active=false;closeCamera();Hub.unobserve(observer);Hub.send("camera.state","running" to false,"message" to "Камера остановлена");super.onDestroy()}
    private fun nv21(image:Image):ByteArray {
        val w=image.width;val h=image.height;val result=ByteArray(w*h*3/2)
        val y=image.planes[0];val yBuffer=y.buffer.duplicate();val origin=yBuffer.position()
        for(row in 0 until h){ if(y.pixelStride==1){yBuffer.position(origin+row*y.rowStride);yBuffer.get(result,row*w,w)}else for(col in 0 until w)result[row*w+col]=yBuffer.get(origin+row*y.rowStride+col*y.pixelStride) }
        for(row in 0 until h/2)for(col in 0 until w/2){val u=image.planes[1];val v=image.planes[2];val offset=w*h+row*w+col*2;result[offset]=v.buffer.get(row*v.rowStride+col*v.pixelStride);result[offset+1]=u.buffer.get(row*u.rowStride+col*u.pixelStride)}
        return result
    }
}
