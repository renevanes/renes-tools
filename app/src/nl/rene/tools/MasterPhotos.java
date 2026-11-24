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
        String reviewId,reviewToken;JSONObject images,found;
        Pending(String id,long revision,String side,String request,String view,String soort){this.id=id;this.revision=revision;this.side=side;this.request=request;this.view=view;this.soort=soort;this.dataset=MasterSession.datasetEpoch();}
    }
    synchronized void pick(String token,String id,long revision,String side,String request)throws Exception {pick(token,id,revision,side,request,"");}
    /** soort: bij een nieuw document (side "scan") de soort die Rene koos; wat uit de strook herkend wordt, gaat voor. */
    synchronized void pick(String token,String id,long revision,String side,String request,String soort)throws Exception {
        if(pending!=null)throw new MasterError(Taal.T("Er staat al een kaartfoto klaar; ontgrendel om die op te slaan"));
        if(!side.equals("front")&&!side.equals("back")&&!side.equals("scan")&&!side.equals("pair"))throw new MasterError(Taal.T("Kies de voorzijde of achterzijde"));
        if(soort==null||!SOORTEN.contains(soort))throw new MasterError(Taal.T("Kies wat voor document het is"));
        Pending job=new Pending(id,revision,side,request,activity.masterView,soort);pending=job;
        activity.h.post(()->{
            synchronized(MasterPhotos.this){if(pending!=job)return;}
            if(!activity.masterForeground||!activity.masterSession.accepts(token)||!job.view.equals(activity.masterView)){clear(job);notify(job,false,Taal.T("Ontgrendel de stamgegevens opnieuw"));return;}
            // Zolang de kiezer open is (hooguit 2 minuten) niet op slot: zie MainActivity.masterPickUntil.
            if(activity.masterSession.accepts(token)){activity.masterPickReturned=false;activity.masterPickKind="photo";activity.masterPickUntil=android.os.SystemClock.elapsedRealtime()+MainActivity.MASTER_PICK_MS;activity.h.postDelayed(activity::masterPickExpired,MainActivity.MASTER_PICK_MS+500);}
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Een kaartfoto: alleen echte bestanden. Een document: ook een Google-document (een "virtueel" bestand zonder
            // CATEGORY_OPENABLE); dat haalt resume() als Word-bestand of PDF op.
            if("scan".equals(job.side))intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "image/*","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document",GOOGLE_DOC
            });
            else if("pair".equals(job.side))intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document",GOOGLE_DOC
            });
            else intent.addCategory(Intent.CATEGORY_OPENABLE);
            try{activity.startActivityForResult(intent,REQUEST);}catch(Throwable e){activity.masterPickUntil=0;clear(job);notify(job,false,Taal.T("Bestandskiezer niet beschikbaar"));}
        });
    }
    synchronized void clear(Pending job){if(pending==job)pending=null;notifyAll();}
    synchronized void cancel(){pending=null;notifyAll();}
    synchronized void cancelReview(){if(pending!=null&&pending.reviewId!=null)pending=null;notifyAll();}
    /**
     * Uitkomst van lezen en herkennen, alleen in het geheugen (nooit in een bestand): de genormaliseerde foto(’s) en wat er
     * herkend is, of de fout. Weg met de klaargezette keuze (cancel, vergrendelen met de hand, de app sluiten).
     */
    static final class Uitkomst {
        String image,error;JSONObject images,found,diag;
    }
    /** Hoe lang een herkende foto of document in het geheugen wacht op ontgrendelen; daarna opnieuw kiezen. */
    static final long BEWAAR_MS=15*60_000L;
    /** Hoe lang ontgrendelen hooguit wacht op herkennen dat nog loopt (lezen, een Google-document ophalen, zoeken). */
    static final long WACHT_MS=60_000L*2+MasterDocumentImages.SEARCH_MS+30_000; // twee keer EXPORT_MS (ophalen en lezen), zoeken en marge
    private JSONObject review(Pending job,String token,JSONObject images,JSONObject found)throws Exception{
        synchronized(activity.masterSession){
            activity.masterSession.require(token);
            synchronized(this){
                if(pending!=job||!activity.masterForeground||!activity.masterWanted||
                    !job.view.equals(activity.masterView)||job.dataset!=MasterSession.datasetEpoch())
                    throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                job.reviewId=java.util.UUID.randomUUID().toString();
                job.reviewToken=token;
                job.images=images;
                job.found=found;
                job.uri=null;
                return new JSONObject().put("reviewId",job.reviewId).put("images",images).put("herkend",found!=null);
            }
        }
    }
    JSONObject saveReview(SecretKey key,MasterSession session,String token,String id,boolean swap)throws Exception{
        Pending job;
        JSONObject images;
        synchronized(this){
            job=pending;
            if(job==null||id==null||!id.equals(job.reviewId)||!token.equals(job.reviewToken)||job.images==null)
                throw new MasterError(Taal.T("Kies het bestand opnieuw."));
            images=job.images;
            job.images=null;
        }
        try{
            if(swap)images=new JSONObject().put("front",images.getString("back")).put("back",images.getString("front"));
            return scanDocument(key,session,token,job,images,job.found);
        }finally{clear(job);}
    }
    void notify(Pending job,boolean picked,String error){try{JSONObject out=new JSONObject().put("request",job.request).put("picked",picked).put("direct",picked&&activity.masterPicking());if(error!=null)out.put("error",error);activity.js("onMasterPhotoPicked",out.toString());}catch(Exception ignored){ }}
    void result(int result,Intent data){
        Pending job;synchronized(this){job=pending;}if(job==null)return;
        Uri uri=result==Activity.RESULT_OK&&data!=null?data.getData():null;
        if(uri==null){clear(job);notify(job,false,null);return;}
        try{if(!PdfTools.grantedBySender(data,java.util.Collections.singletonList(uri)))throw new Exception();PdfTools.checkUri(activity,uri);
            synchronized(this){if(pending!=job)return;job.uri=uri;}notify(job,true,null);
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
        boolean doc="scan".equals(job.side)||"pair".equals(job.side);
        job.virtual=doc&&virtual(job.uri);
        if(!job.virtual){
            try{return v.open(activity,job.uri);}
            catch(java.io.FileNotFoundException|SecurityException e){if(!doc)throw e;job.virtual=true;}
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
        activity.h.postDelayed(()->{synchronized(MasterPhotos.this){if(pending==job&&job.klaar!=null&&job.reviewId==null){pending=null;notifyAll();}}},BEWAAR_MS+1000);
        return u;
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
            if("pair".equals(job.side)){
                v.tijd("herkennen",MasterDocumentImages.SEARCH_MS);
                MasterDocumentImages.Pair pair=null;
                JSONObject stats=new JSONObject();
                try{pair=MasterDocumentImages.findPair(activity,bytes,today,job.soort,active,stats);}
                catch(java.io.IOException|IllegalArgumentException|SecurityException e){stats.put("reden","niet te openen");}
                catch(Throwable e){stats.put("reden","fout "+e.getClass().getSimpleName());} // (OutOfMemoryError e.d.: ook in het logboek)
                // Alleen vaste woorden, aantallen en ja/nee: nooit namen, nummers of tekst uit het document.
                App.log(activity,"STAMGEGEVENS","beide zijden ("+stats.optString("bron","?")+(job.virtual?", google":"")+"): "
                    +(pair==null?"niet gelukt":pair.fields==null?"zonder herkenning":"herkend")+", reden "+stats.optString("reden","-")
                    +", foto's "+stats.optInt("fotos")+(stats.optBoolean("pagina")?", pagina's als zijde":"")+(stats.has("paginas")?", pagina's "+stats.optInt("paginas"):"")+(stats.optBoolean("gesplitst")?", in tweeën":"")
                    +", herkend "+(stats.optBoolean("herkendA")?"ja":"nee")+"/"+(stats.optBoolean("herkendB")?"ja":"nee"));
                if(pair==null&&"verschillend".equals(stats.optString("reden")))throw new MasterError(Taal.T("De twee zijden lijken van verschillende ID-bewijzen te zijn. Kies een bestand met de voor- en achterkant van hetzelfde ID-bewijs."));
                if(pair==null&&("niet te openen".equals(stats.optString("reden"))||stats.optString("reden").startsWith("fout ")))throw new MasterError(Taal.T("Dit document kan niet worden geopend. Kies een leesbare PDF of een Word-document (.docx)."));
                if(pair==null)throw new MasterError(Taal.T("Geen twee zijden gevonden. Kies een PDF met beide zijden (op één of twee pagina's) of een Word-bestand of Google-document met twee foto's, of voeg de zijden apart toe."));
                u.images=new JSONObject().put("front",normalize(pair.front)).put("back",normalize(pair.back));u.found=pair.fields;
                u.diag=new JSONObject().put("herkend",pair.fields!=null).put("pagina",pair.pagina);
                return u;
            }
            boolean document="scan".equals(job.side)&&(MasterDocumentImages.pdf(bytes)||MasterDocumentImages.zip(bytes));
            JSONObject found;
            if(document){
                v.tijd("herkennen",MasterDocumentImages.SEARCH_MS);
                MasterDocumentImages.Match match;
                JSONObject stats=new JSONObject();
                String soortBestand=MasterDocumentImages.pdf(bytes)?"pdf":"word";
                try{match=MasterDocumentImages.find(activity,bytes,today,active,stats,"rijbewijs".equals(job.soort));}
                catch(java.io.IOException|IllegalArgumentException|SecurityException e){
                    App.log(activity,"STAMGEGEVENS","document ("+soortBestand+(job.virtual?", google":"")+"): niet te openen, "+e.getClass().getSimpleName());
                    throw new MasterError(Taal.T("Dit document kan niet worden geopend. Kies een leesbare PDF of een Word-document (.docx)."));
                }
                // Alleen als het niet lukt, en alleen vaste woorden en aantallen: nooit namen, nummers of tekst uit het document.
                if(match==null)App.log(activity,"STAMGEGEVENS","document ("+soortBestand+(job.virtual?", google":"")+"): niet herkend"
                    +("pdf".equals(soortBestand)?", foto's "+stats.optInt("fotos")+", "+stats.optInt("bekeken")+" van "+stats.optInt("paginas")+" pagina's, "+stats.optInt("stukken")+" stukken":"")
                    +(stats.optBoolean("teLang")?", tijd op":""));
                if(match==null&&!active.getAsBoolean())throw new MasterError(Taal.T("Kies het bestand opnieuw."));
                if(match==null&&stats.optBoolean("teLang"))throw new MasterError(Taal.T("Geen ID-bewijs herkend: het zoeken duurde te lang. Kies een PDF met alleen de pagina van het ID-bewijs, of een foto van de kaart."));
                if(match==null)throw new MasterError(Taal.T("Geen ID-bewijs herkend in dit document. Kies een document met een scherpe foto van het ID-bewijs, of een foto van de kaart zelf."));
                bytes=match.image;
                found=match.fields;
            }else{
                v.tijd("herkennen",HERKEN_MS);
                found=recognize(activity,bytes,today,active,false,diag);
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
        if("pair".equals(job.side))return review(job,token,u.images,u.found).put("pagina",u.diag!=null&&u.diag.optBoolean("pagina"));
        // Herkennen uit foto (bij een persoon): een nieuw document met deze foto als bijlage, daarna voorstellen.
        if("scan".equals(job.side)){
            String type=u.found==null?job.soort:u.found.getJSONObject("document").optString("documentsoort",job.soort);
            return scanDocument(key,session,token,job,new JSONObject().put(MasterSchema.scanSide(type,u.found),u.image),u.found).put("diag",u.diag);
        }
        JSONObject result=MasterStore.photo(activity,key,session,token,job.id,job.revision,job.side,u.image,false,()->{synchronized(MasterPhotos.this){if(pending!=job||job.dataset!=MasterSession.datasetEpoch()||!job.view.equals(activity.masterView)||!activity.masterWanted||!activity.masterForeground)throw new MasterError(Taal.T("Kaartfoto opslaan geannuleerd"));pending=null;}});
        result.put("saved",true).put("diag",u.diag);if(u.found!=null)result.put("herkend",u.found);
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
