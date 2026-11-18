package com.pocketlink.app.qa
import android.app.*
import android.os.Bundle
import android.content.Intent
import android.graphics.drawable.Icon
import com.pocketlink.app.*
class IconRunner:Instrumentation(){
 override fun onCreate(arguments:Bundle?){start()}
 override fun onStart(){
  try{
   val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   runOnMainSync{Notifier.createChannels(targetContext);Notifier.refreshLink(targetContext)}
   Thread.sleep(300)
   val notice=targetContext.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull{it.id==Notifier.ID_LINK}?.notification ?: throw AssertionError("Notification absent")
   if(notice.smallIcon.type!=Icon.TYPE_BITMAP)throw AssertionError("Small icon still refers to a resource")
   if(notice.smallIcon.loadDrawable(targetContext)==null)throw AssertionError("Small icon cannot render")
   finish(Activity.RESULT_OK,Bundle().apply{putString("stream","PASS posted Android notification contains a drawable bitmap P icon, not a cached resource ID\n")})
  }catch(error:Throwable){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","FAIL "+error.toString()+"\n")})}
 }
}
