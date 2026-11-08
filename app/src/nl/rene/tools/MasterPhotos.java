package nl.rene.tools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Kaartzijden na expliciete bestandskeuze en hernieuwde ontgrendeling, zonder leesbare werkbestanden. */
final class MasterPhotos {
    static final int REQUEST=94,MAX_BYTES=256000,MAX_DATA=350000;
    final MainActivity activity;
    private Pending pending;
    MasterPhotos(MainActivity activity){this.activity=activity;}
    static final class Pending {
        final String id,side,request,view;final long revision,dataset;Uri uri;
        Pending(String id,long revision,String side,String request,String view){this.id=id;this.revision=revision;this.side=side;this.request=request;this.view=view;this.dataset=MasterSession.datasetEpoch();}
    }
    synchronized void pick(String token,String id,long revision,String side,String request)throws Exception {
        if(pending!=null)throw new MasterError(Taal.T("Er staat al een kaartfoto klaar; ontgrendel om die op te slaan"));
        if(!side.equals("front")&&!side.equals("back")&&!side.equals("scan"))throw new MasterError(Taal.T("Kies de voorzijde of achterzijde"));
        Pending job=new Pending(id,revision,side,request,activity.masterView);pending=job;
        activity.h.post(()->{
            synchronized(MasterPhotos.this){if(pending!=job)return;}
            if(!activity.masterForeground||!activity.masterSession.accepts(token)||!job.view.equals(activity.masterView)){clear(job);notify(job,false,Taal.T("Ontgrendel de stamgegevens opnieuw"));return;}
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{activity.startActivityForResult(intent,REQUEST);}catch(Throwable e){clear(job);notify(job,false,Taal.T("Bestandskiezer niet beschikbaar"));}
        });
    }
    synchronized void clear(Pending job){if(pending==job)pending=null;}
    synchronized void cancel(){pending=null;}
    void notify(Pending job,boolean picked,String error){try{JSONObject out=new JSONObject().put("request",job.request).put("picked",picked);if(error!=null)out.put("error",error);activity.js("onMasterPhotoPicked",out.toString());}catch(Exception ignored){ }}
    void result(int result,Intent data){
        Pending job;synchronized(this){job=pending;}if(job==null)return;
        Uri uri=result==Activity.RESULT_OK&&data!=null?data.getData():null;
        if(uri==null){clear(job);notify(job,false,null);return;}
        try{if(!PdfTools.grantedBySender(data,java.util.Collections.singletonList(uri)))throw new Exception();PdfTools.checkUri(activity,uri);
            synchronized(this){if(pending!=job)return;job.uri=uri;}notify(job,true,null);
        }catch(Throwable e){clear(job);notify(job,false,Taal.T("Deze kaartfoto kan niet worden geopend"));}
    }
    JSONObject resume(SecretKey key,MasterSession session,String token){
        Pending job;synchronized(this){job=pending;if(job==null||job.uri==null)return null;}
        JSONObject result=new JSONObject();
        try{
            if(job.dataset!=MasterSession.datasetEpoch()||!job.view.equals(activity.masterView)||!activity.masterWanted||!activity.masterForeground)throw new MasterError(Taal.T("Kies de kaartfoto opnieuw vanuit Stamgegevens"));
            session.require(token);byte[] bytes=read(activity.getContentResolver().openInputStream(job.uri));
            JSONObject found=recognize(bytes,Integer.parseInt(Regio.dayKey(activity,System.currentTimeMillis()).substring(0,4)));session.require(token);
            // Alleen herkennen (Document herkennen uit foto): de foto zelf wordt niet bewaard.
            if("scan".equals(job.side)){
                synchronized(this){if(pending!=job)throw new MasterError(Taal.T("Kaartfoto opslaan geannuleerd"));pending=null;}
                if(found==null)throw new MasterError(Taal.T("Geen strook met <<< gevonden. Fotografeer de achterkant van de ID-kaart of de fotopagina van het paspoort recht van boven, scherp en zonder schittering."));
                return result.put("scan",true).put("person",job.id).put("herkend",found);
            }
            String image=normalize(bytes);session.require(token);
            result=MasterStore.photo(activity,key,session,token,job.id,job.revision,job.side,image,false,()->{synchronized(MasterPhotos.this){if(pending!=job||job.dataset!=MasterSession.datasetEpoch()||!job.view.equals(activity.masterView)||!activity.masterWanted||!activity.masterForeground)throw new MasterError(Taal.T("Kaartfoto opslaan geannuleerd"));pending=null;}});result.put("saved",true);if(found!=null)result.put("herkend",found);
        }catch(Throwable e){try{result.put("error",e instanceof MasterError?e.getMessage():Taal.T("De kaartfoto kan niet worden opgeslagen. Kies een duidelijke afbeelding."));}catch(Exception ignored){ }}
        finally{clear(job);}
        return result;
    }
    static byte[] read(InputStream input)throws Exception{
        if(input==null)throw new MasterError(Taal.T("Deze kaartfoto kan niet worden geopend"));
        ByteArrayOutputStream source=new ByteArrayOutputStream();
        try(InputStream in=input){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(source.size()+n>20*1024*1024)throw new MasterError(Taal.T("De kaartfoto is te groot"));source.write(buffer,0,n);}}
        return source.toByteArray();
    }
    /**
     * De strook (MRZ) lezen, op de telefoon zelf (MrzOcr, geen dienst op internet). Geeft null als er geen betrouwbare strook is;
     * een fout in het herkennen laat het bewaren van de foto nooit mislukken.
     */
    static JSONObject recognize(byte[] bytes,int year){
        Bitmap image=null;
        try{
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
            if(o.outWidth<1||o.outHeight<1||o.outWidth>16000||o.outHeight>16000)return null;
            int sample=1;while(o.outWidth/sample>3200||o.outHeight/sample>3200)sample*=2;o=new BitmapFactory.Options();o.inSampleSize=sample;
            image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(image==null)return null;
            int w=image.getWidth(),h=image.getHeight();int[] px=new int[w*h];image.getPixels(px,0,w,0,0,w,h);image.recycle();image=null;
            for(int i=0;i<px.length;i++){int c=px[i];px[i]=((c>>16&255)*30+(c>>8&255)*59+(c&255)*11)/100;}
            Mrz.Result r=MrzOcr.read(px,w,h);if(r==null||!r.usable())return null;
            return Mrz.fields(r,year);
        }catch(Throwable e){return null;}
        finally{if(image!=null)image.recycle();}
    }
    static String normalize(byte[] bytes)throws Exception {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
        if(o.outWidth<1||o.outHeight<1||o.outWidth>16000||o.outHeight>16000||(long)o.outWidth*o.outHeight>80_000_000)throw new MasterError(Taal.T("Deze kaartfoto kan niet worden geopend"));
        int sample=1;while(o.outWidth/sample>1600||o.outHeight/sample>1600)sample*=2;o=new BitmapFactory.Options();o.inSampleSize=sample;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(image==null)throw new MasterError(Taal.T("Deze kaartfoto kan niet worden geopend"));
        try{
            Matrix m=new Matrix();try{int orientation=new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;default:break;}}catch(Exception ignored){ }
            if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);if(rotated!=image){image.recycle();image=rotated;}}
            if(image.hasAlpha()){Bitmap flat=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(flat);c.drawColor(Color.WHITE);c.drawBitmap(image,0,0,null);image.recycle();image=flat;}
            for(int size=0;size<4;size++){
                for(int quality:new int[]{85,70,55,40}){ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,quality,out);if(out.size()<=MAX_BYTES)return "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);}
                Bitmap smaller=Bitmap.createScaledBitmap(image,Math.max(1,image.getWidth()*3/4),Math.max(1,image.getHeight()*3/4),true);if(smaller!=image){image.recycle();image=smaller;}
            }
            throw new MasterError(Taal.T("De kaartfoto is te groot"));
        }finally{image.recycle();}
    }
}
