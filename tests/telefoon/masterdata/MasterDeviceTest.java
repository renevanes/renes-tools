package nl.rene.tools.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** CI-instrumentatie tegen echte Android SQLite; geen ingang in de productie-app. */
public final class MasterDeviceTest extends Instrumentation {
    private Context target,context;
    private Class<?> store,sessionType,keys;
    private Object session;
    private SecretKey key;
    private String token;
    private File root,dbFile;
    static final class Isolated extends ContextWrapper {
        private final File files;
        Isolated(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getNoBackupFilesDir(){File dir=new File(files,"nobackup");dir.mkdirs();return dir;}
    }
    interface Checked {void run()throws Exception;}
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static void rejected(Checked action)throws Exception{boolean denied=false;try{action.run();}catch(Exception expected){denied=true;}check(denied,"action must be refused");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle r=new Bundle();try{target=getTargetContext();runChecks();r.putString("masterdata","ok");finish(Activity.RESULT_OK,r);}catch(Throwable e){r.putString("masterdata","failed: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,r);}finally{if(root!=null)erase(root);}}
    static void erase(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)erase(child);f.delete();}
    Class<?> type(String name)throws Exception{return target.getClassLoader().loadClass("nl.rene.tools."+name);}
    Object call(Class<?> owner,Object instance,String name,Class<?>[] signature,Object... args)throws Exception{
        Method method=owner.getDeclaredMethod(name,signature);method.setAccessible(true);try{return method.invoke(instance,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new Exception(cause);}
    }
    JSONObject operation(String name,Class<?>[] extra,Object... args)throws Exception{
        Class<?>[] signature=new Class<?>[extra.length+4];signature[0]=Context.class;signature[1]=SecretKey.class;signature[2]=sessionType;signature[3]=String.class;System.arraycopy(extra,0,signature,4,extra.length);
        Object[] values=new Object[args.length+4];values[0]=context;values[1]=key;values[2]=session;values[3]=token;System.arraycopy(args,0,values,4,args.length);
        return (JSONObject)call(store,null,name,signature,values);
    }
    JSONObject save(JSONObject r)throws Exception{return operation("save",new Class<?>[]{JSONObject.class},r);}
    static byte[] read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    static void write(File f,byte[] data)throws Exception{try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}}
    void privacyFrames()throws Exception {
        Activity activity=startActivitySync(new android.content.Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        Throwable[] problem={null};runOnMainSync(()->{try{
            Class<?> main=activity.getClass();java.lang.reflect.Field counter=main.getDeclaredField("masterSecurityGeneration"),screen=main.getDeclaredField("masterScreen"),wanted=main.getDeclaredField("masterWanted"),view=main.getDeclaredField("masterView");
            counter.setAccessible(true);screen.setAccessible(true);wanted.setAccessible(true);view.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong)counter.get(activity)).set(8);screen.setBoolean(activity,true);wanted.setBoolean(activity,false);view.set(activity,"");activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            Class<?> drawn=type("MainActivity$MasterDrawn");java.lang.reflect.Constructor<?> ctor=drawn.getDeclaredConstructor(main,String.class,long.class,boolean.class);ctor.setAccessible(true);
            Method complete=drawn.getDeclaredMethod("onComplete",long.class);complete.setAccessible(true);
            complete.invoke(ctor.newInstance(activity,"",7L,false),0L);check(screen.getBoolean(activity),"old locked frame cannot release new protection");
            complete.invoke(ctor.newInstance(activity,"",8L,false),0L);check(!screen.getBoolean(activity),"matching locked frame releases protection after leaving central screen");
            screen.setBoolean(activity,true);wanted.setBoolean(activity,true);view.set(activity,"new-private-view");activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            complete.invoke(ctor.newInstance(activity,"old-private-view",8L,false),0L);check(screen.getBoolean(activity),"wrong view cannot release protection");
        }catch(Throwable e){problem[0]=e;}finally{activity.finish();}});if(problem[0]!=null)throw new Exception(problem[0]);
    }
    void photoAndProfileChecks(JSONObject identity,String docId,String me)throws Exception {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(1800,1000,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);
        java.io.ByteArrayOutputStream original=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,original);bitmap.recycle();
        String image=(String)call(type("MasterPhotos"),null,"normalize",new Class<?>[]{java.io.InputStream.class},new java.io.ByteArrayInputStream(original.toByteArray()));
        byte[] jpeg=android.util.Base64.decode(image.substring(image.indexOf(',')+1),android.util.Base64.DEFAULT);check(jpeg.length<=256000,"bounded reencoded image");
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);check(bounds.outWidth<=1600&&bounds.outHeight<=1600,"bounded image dimensions");
        Class<?>[] sig={String.class,long.class,String.class,String.class,boolean.class};
        operation("photo",sig,docId,1L,"front",image,false);operation("photo",sig,docId,2L,"back",image,false);
        JSONObject read=operation("read",new Class<?>[]{String.class,String.class,String.class},docId,"masterdata","").getJSONObject("record");check(read.getJSONObject("images").getBoolean("front")&&read.getJSONObject("images").getBoolean("back")&&!read.toString().contains("base64"),"editor receives presence flags only");
        JSONObject consumer=operation("read",new Class<?>[]{String.class,String.class,String.class},docId,"formulieren","kind").getJSONObject("record");check(!consumer.has("images")&&!consumer.toString().contains("base64"),"consumer receives no image data");
        identity.put("revision",3).put("images",new JSONObject().put("front","INJECTED"));identity.getJSONObject("fields").put("afgegevenDoor","Testgemeente");save(identity);
        check(operation("photo",sig,docId,4L,"front",null,true).getString("image").equals(image),"text saves preserve native photos and ignore incoming image fields");
        rejected(()->operation("photo",sig,docId,1L,"front",null,false));
        Class<?> commit=type("MasterStore$Commit");Object cancel=java.lang.reflect.Proxy.newProxyInstance(commit.getClassLoader(),new Class<?>[]{commit},(proxy,m,args)->{throw new Exception("cancelled");});
        rejected(()->operation("photo",new Class<?>[]{String.class,long.class,String.class,String.class,boolean.class,commit},docId,4L,"front",null,false,cancel));check(operation("photo",sig,docId,4L,"front",null,true).getString("image").equals(image),"cancelled import transaction rolls back");
        operation("photo",sig,docId,4L,"back",null,false);check(operation("photo",sig,docId,5L,"back",null,true).getString("image").isEmpty(),"delete side");
        check(!new String(read(dbFile),"ISO-8859-1").contains(image.substring(0,80)),"photos encrypted at rest");
        JSONObject child=new JSONObject().put("type","persoon").put("label","Testkind").put("fields",new JSONObject().put("naam","Testkind"));String kid=save(child).getJSONObject("record").getString("id");
        JSONObject relation=new JSONObject().put("type","relatie").put("label","Testrelatie").put("fields",new JSONObject().put("vanPersoon",me).put("naarPersoon",kid).put("relatie","kind").put("delen","ja"));save(relation);
        check(operation("formRelation",new Class<?>[]{String.class,String.class,String.class},kid,me,"begeleider1").getString("relation").equals("ouder"),"form uses explicitly stored reverse relationship");
        JSONObject profile=new JSONObject().put("type","profiel").put("label","Testprofiel").put("fields",new JSONObject().put("persoon",me).put("delen","ja"));JSONObject saved=save(profile).getJSONObject("record");profile.put("id",saved.getString("id")).put("revision",1);
        operation("refreshContext",new Class<?>[]{});Class<?> cache=type("MasterAppContext");JSONObject shared=(JSONObject)call(cache,null,"read",new Class<?>[]{Context.class},context);check(shared.has("self")&&shared.getJSONArray("people").length()==1&&!shared.toString().contains("123456782")&&!shared.toString().contains("DOC123456"),"limited profile context");
        File cacheFile=new File(context.getNoBackupFilesDir(),"app-identiteit.bin");check(cacheFile.isFile()&&!new String(read(cacheFile),"ISO-8859-1").contains("SecretTestPerson"),"shared context is encrypted outside backups");
        rejected(()->save(new JSONObject().put("type","profiel").put("label","Duplicaat").put("fields",profile.getJSONObject("fields"))));
        profile.getJSONObject("fields").put("delen","nee");save(profile);check(!((JSONObject)call(cache,null,"read",new Class<?>[]{Context.class},context)).has("self"),"sharing revoked before save completes");
        operation("refreshContext",new Class<?>[]{});check(!cacheFile.exists(),"revoked cache remains absent after refresh");
        call(cache,null,"reset",new Class<?>[]{Context.class},context);
    }
    JSONObject full(String id)throws Exception{return operation("read",new Class<?>[]{String.class,String.class,String.class},id,"masterdata","").getJSONObject("record");}
    void scanSaveChecks()throws Exception {
        String owner=save(new JSONObject().put("type","persoon").put("label","OCR testpersoon").put("fields",new JSONObject().put("naam","Bestaande naam").put("voornamen","Anna"))).getJSONObject("record").getString("id");
        String doc=save(new JSONObject().put("type","identiteit").put("label","Rijbewijs testpersoon").put("fields",new JSONObject().put("persoon",owner).put("documentsoort","rijbewijs").put("afgegevenDoor","Bewaren"))).getJSONObject("record").getString("id");
        JSONObject input=new JSONObject().put("documentId",doc).put("documentRevision",1).put("personId",owner).put("personRevision",1).put("document",new JSONObject().put("document","1234567890").put("categorieen","AM, B")).put("persoon",new JSONObject().put("geboortedatum","1980-02-01"));
        operation("scanSave",new Class<?>[]{JSONObject.class},input);
        check(full(doc).getLong("revision")==2&&full(owner).getLong("revision")==2,"document and owner saved together");
        check(full(doc).getJSONObject("fields").getString("afgegevenDoor").equals("Bewaren")&&full(owner).getJSONObject("fields").getString("naam").equals("Bestaande naam"),"unselected values preserved");
        rejected(()->operation("scanSave",new Class<?>[]{JSONObject.class},input));
        input.put("documentRevision",2).put("personRevision",2);input.getJSONObject("document").put("document","SHOULDROLLBACK");input.getJSONObject("persoon").put("geboortedatum","1980-02-31");
        rejected(()->operation("scanSave",new Class<?>[]{JSONObject.class},input));
        check(full(doc).getLong("revision")==2&&full(doc).getJSONObject("fields").getString("document").equals("1234567890"),"invalid owner field rolls back the document too");
        input.getJSONObject("persoon").put("geboortedatum","1980-02-01");input.getJSONObject("document").put("persoon",owner);
        rejected(()->operation("scanSave",new Class<?>[]{JSONObject.class},input));
        check(full(doc).getLong("revision")==2,"association cannot be changed via scan patch");
    }
    void drivingLicenceOcr()throws Exception {
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(1500,950,android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.WHITE);android.graphics.Canvas canvas=new android.graphics.Canvas(image);android.graphics.Paint paint=new android.graphics.Paint(3);
        paint.setColor(android.graphics.Color.BLACK);paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));paint.setTextSize(44);
        String[] lines={"NEDERLAND RIJBEWIJS","1. DE VRIES","2. ANNA JOSEFINA","3. 01.02.1980 ROTTERDAM","4a. 03.04.2020   4b. 03.04.2030","4c. RDW","5. 0123456789","9. AM/B/BE"};
        for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],220,90+i*100,paint);
        for(int turn:new int[]{0,90}){
            android.graphics.Matrix rotation=new android.graphics.Matrix();rotation.setRotate(turn);android.graphics.Bitmap page=turn==0?image:android.graphics.Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),rotation,true);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();page.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,out);
            JSONObject found=(JSONObject)call(type("RijbewijsOcr"),null,"read",new Class<?>[]{Context.class,byte[].class,String.class},context,out.toByteArray(),"2026-11-08");
            check(found!=null,"bundled OCR reads synthetic licence, rotation "+turn);
            JSONObject fields=found.getJSONObject("document"),person=found.getJSONObject("persoon");
            check(fields.getString("documentsoort").equals("rijbewijs")&&fields.getString("document").equals("0123456789"),"actual OCR document number");
            check(person.getString("naam").equals("DE VRIES")&&person.getString("geboortedatum").equals("1980-02-01"),"actual OCR name and birth date");
            check(fields.getString("geldig").equals("2030-04-03")&&!person.has("bsn"),"actual OCR expiry, no inferred BSN");
            if(page!=image)page.recycle();
        }
        documentOcr(image);
        documentSides(image);
        image.recycle();File model=new File(context.getNoBackupFilesDir(),"document-ocr/tessdata/eng.traineddata");check(model.isFile(),"public OCR model available offline");
        File[] cached=model.getParentFile().listFiles();check(cached!=null&&cached.length==1,"no photo or recognized text cached with model");
    }
    /** DocumentFotos.zet: de foto's uit een document (zoals de PDF-tool Foto's uit PDF en Stamgegevens). */
    @SuppressWarnings("unchecked")
    java.util.List<Object> fotos(byte[] bytes,int max)throws Exception{
        Object u=call(type("DocumentFotos"),null,"zet",new Class<?>[]{Context.class,byte[].class,int.class,java.util.function.BooleanSupplier.class},context,bytes,max,(java.util.function.BooleanSupplier)()->true);
        java.lang.reflect.Field f=u.getClass().getDeclaredField("fotos");f.setAccessible(true);return (java.util.List<Object>)f.get(u);
    }
    static Object veld(Object o,String naam)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(naam);f.setAccessible(true);return f.get(o);}
    JSONObject herken(byte[] foto)throws Exception{
        return (JSONObject)call(type("MasterDocumentImages"),null,"herkenFoto",new Class<?>[]{Context.class,byte[].class,String.class,java.util.function.BooleanSupplier.class},context,foto,"2026-11-08",(java.util.function.BooleanSupplier)()->true);
    }
    void documentOcr(android.graphics.Bitmap licence)throws Exception {
        // Een PDF zonder losse foto (Android tekent de kaart in de pagina): elke pagina wordt een foto; daarop het rijbewijs herkend.
        java.io.ByteArrayOutputStream pdfBytes=new java.io.ByteArrayOutputStream();
        android.graphics.pdf.PdfDocument pdf=new android.graphics.pdf.PdfDocument();
        try{
            android.graphics.pdf.PdfDocument.Page page=pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(1500,950,1).create());
            pdf.finishPage(page);
            page=pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(1500,950,2).create());
            page.getCanvas().drawBitmap(licence,0,0,null);
            pdf.finishPage(page);
            pdf.writeTo(pdfBytes);
        }finally{pdf.close();}
        java.util.List<Object> uitPdf=fotos(pdfBytes.toByteArray(),12);
        check(uitPdf.size()==2&&(Boolean)veld(uitPdf.get(0),"heleBladzijde")&&(Integer)veld(uitPdf.get(1),"pagina")==2,"two pages become two photos");
        JSONObject f=herken((byte[])veld(uitPdf.get(1),"jpeg"));
        check(f!=null&&f.getJSONObject("document").getString("document").equals("0123456789"),"ID number from the page photo");
        // Word: de foto zelf (PNG wordt JPEG)
        java.io.ByteArrayOutputStream docxBytes=new java.io.ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(docxBytes)){
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/media/image1.png"));
            licence.compress(android.graphics.Bitmap.CompressFormat.PNG,100,zip);
            zip.closeEntry();
        }
        java.util.List<Object> uitWord=fotos(docxBytes.toByteArray(),12);
        check(uitWord.size()==1&&!(Boolean)veld(uitWord.get(0),"heleBladzijde"),"Word photo extracted");
        byte[] wj=(byte[])veld(uitWord.get(0),"jpeg");check(wj[0]==(byte)0xFF&&wj[1]==(byte)0xD8,"converted to JPEG");
        check(herken(wj).getJSONObject("document").getString("document").equals("0123456789"),"ID number from the Word photo");
        // Een foto van het ID-bewijs klein op een A4 (zoals in een Google-document als PDF): de foto komt er op volle grootte uit
        java.io.ByteArrayOutputStream foto=new java.io.ByteArrayOutputStream(),klein=new java.io.ByteArrayOutputStream();
        licence.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,foto);
        Class<?> writer=type("PdfWriter"),pageType=type("PdfWriter$Page");
        java.lang.reflect.Constructor<?> mk=pageType.getDeclaredConstructor(byte[].class,int.class,int.class,boolean.class,float.class,float.class,float.class,float.class,float.class,float.class);mk.setAccessible(true);
        Object a4=mk.newInstance(foto.toByteArray(),1500,950,false,595.28f,841.89f,60f,600f,150f,95f);
        call(writer,null,"write",new Class<?>[]{java.io.OutputStream.class,java.util.List.class,String.class},klein,Arrays.asList(a4),"Test");
        java.util.List<Object> uitA4=fotos(klein.toByteArray(),12);
        check(uitA4.size()==1&&!(Boolean)veld(uitA4.get(0),"heleBladzijde"),"the embedded card photo, not the page");
        check(Arrays.equals((byte[])veld(uitA4.get(0),"jpeg"),foto.toByteArray()),"the embedded photo unchanged (full size)");
        check(fotos(klein.toByteArray(),0).isEmpty(),"limit respected");
        // Een scan van een A4 met voor- en achterkant (één foto zo groot als de pagina): de twee kaarten komen er los uit,
        // en de pagina zelf ook (als laatste)
        android.graphics.Bitmap scan=android.graphics.Bitmap.createBitmap(1240,1754,android.graphics.Bitmap.Config.ARGB_8888);
        scan.eraseColor(android.graphics.Color.WHITE);
        android.graphics.Canvas sc=new android.graphics.Canvas(scan);
        // Een echt rijbewijs heeft een gekleurde achtergrond (roze): het witte testplaatje zo gekleurd (wit wordt roze, tekst blijft zwart)
        android.graphics.Paint roze=new android.graphics.Paint();roze.setColorFilter(new android.graphics.PorterDuffColorFilter(android.graphics.Color.rgb(240,200,212),android.graphics.PorterDuff.Mode.MULTIPLY));
        sc.drawBitmap(android.graphics.Bitmap.createScaledBitmap(licence,856,542,true),120,120,roze);
        android.graphics.Paint grijs=new android.graphics.Paint();grijs.setColor(android.graphics.Color.rgb(205,215,230));
        sc.drawRect(160,900,1016,1442,grijs);
        java.io.ByteArrayOutputStream scanJpg=new java.io.ByteArrayOutputStream(),scanPdf=new java.io.ByteArrayOutputStream();
        scan.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,scanJpg);scan.recycle();
        Object scanPage=call(writer,null,"layout",new Class<?>[]{byte[].class,int.class,int.class,boolean.class,boolean.class,float.class},scanJpg.toByteArray(),1240,1754,false,true,0f);
        call(writer,null,"write",new Class<?>[]{java.io.OutputStream.class,java.util.List.class,String.class},scanPdf,Arrays.asList(scanPage),"Test");
        java.util.List<Object> uitScan=fotos(scanPdf.toByteArray(),12);
        check(uitScan.size()==3,"two cards and the page from a scan ("+uitScan.size()+")");
        check(!(Boolean)veld(uitScan.get(0),"heleBladzijde")&&(Integer)veld(uitScan.get(0),"breed")<1000,"first a card, not the page");
        check(herken((byte[])veld(uitScan.get(0),"jpeg")).getJSONObject("document").getString("document").equals("0123456789"),"ID number from the cut-out card");
    }
    void documentSides(android.graphics.Bitmap licence)throws Exception {
        java.io.ByteArrayOutputStream front=new java.io.ByteArrayOutputStream(),back=new java.io.ByteArrayOutputStream();
        licence.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,front);
        android.graphics.Bitmap blank=android.graphics.Bitmap.createBitmap(900,560,android.graphics.Bitmap.Config.ARGB_8888);
        blank.eraseColor(android.graphics.Color.LTGRAY);
        blank.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,back);
        blank.recycle();
        // Bewust achterkant eerst, in beide bestandstypen; image10 na image2.
        java.io.ByteArrayOutputStream word=new java.io.ByteArrayOutputStream(),pdf=new java.io.ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(word)){
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/media/image10.jpg"));
            front.writeTo(zip);
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/media/image2.jpg"));
            back.writeTo(zip);
            zip.closeEntry();
        }
        Class<?> writer=type("PdfWriter");
        Class<?>[] layout={byte[].class,int.class,int.class,boolean.class,boolean.class,float.class};
        Object backPage=call(writer,null,"layout",layout,back.toByteArray(),900,560,false,true,28f);
        Object frontPage=call(writer,null,"layout",layout,front.toByteArray(),1500,950,false,true,28f);
        call(writer,null,"write",new Class<?>[]{java.io.OutputStream.class,java.util.List.class,String.class},pdf,Arrays.asList(backPage,frontPage),"Test");
        for(byte[] bytes:new byte[][]{word.toByteArray(),pdf.toByteArray()}){
            String wat=bytes[0]=='%'?"pdf":"word";
            java.util.List<Object> l=fotos(bytes,12);
            check(l.size()==2,"two photos ("+wat+")");
            byte[] a=(byte[])veld(l.get(0),"jpeg"),b=(byte[])veld(l.get(1),"jpeg");
            check(Arrays.equals(a,back.toByteArray())&&Arrays.equals(b,front.toByteArray()),"photos unchanged and in document order ("+wat+")");
            JSONObject fa=herken(a),fb=herken(b);
            Object pair=call(type("MasterDocumentImages"),null,"pair",new Class<?>[]{byte[].class,JSONObject.class,byte[].class,JSONObject.class,String.class},a,fa,b,fb,"rijbewijs");
            check(pair!=null&&Arrays.equals((byte[])veld(pair,"front"),front.toByteArray())&&Arrays.equals((byte[])veld(pair,"back"),back.toByteArray()),"recognized licence is the front ("+wat+")");
        }
    }
    void runChecks()throws Exception {
        store=type("MasterStore");sessionType=type("MasterSession");keys=type("MasterKeys");
        // Stop op een installatie met echte centrale gegevens; uitsluitend geisoleerde testopslag.
        check(!(Boolean)call(keys,null,"has",new Class<?>[]{String.class},"renes_masterdata_ready_v1"),"requires fresh CI installation");
        root=new File(target.getCacheDir(),"masterdata-ci-"+java.util.UUID.randomUUID());check(root.mkdirs(),"isolated files");context=new Isolated(target,root);
        java.lang.reflect.Constructor<?> ctor=sessionType.getDeclaredConstructor();ctor.setAccessible(true);session=ctor.newInstance();token=(String)call(sessionType,session,"open",new Class<?>[]{});
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);key=generator.generateKey();
        operation("unlock",new Class<?>[]{});dbFile=new File(root,"masterdata/master.db");
        JSONObject person=new JSONObject().put("type","persoon").put("label","CENTRAL_PRIVATE_NAME_856201").put("fields",new JSONObject().put("naam","SecretTestPerson").put("bsn","123456782"));
        JSONObject saved=save(person).getJSONObject("record");String id=saved.getString("id");person.put("id",id).put("revision",1);
        JSONObject result=operation("read",new Class<?>[]{String.class,String.class,String.class},id,"masterdata","");check("123456782".equals(result.getJSONObject("record").getJSONObject("fields").getString("bsn")),"encrypted roundtrip");
        String fileText=new String(read(dbFile),java.nio.charset.StandardCharsets.ISO_8859_1);check(!fileText.contains("SecretTestPerson")&&!fileText.contains("123456782")&&!fileText.contains("CENTRAL_PRIVATE_NAME"),"no plaintext record or audit values in database");
        JSONObject recipient=operation("read",new Class<?>[]{String.class,String.class,String.class},id,"idcopy","ontvanger");check(recipient.getJSONObject("record").getJSONObject("fields").length()==1,"recipient projection only");
        rejected(()->operation("use",new Class<?>[]{String.class,long.class,String.class,String.class,JSONArray.class,boolean.class},id,1L,"idcopy","ontvanger",new JSONArray().put("bsn"),true));
        rejected(()->operation("use",new Class<?>[]{String.class,long.class,String.class,String.class,JSONArray.class,boolean.class},id,1L,"formulieren","kind",new JSONArray().put("bsn"),false));
        operation("use",new Class<?>[]{String.class,long.class,String.class,String.class,JSONArray.class,boolean.class},id,1L,"formulieren","kind",new JSONArray().put("bsn"),true);
        save(person);rejected(()->save(person)); // stale revision cannot overwrite or append an audit event
        JSONObject identity=new JSONObject().put("type","identiteit").put("label","Test document").put("fields",new JSONObject().put("persoon",id).put("document","DOC123456").put("geldig","2030-06-30"));JSONObject docSaved=save(identity).getJSONObject("record");String docId=docSaved.getString("id");identity.put("id",docId).put("revision",1);
        photoAndProfileChecks(identity,docId,id);
        rejected(()->operation("delete",new Class<?>[]{String.class,long.class},id,2L));
        JSONObject audit=operation("audit",new Class<?>[]{long.class},0L);check(!audit.getJSONArray("events").toString().contains("123456782"),"audit has field names without field values");
        // Transaction rollback when the session is withdrawn during work.
        Class<?> work=type("MasterStore$Work"),commit=type("MasterStore$Commit");boolean[] published={false};
        Object worker=java.lang.reflect.Proxy.newProxyInstance(work.getClassLoader(),new Class<?>[]{work},(proxy,m,args)->{call(sessionType,session,"close",new Class<?>[]{});return new JSONObject();});
        Object publisher=java.lang.reflect.Proxy.newProxyInstance(commit.getClassLoader(),new Class<?>[]{commit},(proxy,m,args)->{published[0]=true;return null;});
        rejected(()->call(store,null,"transaction",new Class<?>[]{Context.class,SecretKey.class,sessionType,String.class,work,commit},context,key,session,token,worker,publisher));check(!published[0],"revoked session never publishes");token=(String)call(sessionType,session,"open",new Class<?>[]{});
        // Passing the deadline during an already authorised commit must not report rollback afterwards.
        Object noWork=java.lang.reflect.Proxy.newProxyInstance(work.getClassLoader(),new Class<?>[]{work},(proxy,m,args)->new JSONObject());
        Object expires=java.lang.reflect.Proxy.newProxyInstance(commit.getClassLoader(),new Class<?>[]{commit},(proxy,m,args)->{java.lang.reflect.Field limit=sessionType.getDeclaredField("limit");limit.setAccessible(true);limit.setLong(session,android.os.SystemClock.elapsedRealtime()-1);return null;});
        call(store,null,"transaction",new Class<?>[]{Context.class,SecretKey.class,sessionType,String.class,work,commit},context,key,session,token,noWork,expires);
        check(!(Boolean)call(sessionType,session,"isOpen",new Class<?>[]{}),"deadline passed during completed commit");token=(String)call(sessionType,session,"open",new Class<?>[]{});
        scanSaveChecks();drivingLicenceOcr();
        byte[] good=read(dbFile);
        for(String sql:new String[]{"UPDATE records SET body=X'00010203'", "DELETE FROM events WHERE seq=1", "CREATE TABLE surprise(secret TEXT)","DELETE FROM records"}){
            SQLiteDatabase db=SQLiteDatabase.openDatabase(dbFile.getPath(),null,0);try{db.execSQL(sql);}finally{db.close();}
            rejected(()->operation("list",new Class<?>[]{String.class,String.class},"masterdata",""));write(dbFile,good);
        }
        byte[] corrupt="Invalid SQLite database; preserve original bytes".getBytes("UTF-8");write(dbFile,corrupt);
        rejected(()->operation("list",new Class<?>[]{String.class,String.class},"masterdata",""));check(Arrays.equals(corrupt,read(dbFile)),"corruption never deletes/replaces original database");
        privacyFrames();
        // An emulator without a configured phone lock must refuse an auth-bound key.
        android.app.KeyguardManager kg=(android.app.KeyguardManager)target.getSystemService(Context.KEYGUARD_SERVICE);
        if(!kg.isDeviceSecure())rejected(()->call(keys,null,"authenticated",new Class<?>[]{Context.class},context));
    }
}
