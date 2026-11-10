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
    static JSONObject read(Context context,byte[] bytes,String today,BooleanSupplier active) { return read(context,bytes,today,active,false,null); }

    /** Hoe lang het lezen hooguit duurt (ms); ook voor de voortgangsbalk. */
    static final long TIJD=25000,TIJD_GRONDIG=70000;
    /**
     * Leest de voorkant van een rijbewijs. Eerst gewoon (vier draaiingen); ontbreekt het nummer of de einddatum, dan nog eens
     * op een zwart-wit-versie (tegen de achtergrondpatronen). Grondig (op verzoek, langzamer): ook vergroot en met een andere
     * indeling van de tekst. diag (mag null) krijgt alleen ja/nee en aantallen voor de hulp bij mislukken, nooit tekst.
     */
    static synchronized JSONObject read(Context context,byte[] bytes,String today,BooleanSupplier active,boolean thorough,JSONObject diag) {
        final long deadline=android.os.SystemClock.elapsedRealtime()+(thorough?TIJD_GRONDIG:TIJD);
        BooleanSupplier running=()->active.getAsBoolean()&&android.os.SystemClock.elapsedRealtime()<deadline;
        TessBaseAPI api=null;Bitmap image=null;TessBaseAPI[] current=new TessBaseAPI[1];
        Rijbewijs.Stand stand=new Rijbewijs.Stand();int lines=0;boolean licence=false,bottom=false;
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
            if(diag!=null)quality(image,diag);
            File root=model(context);if(!running.getAsBoolean())return null;
            api=new TessBaseAPI(progress->{if(!running.getAsBoolean()&&current[0]!=null)current[0].stop();});current[0]=api;
            Map<String,String> config=new HashMap<>();config.put("debug_file","/dev/null");config.put("load_system_dawg","0");config.put("load_freq_dawg","0");config.put("load_number_dawg","0");
            if(!api.init(root.getAbsolutePath(),"eng",TessBaseAPI.OEM_LSTM_ONLY,config)){if(diag!=null)diag.put("ocr",false);return null;}
            if(diag!=null)diag.put("ocr",true);
            api.setDebug(false);api.setVariable("tessedit_write_images","0");api.setVariable("user_defined_dpi","300");
            JSONObject best=null;int bestTurn=-1,bestScore=-1;
            // 1. Gewoon, in vier draaiingen
            for(int turn=0;turn<4&&running.getAsBoolean()&&!complete(best);turn++) {
                Bitmap page=turned(image,turn);
                try {
                    List<Rijbewijs.Line> found=ocr(api,page,TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);if(!running.getAsBoolean())break;if(found==null)continue;
                    JSONObject r=Rijbewijs.read(found,today,stand);lines=Math.max(lines,stand.regels);licence|=stand.rijbewijs;bottom|=stand.onderregel;
                    int score=(r==null?0:100*r.optInt("gelezen"))+(stand.rijbewijs?50:0)+stand.regels;
                    if(score>bestScore){bestScore=score;bestTurn=turn;}
                    best=Rijbewijs.merge(best,r);
                }finally{if(page!=image)page.recycle();}
            }
            // 2. Zwart-wit (drempel per buurt), in de beste draaiing; grondig: ook vergroot en als één tekstblok
            List<Bitmap> extra=new ArrayList<>();
            // Alleen als het (vermoedelijk) een rijbewijs is, of op verzoek grondig: een ID-kaart zonder strook hoeft dit niet.
            if(!full(best)&&running.getAsBoolean()&&(licence||best!=null||thorough)) {
                int[] turns=thorough&&bestScore<50?new int[]{0,1,2,3}:new int[]{Math.max(0,bestTurn)};
                for(int turn:turns) {
                    if(full(best)||!running.getAsBoolean())break;
                    Bitmap page=turned(image,turn);
                    try {
                        Bitmap bw=binary(page,2000);
                        try{best=pass(api,bw,TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,today,stand,best);}finally{bw.recycle();}
                        lines=Math.max(lines,stand.regels);licence|=stand.rijbewijs;bottom|=stand.onderregel;
                        if(thorough&&!full(best)&&running.getAsBoolean()) {
                            Bitmap big=stretched(page,3200);
                            try{
                                best=pass(api,big,TessBaseAPI.PageSegMode.PSM_AUTO,today,stand,best);lines=Math.max(lines,stand.regels);licence|=stand.rijbewijs;bottom|=stand.onderregel;
                                if(!full(best)&&running.getAsBoolean()){Bitmap bw2=binary(big,3200);try{best=pass(api,bw2,TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,today,stand,best);}finally{bw2.recycle();}lines=Math.max(lines,stand.regels);licence|=stand.rijbewijs;bottom|=stand.onderregel;}
                            }finally{big.recycle();}
                        }
                    }finally{if(page!=image)page.recycle();}
                }
            }
            if(diag!=null)diag.put("regels",lines).put("rijbewijs",licence).put("onderregel",bottom).put("grondig",thorough).put("tijd",active.getAsBoolean()&&android.os.SystemClock.elapsedRealtime()>=deadline);
            return active.getAsBoolean()?best:null;
        }catch(Throwable ignored){return null;}
        finally{current[0]=null;if(api!=null)api.recycle();if(image!=null)image.recycle();}
    }

    /**
     * Gewone tekst van een gescande pagina (documenten vergelijken), met hetzelfde lokale model. Leeg als het niet lukt of
     * als active intussen nee zegt. De pagina en de tekst blijven in het geheugen.
     */
    static synchronized String pageText(Context context,Bitmap page,BooleanSupplier active) {
        TessBaseAPI api=null;TessBaseAPI[] current=new TessBaseAPI[1];
        try {
            if(!active.getAsBoolean())return "";
            File root=model(context);
            api=new TessBaseAPI(progress->{if(!active.getAsBoolean()&&current[0]!=null)current[0].stop();});current[0]=api;
            Map<String,String> config=new HashMap<>();config.put("debug_file","/dev/null");
            if(!api.init(root.getAbsolutePath(),"eng",TessBaseAPI.OEM_LSTM_ONLY,config))return "";
            api.setDebug(false);api.setVariable("tessedit_write_images","0");api.setVariable("user_defined_dpi","300");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            Bitmap bw=binary(page,2400);
            try{api.setImage(bw);String t=api.getUTF8Text();return t==null||!active.getAsBoolean()?"":t;}finally{bw.recycle();}
        }catch(Throwable ignored){return "";}
        finally{current[0]=null;if(api!=null)api.recycle();}
    }

    /** Alles wat een rijbewijs nodig heeft gevonden (nummer zeker, einddatum en geboortedatum): dan niet verder zoeken. */
    static boolean complete(JSONObject r) {
        if(r==null)return false;JSONObject d=r.optJSONObject("document"),p=r.optJSONObject("persoon");
        org.json.JSONArray t=r.optJSONArray("twijfel");
        return d!=null&&p!=null&&d.has("document")&&d.has("geldig")&&p.has("geboortedatum")&&(t==null||!t.toString().contains("\"document\""));
    }
    /** Ook de afgiftedatum: de kleine "4a." mist vaak in de gewone lezing, de zwart-witversie leest hem dan nog wel eens. */
    static boolean full(JSONObject r) { return complete(r)&&r.optJSONObject("document").has("afgifte"); }
    private static JSONObject pass(TessBaseAPI api,Bitmap page,int mode,String today,Rijbewijs.Stand stand,JSONObject best)throws Exception {
        List<Rijbewijs.Line> found=ocr(api,page,mode);if(found==null)return best;
        return Rijbewijs.merge(best,Rijbewijs.read(found,today,stand));
    }
    private static Bitmap turned(Bitmap image,int turn) {
        if(turn==0)return image;Matrix rotation=new Matrix();rotation.setRotate(turn*90);return Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),rotation,true);
    }
    private static List<Rijbewijs.Line> ocr(TessBaseAPI api,Bitmap page,int mode) {
        api.clear();api.setPageSegMode(mode);api.setImage(page);String text=api.getUTF8Text();if(text==null||text.length()>24000)return null;
        List<Rijbewijs.Line> lines=new ArrayList<>();ResultIterator iterator=api.getResultIterator();if(iterator==null)return null;
        try{iterator.begin();do{
            String line=iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);int[] b=iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
            if(line!=null&&b!=null&&b.length==4)lines.add(new Rijbewijs.Line(line.trim(),iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE),b[0],b[1],b[2],b[3]));
        }while(lines.size()<=200&&iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));}finally{iterator.delete();}
        return lines;
    }
    private static int[] gray(Bitmap src,int max,int[] size) {
        int side=Math.max(src.getWidth(),src.getHeight());Bitmap b=src;
        if(side>max)b=Bitmap.createScaledBitmap(src,Math.max(1,src.getWidth()*max/side),Math.max(1,src.getHeight()*max/side),true);
        int w=b.getWidth(),h=b.getHeight();int[] px;
        try{px=new int[w*h];b.getPixels(px,0,w,0,0,w,h);}finally{if(b!=src)b.recycle();}
        for(int i=0;i<px.length;i++){int c=px[i];px[i]=((c>>16&255)*30+(c>>8&255)*59+(c&255)*11)/100;}
        size[0]=w;size[1]=h;return px;
    }
    /** Zwart-wit met een drempel per buurt (Bradley): tekst blijft, het lichte achtergrondpatroon valt weg. */
    static Bitmap binary(Bitmap src,int max) {
        int[] size=new int[2];int[] g=gray(src,max,size);int w=size[0],h=size[1];
        int[] sum=new int[w*h];
        for(int y=0;y<h;y++){int row=0;for(int x=0;x<w;x++){row+=g[y*w+x];sum[y*w+x]=row+(y>0?sum[(y-1)*w+x]:0);}}
        int r=Math.max(8,w/24);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int x1=Math.max(0,x-r),x2=Math.min(w-1,x+r),y1=Math.max(0,y-r),y2=Math.min(h-1,y+r),n=(x2-x1+1)*(y2-y1+1);
            // In int rekenen: de som over het hele beeld kan overlopen, maar het verschil (de som van het venster) klopt dan nog.
            int total=sum[y2*w+x2]-(x1>0?sum[y2*w+x1-1]:0)-(y1>0?sum[(y1-1)*w+x2]:0)+(x1>0&&y1>0?sum[(y1-1)*w+x1-1]:0);
            g[y*w+x]=(long)g[y*w+x]*n*100<(long)total*85?0xFF000000:0xFFFFFFFF;
        }
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);out.setPixels(g,0,w,0,0,w,h);return out;
    }
    /** Grijs, contrast opgerekt (1e en 99e percentiel) en zo nodig vergroot tot de werkmaat. */
    static Bitmap stretched(Bitmap src,int max) {
        int side=Math.max(src.getWidth(),src.getHeight());Bitmap scaled=src;
        if(side<max*2/3){float f=Math.min(1.6f,(float)max/side);scaled=Bitmap.createScaledBitmap(src,Math.round(src.getWidth()*f),Math.round(src.getHeight()*f),true);}
        int[] size=new int[2];int[] g;try{g=gray(scaled,max,size);}finally{if(scaled!=src)scaled.recycle();}int w=size[0],h=size[1];
        int[] hist=new int[256];for(int v:g)hist[v]++;int lo=0,hi=255,acc=0;
        for(int i=0;i<256;i++){acc+=hist[i];if(acc>g.length/100){lo=i;break;}}acc=0;for(int i=255;i>=0;i--){acc+=hist[i];if(acc>g.length/100){hi=i;break;}}
        int span=Math.max(1,hi-lo);for(int i=0;i<g.length;i++){int v=Math.max(0,Math.min(255,(g[i]-lo)*255/span));g[i]=0xFF000000|v<<16|v<<8|v;}
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);out.setPixels(g,0,w,0,0,w,h);return out;
    }
    /** Hint voor de foto: te donker, schittering (veel wit uitgebeten) of onscherp (weinig randen). Alleen ja/nee. */
    static void quality(Bitmap image,JSONObject diag)throws Exception {
        int[] size=new int[2];int[] g=gray(image,640,size);int w=size[0],h=size[1];long total=0;int clipped=0;
        for(int v:g){total+=v;if(v>=250)clipped++;}
        double mean=(double)total/g.length,lap=0,lap2=0;int n=0;
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int i=y*w+x,l=4*g[i]-g[i-1]-g[i+1]-g[i-w]-g[i+w];lap+=l;lap2+=(double)l*l;n++;}
        double var=n==0?0:lap2/n-(lap/n)*(lap/n);
        diag.put("donker",mean<70).put("glans",clipped>g.length/20).put("onscherp",var<15);
    }
}
