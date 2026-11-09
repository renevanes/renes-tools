package nl.rene.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Handtekening uit een foto of scan: alleen inlezen, rechtzetten (EXIF) en verkleinen, in het geheugen. De interface
 * (02-handtekening.js) maakt er lijnen van; de afbeelding zelf wordt nergens bewaard.
 */
final class PdfSignatureImage {
    static final int MAX_FILE=20*1024*1024, SIDE=1400, MAX_DATA=1200000;
    private PdfSignatureImage() { }

    /** Een melding die de interface mag tonen (Nederlands, vertaald in de interface). */
    static final class Fout extends Exception { Fout(String m) { super(m); } }

    static String load(Context c,Uri u,Voortgang v) throws Exception {
        PdfTools.checkUri(c,u);
        byte[] bytes;
        try(InputStream in=v.open(c,u)){
            if(in==null)throw new Fout(Taal.T("Deze afbeelding kan niet worden geopend"));
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>MAX_FILE)throw new Fout(Taal.T("De afbeelding is te groot"));out.write(b,0,n);}
            bytes=out.toByteArray();
        }
        v.stap("verwerken",0,0);
        return jpeg(bytes);
    }

    static String jpeg(byte[] bytes) throws Exception {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
        if(o.outWidth<8||o.outHeight<8||o.outWidth>16000||o.outHeight>16000||(long)o.outWidth*o.outHeight>80_000_000)throw new Fout(Taal.T("Deze afbeelding kan niet worden geopend"));
        int sample=1;while(Math.max(o.outWidth,o.outHeight)/(sample*2)>=SIDE)sample*=2;
        o=new BitmapFactory.Options();o.inSampleSize=sample;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(image==null)throw new Fout(Taal.T("Deze afbeelding kan niet worden geopend"));
        try{
            int side=Math.max(image.getWidth(),image.getHeight());
            if(side>SIDE){Bitmap scaled=Bitmap.createScaledBitmap(image,Math.max(1,image.getWidth()*SIDE/side),Math.max(1,image.getHeight()*SIDE/side),true);if(scaled!=image){image.recycle();image=scaled;}}
            Matrix m=new Matrix();
            try{switch(new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)){
                case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;
                case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;
                case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;default:break;
            }}catch(Exception ignored){ }
            if(!m.isIdentity()){Bitmap turned=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);if(turned!=image){image.recycle();image=turned;}}
            // Doorzichtig (een uitgeknipte handtekening als PNG): op wit, anders wordt de achtergrond zwart.
            if(image.hasAlpha()){Bitmap flat=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);Canvas paper=new Canvas(flat);paper.drawColor(Color.WHITE);paper.drawBitmap(image,0,0,null);image.recycle();image=flat;}
            for(int quality:new int[]{90,75,60}){
                ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,quality,out);
                String data="data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                if(data.length()<=MAX_DATA)return data;
            }
            throw new Fout(Taal.T("De afbeelding is te groot"));
        }finally{image.recycle();}
    }
}
