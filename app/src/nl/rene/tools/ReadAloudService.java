package nl.rene.tools;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Alleen een bewust gestart boek mag met scherm uit verdergaan; melding heeft pauze en stoppen. */
public class ReadAloudService extends Service {
    static final String CHANNEL="voorlezen";static final int NOTIFICATION=4943;
    private String request="",started="";
    private android.media.session.MediaSession session;
    private static java.lang.ref.WeakReference<ReadAloudService> live=new java.lang.ref.WeakReference<>(null);
    @Override public void onCreate(){super.onCreate();synchronized(ReadAloud.LOCK){session=new android.media.session.MediaSession(this,"ToolsVoorlezen");session.setCallback(new Controls(this));session.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);live=new java.lang.ref.WeakReference<>(this);}}
    static final class Controls extends android.media.session.MediaSession.Callback {
        final java.lang.ref.WeakReference<ReadAloudService> service;Controls(ReadAloudService s){service=new java.lang.ref.WeakReference<>(s);}
        @Override public void onPlay(){ReadAloudService s=service.get();if(s!=null)ReadAloud.pauseNotification(s.request,false);}
        @Override public void onPause(){ReadAloudService s=service.get();if(s!=null)ReadAloud.pauseNotification(s.request,true);}
        @Override public void onStop(){ReadAloudService s=service.get();if(s!=null)ReadAloud.stop(null,s.request);}
    }
    static void mediaState(ReadAloud.Job j){ReadAloudService s=live.get();if(s==null||s.session==null||!s.request.equals(j.request))return;s.session.setActive(true);s.session.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,Taal.t(j.context,"Boek voorlezen")).build());s.session.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(android.media.session.PlaybackState.ACTION_PLAY|android.media.session.PlaybackState.ACTION_PAUSE|android.media.session.PlaybackState.ACTION_STOP|android.media.session.PlaybackState.ACTION_PLAY_PAUSE).setState(j.paused?android.media.session.PlaybackState.STATE_PAUSED:android.media.session.PlaybackState.STATE_PLAYING,android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN,1).build());}

    static void begin(Context c,String request){try{Intent i=new Intent(c,ReadAloudService.class).putExtra("request",request);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}catch(Throwable ignored){ReadAloud.stop(null,request);}}
    static void changed(Context c,String request){synchronized(ReadAloud.LOCK){ReadAloud.Job j=ReadAloud.current;if(j!=null&&!j.cancelled&&j.background&&j.request.equals(request))try{mediaState(j);((NotificationManager)c.getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(c,j));}catch(Throwable ignored){ }}}
    static void finished(Context c,String request){synchronized(ReadAloud.LOCK){ReadAloud.Job j=ReadAloud.current;if(j==null||!j.background)c.stopService(new Intent(c,ReadAloudService.class));}}
    static PendingIntent action(Context c,String name,String request){return PendingIntent.getService(c,name.equals("stop")?3:2,new Intent(c,ReadAloudService.class).setAction(name).setData(android.net.Uri.parse("renes-tools:voorlezen/"+request+"/"+name)).putExtra("request",request),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static Notification notification(Context c,ReadAloud.Job j){
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,Taal.t(c,"Boek voorlezen"),NotificationManager.IMPORTANCE_LOW);((NotificationManager)c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);}
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        Intent open=new Intent(c,MainActivity.class).putExtra("open","ereader").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(Taal.t(c,"Boek voorlezen")).setContentText(Taal.t(c,j.paused?"Voorlezen gepauzeerd":"Lokale stem · geen internet nodig")).setOngoing(!j.paused).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(c,71,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
            .addAction(j.paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,Taal.t(c,j.paused?"Verder voorlezen":"Pauzeren"),action(c,j.paused?"resume":"pause",j.request))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,Taal.t(c,"Stoppen"),action(c,"stop",j.request));ReadAloudService service=live.get();if(service!=null&&service.session!=null)b.setStyle(new Notification.MediaStyle().setMediaSession(service.session.getSessionToken()).setShowActionsInCompactView(0,1));return b.build();
    }
    @Override public int onStartCommand(Intent i,int flags,int start){synchronized(ReadAloud.LOCK){
        ReadAloud.Job j=ReadAloud.current;String id=i==null?"":i.getStringExtra("request");
        if(j==null||!j.background||!j.request.equals(id)){if(request.isEmpty())stopSelfResult(start);return START_NOT_STICKY;}
        String action=i.getAction();
        if("stop".equals(action)){ReadAloud.stop(null,id);stopSelf();return START_NOT_STICKY;}
        if("pause".equals(action)||"resume".equals(action)){ReadAloud.pauseNotification(id,"pause".equals(action));return START_NOT_STICKY;}
        request=id;mediaState(j);
        if(!Fg.start(this,NOTIFICATION,notification(this,j),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,29)){ReadAloud.stop(null,id);stopSelf();return START_NOT_STICKY;}
        if(!id.equals(started)){started=id;ReadAloud.startService(id);}return START_NOT_STICKY;
    }}
    @Override public void onDestroy(){synchronized(ReadAloud.LOCK){String id=request;request="";ReadAloud.stop(null,id);if(session!=null){try{session.setActive(false);session.release();}catch(Throwable ignored){ }session=null;}if(live.get()==this)live.clear();stopForeground(STOP_FOREGROUND_REMOVE);}super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
