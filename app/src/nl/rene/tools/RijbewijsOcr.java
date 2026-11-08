package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.AtomicFile;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.ResultIterator;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Lokaal gebundelde tekstherkenning. Alleen het openbare taalmodel komt op schijf, nooit foto of herkende tekst. */
final class RijbewijsOcr {
    private RijbewijsOcr() { }
    static final String MODEL_HASH="7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2";
    private static boolean modelOk(File file) {
        try(InputStream in=new FileInputStream(file)) {
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)md.update(b,0,n);
            StringBuilder hex=new StringBuilder();for(byte v:md.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return MODEL_HASH.equals(hex.toString());
        }catch(Exception e){return false;}
    }
    private static File model(Context context)throws Exception {
        File root=new File(context.getNoBackupFilesDir(),"document-ocr"),dir=new File(root,"tessdata"),file=new File(dir,"eng.traineddata");
        if(!modelOk(file)) {
            if(!dir.isDirectory()&&!dir.mkdirs())throw new Exception("ocr-model");
            AtomicFile target=new AtomicFile(file);FileOutputStream out=null;
            try(InputStream in=context.getAssets().open("ocr/eng.traineddata")) {
                out=target.startWrite();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);target.finishWrite(out);out=null;
            }catch(Throwable e){if(out!=null)target.failWrite(out);throw new Exception("ocr-model");}
            if(!modelOk(file))throw new Exception("ocr-model");
        }
        return root;
    }
    static JSONObject read(Context context,byte[] bytes,String today) { return read(context,bytes,today,()->true); }
    static synchronized JSONObject read(Context context,byte[] bytes,String today,BooleanSupplier active) {
        final long deadline=android.os.SystemClock.elapsedRealtime()+25000;
        BooleanSupplier running=()->active.getAsBoolean()&&android.os.SystemClock.elapsedRealtime()<deadline;
        TessBaseAPI api=null;Bitmap image=null;TessBaseAPI[] current=new TessBaseAPI[1];
        try {
            if(!running.getAsBoolean())return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(options.outWidth<1||options.outHeight<1||(long)options.outWidth*options.outHeight>80_000_000)return null;
            options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2600)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)return null;
            Matrix orientation=new Matrix();
            try{switch(new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)){
                case 2:orientation.setScale(-1,1);break;case 3:orientation.setRotate(180);break;case 4:orientation.setScale(1,-1);break;
                case 5:orientation.setRotate(90);orientation.postScale(-1,1);break;case 6:orientation.setRotate(90);break;
                case 7:orientation.setRotate(270);orientation.postScale(-1,1);break;case 8:orientation.setRotate(270);break;default:break;
            }}catch(Exception ignored){ }
            if(!orientation.isIdentity()){Bitmap turned=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),orientation,true);if(turned!=image){image.recycle();image=turned;}}
            File root=model(context);if(!running.getAsBoolean())return null;
            api=new TessBaseAPI(progress->{if(!running.getAsBoolean()&&current[0]!=null)current[0].stop();});current[0]=api;
            Map<String,String> config=new HashMap<>();config.put("debug_file","/dev/null");config.put("load_system_dawg","0");config.put("load_freq_dawg","0");config.put("load_number_dawg","0");
            if(!api.init(root.getAbsolutePath(),"eng",TessBaseAPI.OEM_LSTM_ONLY,config))return null;
            api.setDebug(false);api.setVariable("tessedit_write_images","0");api.setVariable("user_defined_dpi","300");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            JSONObject best=null;
            for(int turn=0;turn<4&&running.getAsBoolean();turn++) {
                Bitmap page=image;
                if(turn>0){Matrix rotation=new Matrix();rotation.setRotate(turn*90);page=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),rotation,true);}
                try {
                    api.clear();api.setImage(page);String text=api.getUTF8Text();if(!running.getAsBoolean())return null;if(text==null||text.length()>24000)continue;
                    List<Rijbewijs.Line> lines=new ArrayList<>();ResultIterator iterator=api.getResultIterator();if(iterator==null)continue;
                    try{iterator.begin();do{
                        String line=iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);int[] b=iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if(line!=null&&b!=null&&b.length==4)lines.add(new Rijbewijs.Line(line.trim(),iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE),b[0],b[1],b[2],b[3]));
                    }while(lines.size()<=200&&iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));}finally{iterator.delete();}
                    JSONObject found=Rijbewijs.read(lines,today);
                    if(found!=null&&(best==null||found.optInt("gelezen")>best.optInt("gelezen")))best=found;
                    if(best!=null&&best.optInt("gelezen")>=5&&best.getJSONObject("document").has("document")&&best.getJSONObject("persoon").has("geboortedatum"))break;
                }finally{if(page!=image)page.recycle();}
            }
            return active.getAsBoolean()?best:null;
        }catch(Throwable ignored){return null;}
        finally{current[0]=null;if(api!=null)api.recycle();if(image!=null)image.recycle();}
    }
}
