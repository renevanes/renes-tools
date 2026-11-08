package nl.rene.tools;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Alleen een bewust gestart boek mag met scherm uit verdergaan; melding heeft pauze en stoppen. */
public class ReadAloudService extends Service {
    static final String CHANNEL="voorlezen";static final int NOTIFICATION=4943;
    private String request="";
    static void begin(Context c,String request){try{Intent i=new Intent(c,ReadAloudService.class).putExtra("request",request);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}catch(Throwable ignored){ReadAloud.stop(null,request);}}
    static void changed(Context c,String request){ReadAloud.Job j=ReadAloud.current;if(j!=null&&j.background&&j.request.equals(request))try{((NotificationManager)c.getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(c,j));}catch(Throwable ignored){ }}
    static void finished(Context c,String request){ReadAloud.Job j=ReadAloud.current;if(j==null||!j.background)c.stopService(new Intent(c,ReadAloudService.class));}
    static PendingIntent action(Context c,String name,String request){return PendingIntent.getService(c,name.equals("stop")?3:2,new Intent(c,ReadAloudService.class).setAction(name).putExtra("request",request),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static Notification notification(Context c,ReadAloud.Job j){
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,Taal.t(c,"Boek voorlezen"),NotificationManager.IMPORTANCE_LOW);((NotificationManager)c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);}
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        Intent open=new Intent(c,MainActivity.class).putExtra("open","ereader").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(Taal.t(c,"Boek voorlezen")).setContentText(Taal.t(c,j.paused?"Voorlezen gepauzeerd":"Lokale stem · geen internet nodig")).setOngoing(!j.paused).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(c,71,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
            .addAction(j.paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,Taal.t(c,j.paused?"Verder voorlezen":"Pauzeren"),action(c,j.paused?"resume":"pause",j.request))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,Taal.t(c,"Stoppen"),action(c,"stop",j.request));return b.build();
    }
    @Override public int onStartCommand(Intent i,int flags,int start){
        ReadAloud.Job j=ReadAloud.current;String id=i==null?"":i.getStringExtra("request");
        if(j==null||!j.background||!j.request.equals(id)){if(request.isEmpty())stopSelfResult(start);return START_NOT_STICKY;}
        String action=i.getAction();
        if("stop".equals(action)){ReadAloud.stop(null,id);stopSelf();return START_NOT_STICKY;}
        if("pause".equals(action)||"resume".equals(action)){ReadAloud.pauseNotification(id,"pause".equals(action));return START_NOT_STICKY;}
        if(!Fg.start(this,NOTIFICATION,notification(this,j),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,29)){ReadAloud.stop(null,id);stopSelf();return START_NOT_STICKY;}
        if(!id.equals(request)){request=id;ReadAloud.startService(id);}return START_NOT_STICKY;
    }
    @Override public void onDestroy(){String id=request;request="";ReadAloud.stop(null,id);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
