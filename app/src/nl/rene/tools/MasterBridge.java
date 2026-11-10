package nl.rene.tools;

import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native toegang tot centrale gegevens. Iedere vrijgave vereist een eigen ontgrendelde Activity-sessie. */
public class MasterBridge extends BankBridge {
    MasterBridge(MainActivity a){super(a);}
    interface Operation{JSONObject run(SecretKey key)throws Exception;}
    static String why(Throwable e){
        if(e instanceof MasterError)return e.getMessage();
        if(e instanceof android.security.keystore.UserNotAuthenticatedException)return Taal.T("Ontgrendel de stamgegevens opnieuw");
        if(e instanceof android.security.keystore.KeyPermanentlyInvalidatedException)return Taal.T("De toestelbeveiliging is gewijzigd; de stamgegevens blijven vergrendeld");
        return Taal.T("Stamgegevens openen of bewaren lukt niet");
    }
    void reply(String request,String token,JSONObject result){
        try{result.put("request",request).put("token",token);a.js("onMasterReply",result.toString());}catch(Throwable ignored){ }
    }
    void work(String token,String request,Operation operation){
        if(request==null||request.length()>100)return;
        new Thread(()->{
            JSONObject result;
            try{
                if(!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                a.masterSession.require(token);SecretKey key=MasterKeys.authenticated(ctx);result=operation.run(key);
                a.masterSession.require(token);if(!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                a.h.post(a::scheduleMasterExpiry);
            }catch(Throwable e){result=new JSONObject();try{result.put("error",why(e)).put("locked",!a.masterSession.accepts(token));}catch(Exception ignored){ }}
            reply(request,token,result);
        },"masterdata").start();
    }
    @JavascriptInterface public String masterState(){
        try{return new JSONObject().put("open",a.masterSession.isOpen()).put("left",a.masterSession.remaining()).put("data",MasterStore.exists(ctx)).put("secure",Lock.deviceSecure(ctx)).toString();}
        catch(Throwable ignored){return "{\"open\":false}";}
    }
    /**
     * Openen zonder eigen vraag: Stamgegevens vraagt nooit zelf om te ontgrendelen, dat doet het slotscherm van Extra
     * ontgrendelen. Open gaat hij meteen na dat slotscherm (MainActivity.toolSlotNeem), of met extra ontgrendelen uit
     * zolang de sleutel zeker nog werkt (MasterKeys.vanzelf). Anders {vraag:"slot"}, of {vraag:"tijd"} (uit, maar de
     * telefoon is te lang geleden ontgrendeld): dan toont de interface het slotscherm. Niet onder het app-slot, niet
     * tijdens een andere vraag, en als losse app alleen in die van Stamgegevens of PDF (Formulieren).
     */
    @JavascriptInterface public void masterOpen(String request){a.h.post(()->unlock(request));}
    private void unlock(String request){
        if(!a.masterWanted||a.isDestroyed()||a.isFinishing()||!a.masterForeground||(a.isSolo()&&!"masterdata".equals(a.solo)&&!"pdf".equals(a.solo))
                ||Lock.busy()||a.masterAuthPending||!a.isSolo()&&Lock.wouldLock(ctx)){reply(request,"",new JSONObject());return;}
        final long net=a.toolSlotNeem("masterdata");
        if(net<0&&!ToolSlot.eigenUit(ctx,"masterdata")){vraag(request,"slot");return;}
        final long auth=a.masterAuthSerial.incrementAndGet(),dataset=MasterSession.datasetEpoch();final String view=a.masterView;
        new Thread(()->{
            try{
                if(net>=0){open(request,auth,dataset,view,net+MasterSession.MAX);return;}
                long start=SystemClock.elapsedRealtime(),max=MasterKeys.vanzelf(ctx);
                if(max>0){open(request,auth,dataset,view,start+max);return;}
                vraag(request,max==0?"tijd":"slot");
            }catch(Throwable t){vraag(request,"slot");}
        },"master-unlock").start();
    }
    private void vraag(String request,String soort){JSONObject r=new JSONObject();try{r.put("vraag",soort);}catch(Exception ignored){ }reply(request,"",r);}
    /** Na het slotscherm (of zonder vraag): de sessie openen, hooguit tot tot (elapsedRealtime: vóór de sleutel ophoudt). Loopt op een eigen thread. */
    private void open(String request,long auth,long dataset,String view,long tot){
        String token="";JSONObject result=new JSONObject();
        try{
            synchronized(MasterStore.LOCK){
                if(dataset!=MasterSession.datasetEpoch())throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                SecretKey key=MasterKeys.authenticated(ctx);
                synchronized(a.masterSession){
                    if(auth!=a.masterAuthSerial.get()||!view.equals(a.masterView)||!a.masterWanted||!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                    long max=tot-SystemClock.elapsedRealtime();
                    if(max<60_000L)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                    token=a.masterSession.open(max);
                }
                MasterStore.unlock(ctx,key,a.masterSession,token);a.masterSession.require(token);MasterKeys.markReady();
                // Lukt het niet, dan blijft de tekst staan en biedt Aandacht nodig het aan; ontgrendelen gaat gewoon door.
                // De Play-versie heeft geen assistent: daar blijft de tekst in Stamgegevens.
                if(PlayPolicy.allows(ctx,"ai"))try{MasterStore.aboutMove(ctx,key,a.masterSession,token,"vanzelf");}catch(Throwable later){ }
                MasterStore.refreshContext(ctx,key,a.masterSession,token);
            }
            a.masterSession.require(token);if(!a.masterForeground||auth!=a.masterAuthSerial.get())throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
            result.put("opened",true);a.h.post(a::scheduleMasterExpiry);
            JSONObject photo=a.masterPhotos.resume(MasterKeys.authenticated(ctx),a.masterSession,token);if(photo!=null)result.put("photo",photo);
        }catch(Throwable e){a.masterSession.closeIf(token);try{MasterAppContext.invalidate(ctx);}catch(Exception ignored){ }MainActivity.appIdentityChanged("{}");try{result.put("error",why(e));}catch(Exception ignored){ }}
        reply(request,token,result);
    }
    @JavascriptInterface public void masterLock(){a.h.post(()->a.suspendMaster(true));}
    @JavascriptInterface public void masterTouch(String token){try{a.masterSession.touch(token);a.h.post(a::scheduleMasterExpiry);}catch(Throwable ignored){ }}
    @JavascriptInterface public void masterView(boolean on,String view){
        final long generation;
        synchronized(a.masterSecurityGeneration){generation=a.masterSecurityGeneration.incrementAndGet();a.masterWanted=on;a.masterView=on&&view!=null?view:"";if(on)a.masterScreen=true;}
        a.h.post(()->{
            if(a.isDestroyed())return;
            if(on){a.applySecure();a.bankPrive(true);a.js("onMasterPrivacy","{\"view\":"+JSONObject.quote(view)+",\"generation\":"+generation+"}");}
            else a.finishMasterScreen(generation);
        });
    }
    @JavascriptInterface public void masterSafeFrame(String view,long generation){
        a.h.post(()->{if(!a.isDestroyed()&&view!=null&&view.equals(a.masterView))a.masterSafeFrame(view,generation);});
    }
    @JavascriptInterface public void masterList(String token,String source,String role,String request){work(token,request,key->MasterStore.list(ctx,key,a.masterSession,token,source,role));}
    @JavascriptInterface public void masterRead(String token,String id,String source,String role,String request){work(token,request,key->MasterStore.read(ctx,key,a.masterSession,token,id,source,role));}
    @JavascriptInterface public void masterSave(String token,String json,String request){
        work(token,request,key->{if(json==null||json.length()>20000)throw new MasterError(Taal.T("Ongeldige stamgegevens"));JSONObject saved=MasterStore.save(ctx,key,a.masterSession,token,new JSONObject(json));refreshIdentity(key,token,saved);return saved;});
    }
    @JavascriptInterface public void masterScanSave(String token,String json,String request){
        work(token,request,key->{if(json==null||json.length()>20000)throw new MasterError(Taal.T("Ongeldige stamgegevens"));JSONObject saved=MasterStore.scanSave(ctx,key,a.masterSession,token,new JSONObject(json));refreshIdentity(key,token,saved);return saved;});
    }
    void refreshIdentity(SecretKey key,String token,JSONObject result)throws Exception{
        try{MasterStore.refreshContext(ctx,key,a.masterSession,token);}catch(Throwable e){result.put("contextError",Taal.T("Gegevens opgeslagen; de gedeelde app-context kon niet worden ververst"));}
        MainActivity.appIdentityChanged(MasterAppContext.read(ctx).toString());
    }
    @JavascriptInterface public void masterDelete(String token,String id,long revision,String request){work(token,request,key->{JSONObject result=MasterStore.delete(ctx,key,a.masterSession,token,id,revision);refreshIdentity(key,token,result);return result;});}
    @JavascriptInterface public void masterFormRelation(String token,String child,String companion,String role,String request){work(token,request,key->MasterStore.formRelation(ctx,key,a.masterSession,token,child,companion,role));}
    @JavascriptInterface public String appIdentity(){return MasterAppContext.read(ctx).toString();}
    @JavascriptInterface public void masterPhotoPick(String token,String id,long revision,String side,String request){
        if(request==null||request.length()>100)return;
        new Thread(()->{JSONObject result=new JSONObject();try{
            if(!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
            a.masterSession.require(token);SecretKey key=MasterKeys.authenticated(ctx);
            MasterStore.photo(ctx,key,a.masterSession,token,id,revision,side,null,true);a.masterSession.require(token);
            // De kiezer gaat even over de app heen; binnen 2 minuten terug = zonder opnieuw ontgrendelen (MainActivity.masterPickUntil).
            a.masterPhotos.pick(token,id,revision,side,request);result.put("picking",true);
        }catch(Throwable e){try{result.put("error",why(e)).put("locked",!a.masterSession.accepts(token));}catch(Exception ignored){ }}reply(request,token,result);},"master-photo-pick").start();
    }
    /**
     * Document toevoegen begint met een foto, voor een bestaande persoon: kiezen; daarna een nieuw document (soort zoals gekozen,
     * of zoals herkend uit de strook) met de foto als bijlage en voorstellen.
     */
    @JavascriptInterface public void masterScanPick(String token,String person,String soort,String request){
        if(request==null||request.length()>100)return;
        new Thread(()->{JSONObject result=new JSONObject();try{
            if(!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
            a.masterSession.require(token);SecretKey key=MasterKeys.authenticated(ctx);
            MasterStore.scanStart(ctx,key,a.masterSession,token,person);a.masterSession.require(token);
            a.masterPhotos.pick(token,person,0,"scan",request,soort==null?"":soort);result.put("picking",true);
        }catch(Throwable e){try{result.put("error",why(e)).put("locked",!a.masterSession.accepts(token));}catch(Exception ignored){ }}reply(request,token,result);},"master-scan-pick").start();
    }
    /** Gegevens herkennen vanaf de bewaarde kaartfoto van een document (op de telefoon, niets gaat weg). */
    @JavascriptInterface public void masterPhotoScan(String token,String id,long revision,String side,String request){ photoScan(token,id,revision,side,false,request); }
    /** Opnieuw herkennen vanaf de bewaarde foto; grondig = langzamer, met meer bewerkingen van de foto (bij een rijbewijs). */
    @JavascriptInterface public void masterPhotoRescan(String token,String id,long revision,String side,boolean grondig,String request){ photoScan(token,id,revision,side,grondig,request); }
    private void photoScan(String token,String id,long revision,String side,boolean grondig,String request){
        work(token,request,key->{
            JSONObject photo=MasterStore.photoScan(ctx,key,a.masterSession,token,id,revision,side);
            JSONObject diag=new JSONObject(),found;
            try(Voortgang v=Voortgang.van(a,"kaartfoto",1)){
                v.tijd("herkennen",grondig?MasterPhotos.HERKEN_GRONDIG_MS:MasterPhotos.HERKEN_MS);
                found=MasterPhotos.recognizeStored(ctx,photo.optString("image"),Regio.dayKey(ctx,System.currentTimeMillis()),()->a.masterSession.accepts(token)&&a.masterForeground&&a.masterWanted,grondig,diag);
            }
            JSONObject out=new JSONObject().put("id",id).put("side",side).put("diag",diag);if(found!=null)out.put("herkend",found);return out;
        });
    }
    @JavascriptInterface public void masterPhotoRead(String token,String id,long revision,String side,String request){work(token,request,key->MasterStore.photo(ctx,key,a.masterSession,token,id,revision,side,null,true));}
    /**
     * ID-kopie maken vanaf de kaartfoto's van een identiteitsdocument: de foto's gaan alleen in het geheugen klaar te staan voor
     * het scherm ID-kopie afschermen (MainActivity.idMaster), dat ze binnen een minuut ophaalt (idCopyTakeMaster); de
     * foto's gaan nooit door de interface. Antwoord {pages, soort}.
     */
    @JavascriptInterface public void masterIdCopySource(String token,String id,long revision,String request){
        work(token,request,key->{
            JSONObject r=MasterStore.idCopySource(ctx,key,a.masterSession,token,id,revision);JSONArray sides=r.getJSONArray("sides");
            java.util.List<byte[]> images=new java.util.ArrayList<>();
            for(int i=0;i<sides.length();i++){String v=sides.getJSONObject(i).getString("image");images.add(android.util.Base64.decode(v.substring(v.indexOf(',')+1),android.util.Base64.DEFAULT));}
            a.idMasterTake(images);
            return new JSONObject().put("pages",images.size()).put("soort",r.optString("soort")).put("sides",images.size()==2?"front,back":sides.getJSONObject(0).getString("side"));
        });
    }
    @JavascriptInterface public void masterPhotoDelete(String token,String id,long revision,String side,String request){work(token,request,key->MasterStore.photo(ctx,key,a.masterSession,token,id,revision,side,null,false));}
    @JavascriptInterface public void masterPhotoCancel(){a.masterPhotos.cancel();}
    @JavascriptInterface public void masterUse(String token,String id,long revision,String source,String role,String fields,boolean consent,String request){
        work(token,request,key->{
            if(fields==null||fields.length()>1000)throw new MasterError(Taal.T("Kies welke velden je wilt overnemen"));
            JSONObject r=MasterStore.use(ctx,key,a.masterSession,token,id,revision,source,role,new JSONArray(fields),consent);
            String grant=java.util.UUID.randomUUID().toString();
            synchronized(a.masterSession){a.masterSession.require(token);a.masterGrants.put(grant,token+"\n"+source);}
            r.put("grant",grant);
            return r;
        });
    }
    /** Vaste formuliergegevens opbouwen uit de actuele stamgegevens; daarna net als masterUse via een eenmalige vrijgave binden. */
    @JavascriptInterface public void masterFormProfile(String token,String id,long revision,boolean consent,String request){
        work(token,request,key->{
            JSONObject r=MasterStore.formProfile(ctx,key,a.masterSession,token,id,revision,consent);
            String grant=java.util.UUID.randomUUID().toString();
            synchronized(a.masterSession){a.masterSession.require(token);a.masterGrants.put(grant,token+"\nformulieren");}
            return r.put("grant",grant);
        });
    }
    @JavascriptInterface public void masterFormExpiry(String token,String id,long revision,String van,String tot,boolean consent,String request){work(token,request,key->MasterStore.formExpiry(ctx,key,a.masterSession,token,id,revision,van,tot,consent));}
    @JavascriptInterface public boolean masterApplied(String token,String grant){
        synchronized(a.masterSession){
            if(!a.masterForeground||!a.masterSession.accepts(token))return false;
            String value=a.masterGrants.remove(grant);if(value==null)return false;
            if(value.equals(token+"\nformulieren")){a.masterFormBound=true;return true;}
            if(value.equals(token+"\nidcopy")){a.masterIdBound=true;return true;}
            return false;
        }
    }
    /** Een contact kiezen om aan een persoon te koppelen; het antwoord komt via onMasterContactPicked (zie MasterContacts.Pick). */
    @JavascriptInterface public void masterContactPick(String token,String request){
        if(request==null||request.length()>100)return;
        a.h.post(()->{
            try{
                if(Updater.isPlayPackage(ctx.getPackageName()))throw new MasterError(PlayPolicy.UNAVAILABLE);
                if(!a.masterForeground||!a.masterSession.accepts(token))throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                a.masterContacts.start(token,request);
            }catch(Throwable e){try{a.js("onMasterContactPicked",new JSONObject().put("request",request).put("token",token).put("error",why(e)).toString());}catch(Exception ignored){ }}
        });
    }
    /** Vanaf het visitekaartje: bel (kiesscherm, Tools belt zelf niet), bericht of contact (Contacten openen). */
    @JavascriptInterface public void masterContactAction(String token,String id,String what,String request){
        work(token,request,key->{
            JSONObject r=MasterStore.contactUse(ctx,key,a.masterSession,token,id,what);
            android.content.Intent i=MasterContacts.intent(ctx,r.optString("contact"),r.optString("telefoon"),what);
            if(i==null)throw new MasterError("contact".equals(what)?Taal.T("Deze persoon is niet aan een contact gekoppeld"):Taal.T("Geen telefoonnummer bekend"));
            a.h.post(()->{try{a.startActivity(i);}catch(Throwable e){android.widget.Toast.makeText(a,Taal.tx(a,"Geen app gevonden om dit te openen"),android.widget.Toast.LENGTH_SHORT).show();}});
            return new JSONObject().put("ok",true);
        });
    }
    /** Tekst over jezelf naar Over mij bij de assistent ("verplaats") of weghalen ("wis"). */
    @JavascriptInterface public void masterAboutMove(String token,String mode,String request){
        work(token,request,key->{if(!"wis".equals(mode)&&!PlayPolicy.allows(ctx,"ai"))throw new MasterError(PlayPolicy.UNAVAILABLE);JSONObject r=MasterStore.aboutMove(ctx,key,a.masterSession,token,mode);if(r.optBoolean("moved"))refreshIdentity(key,token,r);return r;});
    }
    @JavascriptInterface public void masterAudit(String token,long before,String request){work(token,request,key->MasterStore.audit(ctx,key,a.masterSession,token,before));}
    @JavascriptInterface public void masterReleaseForm(){a.masterFormBound=false;}
    @JavascriptInterface public String pdfFormAi(String token,String json,String request){return a.pdfFormAi.start(token,json,request);}
    @JavascriptInterface public void pdfFormAiCancel(String request){a.pdfFormAi.cancel(request);}
    void resetError(String request){JSONObject r=new JSONObject();try{r.put("error",Taal.T("Wissen geannuleerd"));}catch(Exception ignored){}reply(request,"",r);}
    @JavascriptInterface public void masterReset(String confirmation,String request){
        if(!"WISSEN".equals(confirmation)){resetError(request);return;}
        a.h.post(()->{
            if(!a.masterWanted||!a.masterForeground||Lock.busy()){resetError(request);return;}
            final String view=a.masterView;final long auth=a.masterAuthSerial.incrementAndGet(),dataset=MasterSession.datasetEpoch();a.masterAuthPending=true;
            Lock.prompt(a,Taal.t(ctx,"Alle stamgegevens en activiteiten wissen"),(ok,message)->{
                a.masterAuthPending=false;if(!ok){JSONObject r=new JSONObject();try{r.put("error",Taal.T("Wissen geannuleerd"));}catch(Exception ignored){ }reply(request,"",r);return;}
                a.afterMasterAuth(()->new Thread(()->{
                    JSONObject r=new JSONObject();boolean attempted=false,complete=false;
                    try{
                        MasterKeys.proveReset();
                        synchronized(MasterStore.LOCK){synchronized(a.masterSession){if(dataset!=MasterSession.datasetEpoch()||!a.masterForeground||auth!=a.masterAuthSerial.get()||!view.equals(a.masterView))throw new MasterError(Taal.T("Wissen geannuleerd"));a.masterSession.close();attempted=true;MasterStore.reset(ctx);}}
                        complete=true;r.put("reset",true);
                        a.toolSlotNet("masterdata"); // net bevestigd: het lege Stamgegevens opent daarna zonder nog een vraag
                    }catch(Throwable e){try{r.put("error",why(e));}catch(Exception ignored){ }}finally{if(attempted)MainActivity.masterResetAll(complete);}reply(request,"",r);
                },"master-reset").start());
            });
        });
    }
}
