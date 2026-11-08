package nl.rene.tools;

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
    @JavascriptInterface public void masterUnlock(String request){
        a.h.post(()->{
            if(!a.masterWanted||a.isDestroyed()||a.isFinishing()||!a.masterForeground||(a.isSolo()&&!"masterdata".equals(a.solo)&&!"pdf".equals(a.solo))){JSONObject e=new JSONObject();try{e.put("error",Taal.T("Ontgrendel de stamgegevens opnieuw"));}catch(Exception ignored){ }reply(request,"",e);return;}
            if(Lock.busy()){JSONObject e=new JSONObject();try{e.put("error",Taal.T("Er staat al een vraag om te ontgrendelen open"));}catch(Exception ignored){ }reply(request,"",e);return;}
            final long auth=a.masterAuthSerial.incrementAndGet(),dataset=MasterSession.datasetEpoch();final String view=a.masterView;
            a.masterAuthPending=true;
            Lock.prompt(a,Taal.t(ctx,"Stamgegevens ontgrendelen"),(ok,message)->{
                a.masterAuthPending=false;
                if(!ok){JSONObject e=new JSONObject();try{e.put("error",Taal.T("Stamgegevens niet ontgrendeld"));}catch(Exception ignored){ }reply(request,"",e);return;}
                a.afterMasterAuth(()->new Thread(()->{
                    String token="";JSONObject result=new JSONObject();
                    try{
                        synchronized(MasterStore.LOCK){
                            if(dataset!=MasterSession.datasetEpoch())throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                            SecretKey key=MasterKeys.authenticated(ctx);
                            synchronized(a.masterSession){
                                if(auth!=a.masterAuthSerial.get()||!view.equals(a.masterView)||!a.masterWanted||!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                                token=a.masterSession.open();
                            }
                            MasterStore.unlock(ctx,key,a.masterSession,token);a.masterSession.require(token);MasterKeys.markReady();
                            MasterStore.refreshContext(ctx,key,a.masterSession,token);
                        }
                        a.masterSession.require(token);if(!a.masterForeground||auth!=a.masterAuthSerial.get())throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
                        result.put("opened",true);a.h.post(a::scheduleMasterExpiry);
                        JSONObject photo=a.masterPhotos.resume(MasterKeys.authenticated(ctx),a.masterSession,token);if(photo!=null)result.put("photo",photo);
                    }catch(Throwable e){a.masterSession.closeIf(token);try{MasterAppContext.invalidate(ctx);}catch(Exception ignored){ }MainActivity.appIdentityChanged("{}");try{result.put("error",why(e));}catch(Exception ignored){ }}
                    reply(request,token,result);
                },"master-unlock").start());
            });
        });
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
            // De kiezer verlaat bewust de app en sluit de sessie. Geen nacontrole op die oude sessie.
            a.masterPhotos.pick(token,id,revision,side,request);result.put("picking",true);
        }catch(Throwable e){try{result.put("error",why(e)).put("locked",!a.masterSession.accepts(token));}catch(Exception ignored){ }}reply(request,token,result);},"master-photo-pick").start();
    }
    /** Herkennen uit foto, voor een bestaande persoon: kiezen; na ontgrendelen een nieuw document met de foto als bijlage en voorstellen. */
    @JavascriptInterface public void masterScanPick(String token,String person,String request){
        if(request==null||request.length()>100)return;
        new Thread(()->{JSONObject result=new JSONObject();try{
            if(!a.masterForeground)throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));
            a.masterSession.require(token);SecretKey key=MasterKeys.authenticated(ctx);
            MasterStore.scanStart(ctx,key,a.masterSession,token,person);a.masterSession.require(token);
            a.masterPhotos.pick(token,person,0,"scan",request);result.put("picking",true);
        }catch(Throwable e){try{result.put("error",why(e)).put("locked",!a.masterSession.accepts(token));}catch(Exception ignored){ }}reply(request,token,result);},"master-scan-pick").start();
    }
    /** Gegevens herkennen vanaf de bewaarde kaartfoto van een document (op de telefoon, niets gaat weg). */
    @JavascriptInterface public void masterPhotoScan(String token,String id,long revision,String side,String request){
        work(token,request,key->{
            JSONObject photo=MasterStore.photoScan(ctx,key,a.masterSession,token,id,revision,side);
            JSONObject found=MasterPhotos.recognizeStored(photo.optString("image"),Regio.dayKey(ctx,System.currentTimeMillis()));
            JSONObject out=new JSONObject().put("id",id).put("side",side);if(found!=null)out.put("herkend",found);return out;
        });
    }
    @JavascriptInterface public void masterPhotoRead(String token,String id,long revision,String side,String request){work(token,request,key->MasterStore.photo(ctx,key,a.masterSession,token,id,revision,side,null,true));}
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
                    }catch(Throwable e){try{r.put("error",why(e));}catch(Exception ignored){ }}finally{if(attempted)MainActivity.masterResetAll(complete);}reply(request,"",r);
                },"master-reset").start());
            });
        });
    }
}
