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
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Kaartzijden na expliciete bestandskeuze en hernieuwde ontgrendeling, zonder leesbare werkbestanden. */
final class MasterPhotos {
    static final int REQUEST=94,MAX_BYTES=256000,MAX_DATA=350000;
    /** Langste duur van herkennen voor de voortgangsbalk: eerst de strook, dan de tekst van een rijbewijs. */
    static final long HERKEN_MS=RijbewijsOcr.TIJD+5000,HERKEN_GRONDIG_MS=RijbewijsOcr.TIJD_GRONDIG+5000;
    final MainActivity activity;
    private Pending pending;
    MasterPhotos(MainActivity activity){this.activity=activity;}
    static final String GOOGLE_DOC="application/vnd.google-apps.document",
        DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document",PDF="application/pdf";
    /** In welk formaat een virtueel document (Google-document) op te halen is: liefst Word (de foto's blijven op volle grootte), anders PDF. Zuiver. */
    static String exportType(String[] types){
        if(types==null)return null;
        for(String want:new String[]{DOCX,PDF})for(String t:types)if(want.equalsIgnoreCase(t))return want;
        return null;
    }
    static final java.util.List<String> SOORTEN=java.util.Arrays.asList("","paspoort","identiteitskaart","rijbewijs","verblijfsdocument");
    static final class Pending {
        final String id,side,request,view,soort;final long revision,dataset;Uri uri;
        /** Een Google-document en dergelijke: geen eigen bestand, alleen als PDF of Word-bestand op te halen (exportType). */
        boolean virtual;
        /** Na het lezen van een Google-document: de tijdslimiet op het lezen weer weghalen. */
        Runnable exportKlaar;
        /** Lezen en herkennen loopt; dat gaat door als de stamgegevens intussen op slot gaan (scherm uit). */
        boolean bezig;
        /** Wat lezen en herkennen opleverde, nog niet bewaard: bewaren kan pas met een open sessie (zie resume). */
        Uitkomst klaar;
        /** Wanneer klaar kwam: na BEWAAR_MS zonder ontgrendelen gaat het uit het geheugen. */
        long klaarOp;
        /** Een document met meer foto's: Rene kiest er één of twee (voor- en achterkant; voor één kant één); alleen in het geheugen. */
        String reviewId,reviewToken;java.util.List<DocumentFotos.Foto> keuze;
        /** De gekozen foto's worden herkend (saveReview): dat gaat door als Stamgegevens vanzelf op slot gaat. */
        boolean kiezen;
        Pending(String id,long revision,String side,String request,String view,String soort){this.id=id;this.revision=revision;this.side=side;this.request=request;this.view=view;this.soort=soort;this.dataset=MasterSession.datasetEpoch();}
    }
    synchronized void pick(String token,String id,long revision,String side,String request)throws Exception {pick(token,id,revision,side,request,"");}
    /** soort: bij een nieuw document (side "scan") de soort die Rene koos; wat uit de strook herkend wordt, gaat voor. */
    synchronized void pick(String token,String id,long revision,String side,String request,String soort)throws Exception {
        if(pending!=null)throw new MasterError(Taal.T("Er staat al een kaartfoto klaar; ontgrendel om die op te slaan"));
        if(!side.equals("front")&&!side.equals("back")&&!side.equals("scan"))throw new MasterError(Taal.T("Kies de voorzijde of achterzijde"));
        if(soort==null||!SOORTEN.contains(soort))throw new MasterError(Taal.T("Kies wat voor document het is"));
        Pending job=new Pending(id,revision,side,request,activity.masterView,soort);pending=job;stop="onbekend";
        activity.h.post(()->{
            synchronized(MasterPhotos.this){if(pending!=job)return;}
            if(!activity.masterForeground||!activity.masterSession.accepts(token)||!job.view.equals(activity.masterView)){clear(job);notify(job,false,Taal.T("Ontgrendel de stamgegevens opnieuw"));return;}
            // Zolang de kiezer open is (hooguit 2 minuten) niet op slot: zie MainActivity.masterPickUntil.
            if(activity.masterSession.accepts(token)){activity.masterPickReturned=false;activity.masterPickKind="photo";activity.masterPickUntil=android.os.SystemClock.elapsedRealtime()+MainActivity.MASTER_PICK_MS;activity.h.postDelayed(activity::masterPickExpired,MainActivity.MASTER_PICK_MS+500);}
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Een foto of een document (PDF, Word, ook een Google-document: een "virtueel" bestand zonder CATEGORY_OPENABLE;
            // dat haalt resume() als Word-bestand of PDF op). Uit een document kiest Rene daarna de foto (wens van Rene:
            // ook voor de voor- of achterkant van een bestaand document).
            intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "image/*","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document",GOOGLE_DOC
            });
            try{activity.startActivityForResult(intent,REQUEST);}catch(Throwable e){activity.masterPickUntil=0;clear(job);notify(job,false,Taal.T("Bestandskiezer niet beschikbaar"));}
        });
    }
    synchronized void clear(Pending job){if(pending==job)pending=null;notifyAll();}
    /** Vaste woorden voor het logboek: liep de zoektijd af? */
    static String tijdOp(long tot){return android.os.SystemClock.elapsedRealtime()>=tot?", tijd op":"";}
    synchronized void cancel(){cancel("anders");}
    /** reden (vaste woorden, voor het logboek): waarom een lopend verwerken stopte. */
    synchronized void cancel(String reden){if(pending!=null)stop=reden;pending=null;notifyAll();}
    private volatile String stop="onbekend";
    /** Vanzelf op slot: een open keuze vervalt, maar herkennen van wat al gekozen is gaat door (bewaard na ontgrendelen). */
    synchronized void cancelReview(){if(pending!=null&&pending.reviewId!=null&&!pending.kiezen){stop="slot vanzelf";pending=null;}notifyAll();}
    /**
     * Uitkomst van lezen en herkennen, alleen in het geheugen (nooit in een bestand): de genormaliseerde foto(’s) en wat er
     * herkend is, of de fout. Weg met de klaargezette keuze (cancel, vergrendelen met de hand, de app sluiten).
     */
    static final class Uitkomst {
        String image,error;JSONObject found,diag;
        /** Gekozen foto's uit een document, herkend maar nog niet bewaard (op slot gegaan tijdens herkennen). */
        JSONObject images;
        /** Meer foto's in een document: Rene kiest (review). */
        java.util.List<DocumentFotos.Foto> keuze;
    }
    /** Hoe lang een herkende foto of document in het geheugen wacht op ontgrendelen; daarna opnieuw kiezen. */
    static final long BEWAAR_MS=15*60_000L;
    /** Hoe lang ontgrendelen hooguit wacht op herkennen dat nog loopt (lezen, een Google-document ophalen, zoeken). */
    static final long WACHT_MS=60_000L*2+MasterDocumentImages.SEARCH_MS+30_000; // twee keer EXPORT_MS (ophalen en lezen), omzetten/herkennen en marge
    /** Hoeveel foto's uit een document hooguit te kiezen zijn. */
    static final int MAX_KEUZE=12;
    /** Meer foto's in het document: ze tonen (klein) en Rene laten kiezen; de foto's zelf blijven in het geheugen. */
    private JSONObject review(Pending job,String token,java.util.List<DocumentFotos.Foto> alle,JSONObject info)throws Exception{
        // Een foto die Android niet helemaal kan openen (geen voorbeeld) valt af: die is ook niet te herkennen of te bewaren
        JSONArray lijst=new JSONArray();java.util.List<DocumentFotos.Foto> fotos=new java.util.ArrayList<>();
        for(DocumentFotos.Foto f:alle){String src=DocumentFotos.voorbeeld(f.jpeg,600);if(src.isEmpty())continue;fotos.add(f);lijst.put(new JSONObject().put("src",src).put("pagina",f.pagina).put("heel",f.heleBladzijde));}
        if(fotos.isEmpty())throw new MasterError(Taal.T("Geen foto's in dit document gevonden. Kies een document met een foto van het ID-bewijs, of een foto van de kaart zelf."));
        synchronized(activity.masterSession){
            activity.masterSession.require(token);
            synchronized(this){
                if(pending!=job||!activity.masterForeground||!activity.masterWanted||
                    !job.view.equals(activity.masterView)||job.dataset!=MasterSession.datasetEpoch())
                    throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                job.reviewId=java.util.UUID.randomUUID().toString();
                job.reviewToken=token;
                job.keuze=fotos;
                job.uri=null;
                return new JSONObject().put("reviewId",job.reviewId).put("fotos",lijst).put("info",info).put("zijde",job.side);
            }
        }
    }
    /**
     * De gekozen foto('s) uit het document: één foto, of twee (voor- en achterkant, in de gekozen volgorde; wat herkend wordt
     * gaat voor). Herkennen gebeurt pas nu, alleen op wat Rene koos.
     */
    JSONObject saveReview(SecretKey key,MasterSession session,String token,String id,String gekozenJson)throws Exception{
        Pending job;
        java.util.List<DocumentFotos.Foto> fotos;
        synchronized(this){
            job=pending;
            if(job==null||id==null||!id.equals(job.reviewId)||!token.equals(job.reviewToken)||job.keuze==null||job.kiezen)
                throw new MasterError(Taal.T("Kies het bestand opnieuw."));
            fotos=job.keuze;
        }
        boolean kant=!"scan".equals(job.side); // de voor- of achterkant van een bestaand document: precies één foto
        JSONArray gekozen;boolean vast=false;
        // Een lijst nummers, of {fotos:[…], vast:true}: Rene gaf zelf aan wat de voor- en wat de achterkant is (de eerste is de voorkant)
        try{String g=gekozenJson==null?"":gekozenJson.trim();
            if(g.startsWith("{")){JSONObject o=new JSONObject(g);gekozen=o.getJSONArray("fotos");vast=o.optBoolean("vast");}
            else gekozen=new JSONArray(g);
        }catch(Exception e){throw new MasterError(kant?Taal.T("Kies één foto"):Taal.T("Kies één of twee foto's"));}
        if(gekozen.length()<1||gekozen.length()>(kant?1:2))throw new MasterError(kant?Taal.T("Kies één foto"):Taal.T("Kies één of twee foto's"));
        java.util.List<DocumentFotos.Foto> kies=new java.util.ArrayList<>();
        for(int i=0;i<gekozen.length();i++){
            int n=gekozen.optInt(i,-1);
            if(n<0||n>=fotos.size()||kies.contains(fotos.get(n)))throw new MasterError(kant?Taal.T("Kies één foto"):Taal.T("Kies één of twee foto's"));
            kies.add(fotos.get(n));
        }
        // Bezig: wie intussen opnieuw ontgrendelt (resume → uitkomst) wacht hierop en krijgt dan deze uitkomst
        synchronized(this){if(pending!=job)throw new MasterError(Taal.T("Kies het bestand opnieuw."));job.kiezen=true;job.bezig=true;job.klaar=null;}
        final Pending werk=job;
        java.util.function.BooleanSupplier active=()->{synchronized(MasterPhotos.this){return pending==werk&&werk.dataset==MasterSession.datasetEpoch();}};
        long tot=android.os.SystemClock.elapsedRealtime()+MasterDocumentImages.SEARCH_MS;
        java.util.function.BooleanSupplier zoekt=()->active.getAsBoolean()&&android.os.SystemClock.elapsedRealtime()<tot;
        String today=Regio.dayKey(activity,System.currentTimeMillis());
        JSONObject images=new JSONObject(),found;
        try(Voortgang v=Voortgang.van(activity,"kaartfoto",1)){
          try{
            v.tijd("herkennen",MasterDocumentImages.SEARCH_MS);
            if(kant){
                MrzOcr.Spoor spoor=new MrzOcr.Spoor();MrzOcr.volg(spoor);
                try{found=MasterDocumentImages.herkenFoto(activity,kies.get(0).jpeg,today,zoekt);}finally{MrzOcr.volg(null);}
                if(found==null&&active.getAsBoolean())App.log(activity,"STAMGEGEVENS","document, kaartfoto gekozen: niet herkend"+tijdOp(tot)+"; "+spoor.tekst());
                images.put(job.side,normalize(kies.get(0).jpeg));
            }else if(kies.size()==1){
                MrzOcr.Spoor spoor=new MrzOcr.Spoor();MrzOcr.volg(spoor);
                try{found=MasterDocumentImages.herkenFoto(activity,kies.get(0).jpeg,today,zoekt);}finally{MrzOcr.volg(null);}
                if(found==null&&active.getAsBoolean())App.log(activity,"STAMGEGEVENS","document, één foto gekozen: niet herkend"+tijdOp(tot)+"; "+spoor.tekst());
                String type=found==null?job.soort:found.getJSONObject("document").optString("documentsoort",job.soort);
                images.put(MasterSchema.scanSide(type,found),normalize(kies.get(0).jpeg));
            }else{
                // Eerst de strook op beide foto's (snel), pas daarna de tekst van een rijbewijs (langzaam): anders gaat alle tijd
                // naar de eerste foto en komt de tweede (vaak de kant met de strook) niet meer aan bod
                MrzOcr.Spoor spoor=new MrzOcr.Spoor();MrzOcr.volg(spoor);
                JSONObject a,b;
                try{a=MasterDocumentImages.herken(activity,kies.get(0).jpeg,today,zoekt,false);b=MasterDocumentImages.herken(activity,kies.get(1).jpeg,today,zoekt,false);}
                finally{MrzOcr.volg(null);}
                if(a==null&&b==null&&zoekt.getAsBoolean()){a=MasterDocumentImages.herken(activity,kies.get(0).jpeg,today,zoekt,true);if(a==null)b=MasterDocumentImages.herken(activity,kies.get(1).jpeg,today,zoekt,true);}
                MasterDocumentImages.Pair p=vast?MasterDocumentImages.pairVast(kies.get(0).jpeg,a,kies.get(1).jpeg,b):MasterDocumentImages.pair(kies.get(0).jpeg,a,kies.get(1).jpeg,b,job.soort);
                // Alleen vaste woorden en ja/nee: nooit tekst uit het document
                App.log(activity,"STAMGEGEVENS","document, twee foto's gekozen"+(vast?" (eigen volgorde)":"")+": herkend "+(a!=null?"ja":"nee")+"/"+(b!=null?"ja":"nee")+(p==null?", verschillend":"")
                    +(!active.getAsBoolean()?", gestopt":a==null&&b==null?tijdOp(tot)+"; "+spoor.tekst():""));
                if(p==null)throw new MasterError(Taal.T("De twee foto's lijken van verschillende ID-bewijzen te zijn. Kies de voor- en achterkant van hetzelfde ID-bewijs."));
                images.put("front",normalize(p.front)).put("back",normalize(p.back));found=p.fields;
            }
            v.stap("opslaan",0,0);
          }catch(Throwable e){
            boolean dicht=!session.accepts(token)||!activity.masterForeground;
            synchronized(this){
                job.kiezen=false;job.bezig=false;
                // Intussen op slot: het antwoord valt weg, dus de fout bewaren; na ontgrendelen geeft resume() hem
                if(dicht&&pending==job){Uitkomst u=new Uitkomst();u.error=e instanceof MasterError?e.getMessage():Taal.T("De kaartfoto kan niet worden opgeslagen. Kies een duidelijke afbeelding.");
                    job.reviewId=null;job.keuze=null;job.klaar=u;job.klaarOp=android.os.SystemClock.elapsedRealtime();}
                notifyAll();
            }
            if(e instanceof Exception)throw (Exception)e;throw (Error)e;
          }
        }
        boolean open=active.getAsBoolean()&&session.accepts(token)&&activity.masterForeground;
        synchronized(this){
            if(pending!=job){job.bezig=false;notifyAll();throw new MasterError(Taal.T("Kies het bestand opnieuw."));}
            if(!open){
                // Intussen vanzelf op slot (scherm uit) of opnieuw ontgrendeld: de uitkomst blijft in het geheugen en
                // resume() bewaart hem (na ontgrendelen, of meteen als er al opnieuw ontgrendeld is en resume wacht)
                Uitkomst u=new Uitkomst();u.found=found;
                if(kant)u.image=images.optString(job.side,null);else u.images=images;
                job.reviewId=null;job.keuze=null;job.kiezen=false;job.klaar=u;job.klaarOp=android.os.SystemClock.elapsedRealtime();job.bezig=false;notifyAll();
            }
        }
        if(!open){
            activity.h.postDelayed(()->vervallen(job),BEWAAR_MS+1000);
            throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
        }
        try{return kant?bewaarFoto(key,session,token,job,images.getString(job.side),found,new JSONObject()):scanDocument(key,session,token,job,images,found);}
        finally{synchronized(this){job.bezig=false;notifyAll();}clear(job);}
    }
    void notify(Pending job,boolean picked,String error){try{JSONObject out=new JSONObject().put("request",job.request).put("picked",picked).put("direct",picked&&activity.masterPicking());if(error!=null)out.put("error",error);activity.js("onMasterPhotoPicked",out.toString());}catch(Exception ignored){ }}
    void result(int result,Intent data){
        Pending job;synchronized(this){job=pending;}if(job==null)return;
        Uri uri=result==Activity.RESULT_OK&&data!=null?data.getData():null;
        if(uri==null){clear(job);notify(job,false,null);return;}
        try{if(!PdfTools.grantedBySender(data,java.util.Collections.singletonList(uri)))throw new Exception();PdfTools.checkUri(activity,uri);
            synchronized(this){if(pending!=job)return;job.uri=uri;}notify(job,true,null);
            // Een bestand uit de app zelf (Bron: kluis, Gemaakt): er was geen kiezer en dus geen onResume; meteen verwerken
            if(PdfProvider.AUTH.equals(uri.getAuthority())&&activity.masterPicking())continueAfterPick();
        }catch(Throwable e){clear(job);notify(job,false,Taal.T("Deze kaartfoto kan niet worden geopend"));}
    }
    /** Een document zonder eigen bestand (zoals een Google-document): alleen te openen als een ander formaat. */
    private boolean virtual(Uri uri){
        try{
            if(!android.provider.DocumentsContract.isDocumentUri(activity,uri))return false;
            try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{android.provider.DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){
                return c!=null&&c.moveToFirst()&&(c.getLong(0)&android.provider.DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)!=0;
            }
        }catch(Throwable e){return false;}
    }
    /** Hoe lang Drive (of een andere app) mag doen over het omzetten van een Google-document. */
    static final long EXPORT_MS=60_000;
    /**
     * Het gekozen bestand lezen (op de achtergrond). Een document zonder eigen bestand (Google-document) als Word-bestand
     * of PDF, zoals de app die het bewaart het aanbiedt; ook als gewoon openen mislukt en de app die formaten aanbiedt.
     */
    private InputStream open(Voortgang v,Pending job,java.util.function.BooleanSupplier active)throws Exception{
        // Elke kant mag een document zijn (zie pick): een bestand dat niet gewoon open gaat, als PDF of Word ophalen
        job.virtual=virtual(job.uri);
        if(!job.virtual){
            try{return v.open(activity,job.uri);}
            catch(java.io.FileNotFoundException|SecurityException e){job.virtual=true;}
        }
        String fout=Taal.T("Dit document kan niet als PDF of Word-bestand worden geopend. Download het eerst als PDF en kies dat bestand.");
        android.content.ContentResolver cr=activity.getContentResolver();
        android.os.CancellationSignal stop=new android.os.CancellationSignal();
        android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable afbreken=stop::cancel;
        h.postDelayed(afbreken,EXPORT_MS);
        try{
            String type=exportType(cr.getStreamTypes(job.uri,"*/*"));
            if(type==null||!active.getAsBoolean())throw new MasterError(fout);
            final android.content.res.AssetFileDescriptor fd=cr.openTypedAssetFileDescriptor(job.uri,type,null,stop);
            if(fd==null)throw new MasterError(fout);
            // Ook het lezen begrenzen: blijft de app die het levert hangen, dan breekt sluiten het lezen af.
            Runnable sluit=()->{try{fd.close();}catch(Throwable ignored){ }};
            h.postDelayed(sluit,EXPORT_MS);
            job.exportKlaar=()->h.removeCallbacks(sluit);
            return v.lees(fd.createInputStream(),Math.max(0,fd.getLength()));
        }catch(MasterError e){throw e;}
        catch(java.io.IOException|SecurityException|IllegalArgumentException|IllegalStateException|android.os.OperationCanceledException e){
            App.log(activity,"STAMGEGEVENS","document (google): niet op te halen, "+e.getClass().getSimpleName());
            throw new MasterError(fout);
        }finally{h.removeCallbacks(afbreken);}
    }
    /**
     * Terug uit de kiezer binnen de tijd, nog ontgrendeld: de foto meteen verwerken. Lezen en herkennen gaan door als de
     * stamgegevens intussen op slot gaan (scherm uit, app uit beeld; Voortgang houdt de app daarvoor wakker). Is de sessie
     * dan nog dezelfde, dan meteen bewaren; anders blijft de uitkomst in het geheugen en bewaart resume() hem na het
     * ontgrendelen (masterOpen). Alleen vergrendelen met de hand of annuleren breekt het af.
     */
    void continueAfterPick(){
        final String token=activity.masterSession.consumerToken();
        final Pending job;synchronized(this){if(pending==null||pending.uri==null)return;job=pending;}
        final String request=job.request;
        // Nooit stil blijven hangen op "Bestand verwerken…": de interface krijgt altijd antwoord.
        if(token.isEmpty()){cancel();gemist(request,"",Taal.T("Kies het bestand opnieuw."));return;}
        new Thread(App.veilig(()->{
            JSONObject out=new JSONObject();try{out.put("request",request);}catch(Exception ignored){ }
            try{
                Uitkomst u=uitkomst(job);
                synchronized(this){if(pending!=job){gemist(request,token,Taal.T("Kies het bestand opnieuw."));return;}}
                // Intussen op slot: niets melden; na ontgrendelen bewaart masterOpen het (de interface toont dat het wacht).
                if(u==null||!activity.masterSession.accepts(token)||!activity.masterForeground)return;
                SecretKey key=MasterKeys.authenticated(activity);JSONObject photo=resume(key,activity.masterSession,token);
                if(photo==null){synchronized(this){if(pending==job)return;}gemist(request,token,Taal.T("Kies het bestand opnieuw."));return;}
                out.put("photo",photo).put("token",token);
                activity.h.post(activity::scheduleMasterExpiry);
            }catch(Throwable e){try{out.put("photo",new JSONObject().put("error",MasterBridge.why(e))).put("token",token);}catch(Exception ignored){ }}
            activity.js("onMasterPhotoDone",out.toString());
        }),"master-photo-direct").start();
    }
    private void gemist(String request,String token,String error){
        try{activity.js("onMasterPhotoDone",new JSONObject().put("request",request).put("token",token).put("photo",new JSONObject().put("error",error)).toString());}catch(Exception ignored){ }
    }
    /**
     * Na ontgrendelen (of meteen na het kiezen): lezen en herkennen (als dat nog niet gebeurde; loopt het nog, dan wachten)
     * en daarna bewaren. Gaat de sessie onder het bewaren weer op slot, dan blijft de uitkomst klaarstaan (null).
     */
    JSONObject resume(SecretKey key,MasterSession session,String token){
        Pending job;synchronized(this){job=pending;if(job==null||job.uri==null&&job.klaar==null&&!job.bezig)return null;}
        JSONObject result=new JSONObject();
        boolean bewaren=true;
        try{
            Uitkomst u=uitkomst(job);
            if(u==null)return null;
            if(!session.accepts(token)||!activity.masterForeground){bewaren=false;return null;}
            return opslaan(key,session,token,job,u);
        }catch(Throwable e){
            // Weer op slot tijdens het bewaren (niet door Rene zelf): de uitkomst blijft voor de volgende keer ontgrendelen.
            // (eerst de sessie vragen, dan pas deze monitor: elders gaat het altijd sessie → MasterPhotos)
            boolean dicht=!session.accepts(token)||!activity.masterForeground;
            synchronized(this){if(dicht&&pending==job&&job.klaar!=null&&job.reviewId==null){bewaren=false;return null;}}
            try{result.put("error",e instanceof MasterError?e.getMessage():Taal.T("De kaartfoto kan niet worden opgeslagen. Kies een duidelijke afbeelding."));}catch(Exception ignored){ }
        }
        finally{if(bewaren)synchronized(this){if(job.reviewId==null)clear(job);}}
        return result;
    }
    /** De uitkomst van lezen en herkennen: al klaar, nog bezig (wachten, hooguit WACHT_MS), of nu doen. null = afgebroken. */
    private Uitkomst uitkomst(Pending job)throws Exception{
        synchronized(this){
            long tot=android.os.SystemClock.elapsedRealtime()+WACHT_MS;
            while(job.bezig&&pending==job){long left=tot-android.os.SystemClock.elapsedRealtime();if(left<=0)break;wait(left);}
            if(pending!=job)return null;
            if(job.klaar!=null&&android.os.SystemClock.elapsedRealtime()-job.klaarOp>BEWAAR_MS){pending=null;notifyAll();throw new MasterError(Taal.T("Kies het bestand opnieuw."));}
            if(job.klaar!=null)return job.klaar;
            if(job.bezig)throw new MasterError(Taal.T("Het herkennen duurt te lang. Kies het bestand opnieuw."));
            if(job.uri==null)return null;
            job.bezig=true;
        }
        Uitkomst u=null;
        try{u=analyse(job);}
        finally{synchronized(this){job.bezig=false;job.klaar=u;job.klaarOp=android.os.SystemClock.elapsedRealtime();job.uri=null;notifyAll();}}
        // Niet ontgrendeld binnen BEWAAR_MS: de uitkomst uit het geheugen (ook als niemand meer iets vraagt)
        activity.h.postDelayed(()->vervallen(job),BEWAAR_MS+1000);
        return u;
    }
    /** Een uitkomst die BEWAAR_MS niet bewaard is, gaat uit het geheugen (gerekend vanaf dat hij klaar was). */
    private synchronized void vervallen(Pending job){
        if(pending==job&&job.klaar!=null&&job.reviewId==null&&!job.bezig&&android.os.SystemClock.elapsedRealtime()-job.klaarOp>=BEWAAR_MS){stop="verlopen";pending=null;notifyAll();}
    }
    /** Lezen en herkennen; alleen in het geheugen. Stopt alleen als de keuze vervalt (annuleren, vergrendelen met de hand). */
    private Uitkomst analyse(Pending job){
        Uitkomst u=new Uitkomst();
        java.util.function.BooleanSupplier active=()->{synchronized(MasterPhotos.this){return pending==job&&job.dataset==MasterSession.datasetEpoch();}};
        try(Voortgang v=Voortgang.van(activity,"kaartfoto",1)){
            byte[] bytes;
            try{bytes=read(open(v,job,active));}
            catch(java.io.IOException e){
                if(!job.virtual)throw e;
                App.log(activity,"STAMGEGEVENS","document (google): lezen mislukt, "+e.getClass().getSimpleName());
                throw new MasterError(Taal.T("Dit document kan niet als PDF of Word-bestand worden geopend. Download het eerst als PDF en kies dat bestand."));
            }finally{Runnable k=job.exportKlaar;job.exportKlaar=null;if(k!=null)k.run();}
            JSONObject diag=new JSONObject();
            String today=Regio.dayKey(activity,System.currentTimeMillis());
            boolean document=DocumentFotos.pdf(bytes)||DocumentFotos.zip(bytes);
            JSONObject found;
            if(document){
                // Eerst omzetten naar losse foto's (zoals de PDF-tool Foto's uit PDF); daarna gaat het als met een foto
                String soortBestand=DocumentFotos.pdf(bytes)?"pdf":"word";
                v.stap("omzetten",0,0);
                DocumentFotos.Uitkomst d;
                try{d=DocumentFotos.zet(activity,bytes,MAX_KEUZE,active);}
                catch(java.io.IOException|IllegalArgumentException|SecurityException e){
                    App.log(activity,"STAMGEGEVENS","document ("+soortBestand+(job.virtual?", google":"")+"): niet te openen, "+e.getClass().getSimpleName());
                    throw new MasterError(Taal.T("Dit document kan niet worden geopend. Kies een leesbare PDF of een Word-document (.docx)."));
                }
                int heel=0;for(DocumentFotos.Foto f:d.fotos)if(f.heleBladzijde)heel++;
                // Alleen aantallen: nooit namen, nummers of tekst uit het document
                App.log(activity,"STAMGEGEVENS","document ("+soortBestand+(job.virtual?", google":"")+("scan".equals(job.side)?"":", kaartfoto")+"): foto's "+d.fotos.size()+", waarvan hele pagina's "+heel
                    +("pdf".equals(soortBestand)?", pagina's "+d.paginas:"")+(d.afgekapt?", afgekapt":"")+(active.getAsBoolean()?"":", gestopt: "+stop));
                if(!active.getAsBoolean())throw new MasterError(Taal.T("Kies het bestand opnieuw."));
                if(d.fotos.isEmpty())throw new MasterError(Taal.T("Geen foto's in dit document gevonden. Kies een document met een foto van het ID-bewijs, of een foto van de kaart zelf."));
                diag.put("fotos",d.fotos.size()).put("heel",heel).put("afgekapt",d.afgekapt);
                // Altijd eerst tonen (ook bij één foto): Rene ziet wat er in het document staat en kiest. Een hele pagina kan
                // meer bevatten dan het ID-bewijs; dat staat er zichtbaar bij.
                u.keuze=d.fotos;u.diag=diag;return u;
            }else{
                v.tijd("herkennen",HERKEN_MS);
                MrzOcr.Spoor spoor=new MrzOcr.Spoor();MrzOcr.volg(spoor);
                try{found=recognize(activity,bytes,today,active,false,diag);}finally{MrzOcr.volg(null);}
                // Alleen aantallen en ja/nee (zie MrzOcr.Spoor): waar het lezen van de strook bleef steken.
                if(found==null&&"scan".equals(job.side)&&active.getAsBoolean())App.log(activity,"STAMGEGEVENS","foto: niet herkend; "+spoor.tekst());
            }
            v.stap("opslaan",0,0);
            u.image=bytes==null?null:normalize(bytes);u.found=found;u.diag=diag;
        }catch(Throwable e){u.error=e instanceof MasterError?e.getMessage():Taal.T("De kaartfoto kan niet worden opgeslagen. Kies een duidelijke afbeelding.");}
        return u;
    }
    /** Bewaren met een open sessie: bij twee zijden eerst nakijken (review), bij een document een nieuw document, anders de foto. */
    private JSONObject opslaan(SecretKey key,MasterSession session,String token,Pending job,Uitkomst u)throws Exception{
        if(u.error!=null)throw new MasterError(u.error);
        if(job.dataset!=MasterSession.datasetEpoch()||!job.view.equals(activity.masterView)||!activity.masterWanted||!activity.masterForeground)throw new MasterError(Taal.T("Kies de kaartfoto opnieuw vanuit Stamgegevens"));
        session.require(token);
        if(u.keuze!=null)return review(job,token,u.keuze,u.diag);
        if(u.images!=null)return scanDocument(key,session,token,job,u.images,u.found).put("diag",u.diag);
        // Herkennen uit foto (bij een persoon): een nieuw document met deze foto als bijlage, daarna voorstellen.
        if("scan".equals(job.side)){
            String type=u.found==null?job.soort:u.found.getJSONObject("document").optString("documentsoort",job.soort);
            return scanDocument(key,session,token,job,new JSONObject().put(MasterSchema.scanSide(type,u.found),u.image),u.found).put("diag",u.diag);
        }
        return bewaarFoto(key,session,token,job,u.image,u.found,u.diag);
    }
    /** De kaartfoto (voor- of achterkant) bij het document bewaren. */
    private JSONObject bewaarFoto(SecretKey key,MasterSession session,String token,Pending job,String image,JSONObject found,JSONObject diag)throws Exception{
        JSONObject result=MasterStore.photo(activity,key,session,token,job.id,job.revision,job.side,image,false,()->{synchronized(MasterPhotos.this){if(pending!=job||job.dataset!=MasterSession.datasetEpoch()||!job.view.equals(activity.masterView)||!activity.masterWanted||!activity.masterForeground)throw new MasterError(Taal.T("Kaartfoto opslaan geannuleerd"));pending=null;}});
        result.put("saved",true).put("diag",diag);if(found!=null)result.put("herkend",found);
        return result;
    }
    /**
     * Nieuw identiteitsdocument bij de persoon, met de foto als bijlage: de strook van een ID-kaart staat op de achterkant,
     * die van een paspoort op de fotopagina (voorkant). Niets herkend: de soort die Rene koos en de kant waar de app om vroeg.
     * Herkende gegevens worden niet vanzelf ingevuld; die zijn een voorstel.
     */
    JSONObject scanDocument(SecretKey key,MasterSession session,String token,Pending job,JSONObject images,JSONObject found)throws Exception{
        String soort=found==null?job.soort:found.getJSONObject("document").optString("documentsoort",job.soort);
        String side=MasterSchema.scanSide(soort,found);
        JSONObject out=MasterStore.scanCreate(activity,key,session,token,job.id,soort,images,()->{synchronized(MasterPhotos.this){if(pending!=job||job.dataset!=MasterSession.datasetEpoch()||!job.view.equals(activity.masterView)||!activity.masterWanted||!activity.masterForeground)throw new MasterError(Taal.T("Kaartfoto opslaan geannuleerd"));pending=null;}});
        try{MasterStore.refreshContext(activity,key,session,token);}catch(Throwable e){out.put("contextError",Taal.T("Gegevens opgeslagen; de gedeelde app-context kon niet worden ververst"));}
        MainActivity.appIdentityChanged(MasterAppContext.read(activity).toString());
        out.put("saved",true).put("scan",true).put("person",job.id).put("side",side).put("photoSaved",images.length()>0).put("bothSides",images.length()==2);if(found!=null)out.put("herkend",found);
        return out;
    }
    /** Herkennen vanaf een bewaarde kaartfoto (data:image/jpeg;base64,…). Geeft null als er geen betrouwbare strook op staat. */
    static JSONObject recognizeStored(String data,String today){
        if(data==null||!data.startsWith("data:image/jpeg;base64,"))return null;
        try{return recognize(Base64.decode(data.substring(23),Base64.DEFAULT),today);}catch(Throwable e){return null;}
    }
    static JSONObject recognizeStored(android.content.Context context,String data,String today,java.util.function.BooleanSupplier active){
        return recognizeStored(context,data,today,active,false,null);
    }
    static JSONObject recognizeStored(android.content.Context context,String data,String today,java.util.function.BooleanSupplier active,boolean thorough,JSONObject diag){
        if(data==null||!data.startsWith("data:image/jpeg;base64,"))return null;
        try{return recognize(context,Base64.decode(data.substring(23),Base64.DEFAULT),today,active,thorough,diag);}catch(Throwable e){return null;}
    }
    static JSONObject recognize(android.content.Context context,byte[] bytes,String today,java.util.function.BooleanSupplier active){
        return recognize(context,bytes,today,active,false,null);
    }
    /** Eerst de strook (ID-kaart, paspoort), anders de voorkant van een rijbewijs. diag: hint bij mislukken (alleen ja/nee en aantallen). */
    static JSONObject recognize(android.content.Context context,byte[] bytes,String today,java.util.function.BooleanSupplier active,boolean thorough,JSONObject diag){
        if(!active.getAsBoolean())return null;
        JSONObject mrz=recognize(bytes,today);if(mrz!=null||!active.getAsBoolean())return mrz;
        return RijbewijsOcr.read(context,bytes,today,active,thorough,diag);
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
    static JSONObject recognize(byte[] bytes,String today){
        Bitmap image=null;
        try{
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
            if(o.outWidth<1||o.outHeight<1||o.outWidth>16000||o.outHeight>16000)return null;
            // Zo klein mogelijk inlezen maar niet onder de werkmaat, dan precies op de werkmaat brengen (geheugen en scherpte).
            int max=MrzOcr.MAX_SIDE,sample=1;while(Math.max(o.outWidth,o.outHeight)/(sample*2)>=max)sample*=2;
            o=new BitmapFactory.Options();o.inSampleSize=sample;image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(image==null)return null;
            int side=Math.max(image.getWidth(),image.getHeight());
            if(side>max){Bitmap scaled=Bitmap.createScaledBitmap(image,Math.max(1,image.getWidth()*max/side),Math.max(1,image.getHeight()*max/side),true);if(scaled!=image){image.recycle();image=scaled;}}
            int w=image.getWidth(),h=image.getHeight();int[] px=new int[w*h];image.getPixels(px,0,w,0,0,w,h);image.recycle();image=null;
            for(int i=0;i<px.length;i++){int c=px[i];px[i]=((c>>16&255)*30+(c>>8&255)*59+(c&255)*11)/100;}
            Mrz.Result r=MrzOcr.read(px,w,h);if(r==null||!r.usable())return null;
            return Mrz.fields(r,today);
        }catch(Throwable e){return null;}
        finally{if(image!=null)image.recycle();}
    }
    /** Kaartfoto uit een stroom: lezen en verkleinen (ook gebruikt door de telefoontest). */
    static String normalize(InputStream input)throws Exception{return normalize(read(input));}
    static String normalize(byte[] bytes)throws Exception {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
        if(o.outWidth<1||o.outHeight<1||o.outWidth>16000||o.outHeight>16000||(long)o.outWidth*o.outHeight>80_000_000)throw new MasterError(Taal.T("Deze kaartfoto kan niet worden geopend"));
        // Inlezen net boven 1600 px en dan precies naar 1600 px: scherp genoeg om later nog de strook te herkennen.
        int sample=1;while(Math.max(o.outWidth,o.outHeight)/(sample*2)>=1600)sample*=2;o=new BitmapFactory.Options();o.inSampleSize=sample;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(image==null)throw new MasterError(Taal.T("Deze kaartfoto kan niet worden geopend"));
        {int side=Math.max(image.getWidth(),image.getHeight());if(side>1600){Bitmap scaled=Bitmap.createScaledBitmap(image,Math.max(1,image.getWidth()*1600/side),Math.max(1,image.getHeight()*1600/side),true);if(scaled!=image){image.recycle();image=scaled;}}}
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
