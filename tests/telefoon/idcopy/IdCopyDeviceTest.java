package nl.rene.tools.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

/** Afzonderlijke CI-instrumentatie; deze klasse zit nooit in de productie-app. */
public final class IdCopyDeviceTest extends Instrumentation {
    private static final String SECRET = "IDCOPY_PRIVATE_MARKER_735192";
    private Class<?> type;
    private Context target;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try { waitForIdleSync(); target = getTargetContext(); type = target.getClassLoader().loadClass("nl.rene.tools.IdCopy"); runChecks(); result.putString("idcopy", "ok"); finish(Activity.RESULT_OK, result); }
        catch (Throwable e) { result.putString("idcopy", "failed: " + e); finish(Activity.RESULT_CANCELED, result); }
    }

    private Object store() throws Exception { java.lang.reflect.Constructor<?> c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
    private Object call(Object instance, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = type.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(instance, args); }
        catch (InvocationTargetException e) { Throwable t=e.getCause(); if(t instanceof Exception)throw (Exception)t;throw new Exception(t); }
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private JSONObject add(Object s, File file) throws Exception {
        Uri uri = new Uri.Builder().scheme("content").authority(target.getPackageName()+".pdf").appendPath(file.getName()).build();
        return (JSONObject)call(s,"add",new Class<?>[]{Context.class,java.util.List.class},target,Collections.singletonList(uri));
    }
    private File save(Object s, String revision, JSONObject plan) throws Exception { return (File)call(s,"save",new Class<?>[]{Context.class,String.class,String.class},target,revision,plan.toString()); }
    private JSONObject plan(int pages) throws Exception {
        JSONArray a = new JSONArray();
        for(int i=0;i<pages;i++)a.put(new JSONObject().put("turn",i%2).put("checked",true).put("rects",new JSONArray().put(new JSONArray().put(.08).put(.08).put(.35).put(.2))));
        return new JSONObject().put("pages",a).put("watermark","COPY FOR TEST RECIPIENT\nTest purpose only\n5 November 2026");
    }
    private void runChecks() throws Exception {
        File output = new File(target.getCacheDir(),"pdf-out"); check(output.isDirectory()||output.mkdirs(),"output directory");
        File pdf = new File(output,"idcopy-device-pdf.pdf"), photo = new File(output,"idcopy-device-photo.pdf");
        PdfDocument doc = new PdfDocument();
        try {
            for(int i=0;i<2;i++) {
                PdfDocument.Page p = doc.startPage(new PdfDocument.PageInfo.Builder(600,400,i+1).create()); Canvas c=p.getCanvas();c.drawColor(Color.WHITE);
                Paint paint = new Paint();paint.setTextSize(16);paint.setColor(Color.BLUE);c.drawText(SECRET,60,60,paint);doc.finishPage(p);
            }
            try(FileOutputStream out=new FileOutputStream(pdf)){doc.writeTo(out);}
        } finally {doc.close();}
        // Een commentaar in de bron mag evenmin in de nieuwe PDF terechtkomen.
        try(FileOutputStream out=new FileOutputStream(pdf,true)){out.write(("\n%"+SECRET+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        Bitmap image=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        Canvas canvas=new Canvas(image);Paint text=new Paint();text.setColor(Color.BLUE);text.setTextSize(16);canvas.drawText(SECRET,60,60,text);
        try(FileOutputStream out=new FileOutputStream(photo)){check(image.compress(Bitmap.CompressFormat.JPEG,95,out),"image encode");}finally{image.recycle();}
        ExifInterface exif=new ExifInterface(photo.getPath());exif.setAttribute(ExifInterface.TAG_USER_COMMENT,SECRET);exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
        File first=null,second=null;
        Object a=store(),b=store(),closed=store();
        try {
            call(closed,"clear",new Class<?>[]{});
            boolean refused=false;try{add(closed,pdf);}catch(Exception expected){refused=true;}check(refused,"late import after clear refused");
            Object failed=store();JSONObject failState=add(failed,pdf);
            java.lang.reflect.Field field=type.getDeclaredField("dir");field.setAccessible(true);File rawDir=(File)field.get(failed);
            check(rawDir.setWritable(false,true),"remove write permission for cleanup test");
            boolean cleanupFailed=false;
            try { save(failed,failState.getString("revision"),plan(2)); }
            catch(Exception expected) {cleanupFailed=true;}
            finally {check(rawDir.setWritable(true,true),"restore write permission");}
            check(cleanupFailed,"export refuses success when source deletion fails");
            check((Boolean)call(failed,"clear",new Class<?>[]{}),"failed deletion retried");check(!rawDir.exists(),"retry removes source");
            JSONObject state=add(a,pdf);check(state.getInt("pages")==2,"both PDF pages imported");
            JSONObject invalid=plan(2);invalid.getJSONArray("pages").getJSONObject(1).put("checked",false);
            refused=false;try{save(a,state.getString("revision"),invalid);}catch(Exception expected){refused=true;}check(refused,"unchecked page refused");
            JSONObject imageState=add(b,photo);
            JSONObject before=(JSONObject)call(b,"preview",new Class<?>[]{String.class,int.class,int.class},imageState.getString("revision"),0,0);
            check(before.getInt("h")>before.getInt("w"),"EXIF orientation respected");
            first=save(a,state.getString("revision"),plan(2));second=save(b,imageState.getString("revision"),plan(1));
            check(!first.equals(second),"separate source stores have unique outputs");
            inspect(first,2);inspect(second,1);checkPrivacyGeneration();
            refused=false;try{call(a,"preview",new Class<?>[]{String.class,int.class,int.class},state.getString("revision"),0,0);}catch(Exception expected){refused=true;}check(refused,"source unavailable after export");
            for(File f:target.getCacheDir().listFiles())if(f.getName().startsWith("id-copy-"))check(!f.exists(),"raw cache removed");
        } finally {
            call(a,"clear",new Class<?>[]{});call(b,"clear",new Class<?>[]{});call(closed,"clear",new Class<?>[]{});
            pdf.delete();photo.delete();if(first!=null)first.delete();if(second!=null)second.delete();
        }
    }
    private void checkPrivacyGeneration() throws Exception {
        final Activity activity=startActivitySync(new android.content.Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        final Throwable[] error=new Throwable[1];
        runOnMainSync(()->{
            try {
                Class<?> main=activity.getClass();java.lang.reflect.Field session=main.getDeclaredField("idSession"),screen=main.getDeclaredField("idScreen"),counter=main.getDeclaredField("idSecurityGeneration");
                session.setAccessible(true);screen.setAccessible(true);counter.setAccessible(true);
                session.set(activity,"");screen.setBoolean(activity,true);((java.util.concurrent.atomic.AtomicLong)counter.get(activity)).set(3);
                activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                Class<?> callback=target.getClassLoader().loadClass("nl.rene.tools.MainActivity$IdHidden");
                java.lang.reflect.Constructor<?> constructor=callback.getDeclaredConstructor(main,long.class);constructor.setAccessible(true);
                Method complete=callback.getDeclaredMethod("onComplete",long.class);complete.setAccessible(true);
                complete.invoke(constructor.newInstance(activity,1L),0L);
                check(screen.getBoolean(activity)&&(activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"old frame callback cannot release newer privacy frame");
                complete.invoke(constructor.newInstance(activity,3L),0L);check(!screen.getBoolean(activity),"matching generation can finish privacy frame");
            } catch(Throwable e){error[0]=e;}
            finally{activity.finish();}
        });
        if(error[0]!=null)throw new Exception(error[0]);
    }
    private static void inspect(File file,int count) throws Exception {
        byte[] bytes=new byte[(int)file.length()];try(FileInputStream in=new FileInputStream(file)){int off=0,n;while(off<bytes.length&&(n=in.read(bytes,off,bytes.length-off))>0)off+=n;}
        String raw=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
        check(!raw.contains(SECRET),"source marker and EXIF excluded");check(!raw.contains("/Font")&&!raw.contains("/AcroForm")&&!raw.contains("/Annots"),"no original text or form objects");
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            check(renderer.getPageCount()==count,"page count preserved");
            for(int i=0;i<count;i++)try(PdfRenderer.Page page=renderer.openPage(i)){
                int w=600,h=Math.round(600f*page.getHeight()/page.getWidth());Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
                try{page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);int color=b.getPixel((int)(w*.2),(int)(h*.17));check(Color.red(color)<20&&Color.green(color)<20&&Color.blue(color)<20,"redacted pixels opaque on page "+i);}
                finally{b.recycle();}
            }
        }
    }
}
