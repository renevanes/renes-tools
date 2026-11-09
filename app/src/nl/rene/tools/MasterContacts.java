package nl.rene.tools;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Een persoon in Stamgegevens gekoppeld aan een contact. Bij het koppelen neemt Rene naam, telefoon, e-mail, adres en
 * verjaardag één keer over; daarna komen telefoon en e-mail steeds vers uit Contacten (bij het openen, bellen en
 * invullen van een formulier). Alleen het contact dat Rene zelf kiest; er wordt niets naar Contacten geschreven en
 * niets gaat de telefoon uit.
 */
final class MasterContacts {
    private MasterContacts(){ }
    static final int REQUEST=95,PERMISSION=96;
    static final String NAME="vnd.android.cursor.item/name",NICK="vnd.android.cursor.item/nickname",PHONE="vnd.android.cursor.item/phone_v2",
            EMAIL="vnd.android.cursor.item/email_v2",POSTAL="vnd.android.cursor.item/postal-address_v2",EVENT="vnd.android.cursor.item/contact_event";
    /** Tussenvoegsels die Contacten soms als tweede voornaam bewaart ("Rene" "van" "Es"). */
    static final List<String> TUSSEN=Arrays.asList("van","de","der","den","het","ter","ten","te","in","'t","van de","van der","van den","van het","in 't","in de","op de","op den","aan de","uit de","uit den","de la","du","da","di","von","zu","le","la");
    static final List<String> ACTIONS=Arrays.asList("bel","bericht","contact");

    static boolean validKey(String key){return key!=null&&!key.isEmpty()&&key.length()<=200&&key.matches("[A-Za-z0-9._%:+\\-]+");}
    static boolean allowed(Context c){return c.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED;}
    static String cut(String s){s=s==null?"":s.replace('\n',' ').replace('\r',' ').replaceAll("[\\x00-\\x1f]","").replaceAll(" {2,}"," ").trim();return s.length()>240?s.substring(0,240).trim():s;}

    /**
     * De velden van een persoon uit de rijen van één contact: elke rij is {mimetype, data1, …, data10}, de belangrijkste
     * (primaire) eerst. Zuiver (getest in MasterContactsTest).
     */
    static JSONObject fields(List<String[]> rows)throws Exception{
        JSONObject out=new JSONObject();String phone="",mobile="",display="";JSONArray phones=new JSONArray(),emails=new JSONArray();
        for(String[] r:rows){
            if(r==null||r.length<2||r[0]==null)continue;String mime=r[0];
            if(NAME.equals(mime)&&!out.has("naam")&&!out.has("voornamen")){
                String given=cut(col(r,2)),family=cut(col(r,3)),middle=cut(col(r,5));display=cut(col(r,1));
                if(!middle.isEmpty()&&TUSSEN.contains(middle.toLowerCase(java.util.Locale.ROOT)))family=(middle+" "+family).trim();
                else if(!middle.isEmpty())given=(given+" "+middle).trim();
                if(given.isEmpty()&&family.isEmpty()&&!display.isEmpty())family=display;
                if(!given.isEmpty())out.put("voornamen",cut(given));if(!family.isEmpty())out.put("naam",cut(family));
            }
            else if(NICK.equals(mime)&&!out.has("roepnaam")&&!cut(col(r,1)).isEmpty())out.put("roepnaam",cut(col(r,1)));
            else if(PHONE.equals(mime)){String n=cut(col(r,1));if(n.isEmpty())continue;phones.put(n);if(phone.isEmpty())phone=n;if(mobile.isEmpty()&&"2".equals(col(r,2)))mobile=n;}
            else if(EMAIL.equals(mime)){String e=cut(col(r,1));if(!e.isEmpty()){emails.put(e);if(!out.has("email"))out.put("email",e);}}
            else if(POSTAL.equals(mime)&&!out.has("adres")&&!out.has("plaats")){
                String street=cut(col(r,4)),city=cut(col(r,7)),code=cut(col(r,9)),country=cut(col(r,10));
                if(street.isEmpty()&&city.isEmpty())street=cut(col(r,1));
                if(!street.isEmpty())out.put("adres",street);if(!code.isEmpty())out.put("postcode",code);if(!city.isEmpty())out.put("plaats",city);if(!country.isEmpty())out.put("land",country);
            }
            else if(EVENT.equals(mime)&&"3".equals(col(r,2))&&!out.has("geboortedatum")){String d=cut(col(r,1));if(MasterSchema.validDate(d))out.put("geboortedatum",d);}
        }
        String best=mobile.isEmpty()?phone:mobile;if(!best.isEmpty())out.put("telefoon",best);
        if(phones.length()>1)out.put("telefoons",phones);if(emails.length()>1)out.put("emails",emails);
        if(!display.isEmpty())out.put("weergave",display);
        return out;
    }
    static String col(String[] r,int i){return i<r.length&&r[i]!=null?r[i]:"";}

    /** Telefoon en e-mail uit het contact gaan voor op wat er (van het koppelen) in Stamgegevens staat. Zuiver. */
    static JSONObject overlay(JSONObject record,JSONObject live)throws Exception{
        if(live==null||!live.optBoolean("gevonden"))return record;
        JSONObject copy=new JSONObject(record.toString()),f=copy.getJSONObject("fields");
        for(String k:new String[]{"telefoon","email"})if(!live.optString(k).isEmpty())f.put(k,live.getString(k));
        return copy;
    }
    static JSONObject withLive(Context c,JSONObject record)throws Exception{
        if(!"persoon".equals(record.optString("type")))return record;String key=record.getJSONObject("fields").optString("contact");
        return key.isEmpty()?record:overlay(record,live(c,key));
    }

    static List<String[]> rows(Context c,long contact){
        List<String[]> out=new ArrayList<>();
        String[] cols={ContactsContract.Data.MIMETYPE,"data1","data2","data3","data4","data5","data6","data7","data8","data9","data10"};
        try(Cursor cu=c.getContentResolver().query(ContactsContract.Data.CONTENT_URI,cols,ContactsContract.Data.CONTACT_ID+"=?",new String[]{Long.toString(contact)},
                ContactsContract.Data.IS_SUPER_PRIMARY+" DESC,"+ContactsContract.Data.IS_PRIMARY+" DESC")){
            while(cu!=null&&cu.moveToNext()&&out.size()<200){String[] r=new String[cols.length];for(int i=0;i<cols.length;i++)try{r[i]=cu.getString(i);}catch(Throwable ignored){r[i]="";}out.add(r);}
        }
        return out;
    }
    /** Het contact met deze opzoeksleutel: {toegang, gevonden, weergave, telefoon, email, telefoons, emails}. */
    static JSONObject live(Context c,String key){
        JSONObject out=new JSONObject();
        try{
            out.put("toegang",allowed(c)).put("gevonden",false);if(!validKey(key)||!allowed(c))return out;
            Uri uri=ContactsContract.Contacts.lookupContact(c.getContentResolver(),Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI,key));
            if(uri==null)return out;
            JSONObject f=fields(rows(c,ContentUris.parseId(uri)));
            out.put("gevonden",true);for(String k:new String[]{"weergave","telefoon","email","telefoons","emails"})if(f.has(k))out.put(k,f.get(k));
        }catch(Throwable ignored){ }
        return out;
    }
    /** Het gekozen contact: opzoeksleutel en de velden om over te nemen. */
    static JSONObject read(Context c,Uri picked)throws Exception{
        String key="";long id=-1;
        try(Cursor cu=c.getContentResolver().query(picked,new String[]{ContactsContract.Contacts._ID,ContactsContract.Contacts.LOOKUP_KEY},null,null,null)){
            if(cu!=null&&cu.moveToFirst()){id=cu.getLong(0);key=cu.getString(1);}
        }
        if(id<0||!validKey(key))throw new MasterError(Taal.T("Dit contact kan niet worden gekoppeld"));
        return new JSONObject().put("contact",key).put("fields",fields(rows(c,id)));
    }
    /** Bellen of een bericht: het nummer uit het contact (of wat in Stamgegevens staat); Contacten openen bij het gekoppelde contact. */
    static Intent intent(Context c,String key,String stored,String what){
        if("contact".equals(what))return validKey(key)?new Intent(Intent.ACTION_VIEW,Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI,key)):null;
        String number=key==null||key.isEmpty()?"":live(c,key).optString("telefoon");if(number.isEmpty())number=stored==null?"":stored;
        number=dialable(number);if(number.isEmpty())return null;
        return "bel".equals(what)?new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",number,null)):new Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",number,null));
    }

    /** Alleen cijfers met hooguit een + vooraan: geen codes (*#…#) of andere tekens uit een opgeslagen nummer in de telefoon-app. */
    static String dialable(String s){
        String t=s==null?"":s.trim(),d=t.replaceAll("[^0-9]","");
        if(t.startsWith("00")&&d.length()>2)d="+"+d.substring(2);else if(t.startsWith("+")&&!d.isEmpty())d="+"+d;
        return d.length()>16?"":d;
    }

    /**
     * Een contact kiezen vanuit Stamgegevens: de kiezer (en zo nodig eerst de vraag om toegang tot Contacten) gaat even over
     * de app heen, binnen het keuzevenster van 2 minuten (MainActivity.masterPickUntil) zonder opnieuw ontgrendelen.
     */
    static final class Pick {
        final MainActivity a;
        private String token="",request="",view="",state="";private Uri uri;private long dataset;private boolean active;
        Pick(MainActivity a){this.a=a;}
        /** Op de hoofdthread. */
        void start(String token,String request)throws Exception{
            synchronized(this){
                if(active)throw new MasterError(Taal.T("Er wordt al een contact gekozen"));
                active=true;this.token=token;this.request=request;view=a.masterView;dataset=MasterSession.datasetEpoch();state="";uri=null;
            }
            if(allowed(a))launch();
            else{window("perm");try{a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},PERMISSION);}catch(Throwable e){a.masterPickUntil=0;a.masterPickKind="";finish("",Taal.T("Geen toegang tot Contacten"),null);}}
        }
        private void window(String kind){
            a.masterPickReturned=false;a.masterPickKind=kind;a.masterPickUntil=android.os.SystemClock.elapsedRealtime()+MainActivity.MASTER_PICK_MS;
            a.h.postDelayed(a::masterPickExpired,MainActivity.MASTER_PICK_MS+500);
        }
        private void launch(){
            window("contact");
            try{a.startActivityForResult(new Intent(Intent.ACTION_PICK,ContactsContract.Contacts.CONTENT_URI),REQUEST);}
            catch(Throwable e){a.masterPickUntil=0;a.masterPickKind="";finish("",Taal.T("Geen contacten-app gevonden"),null);}
        }
        /** Antwoord op de vraag om toegang. Zonder venster (eerder geweigerd en niet meer vragen) gaat het meteen verder. */
        void permissionResult(boolean granted){
            synchronized(this){if(!active)return;state=granted?"granted":"denied";}
            if(a.masterForeground){a.masterPickUntil=0;a.masterPickKind="";a.masterPickReturned=false;continueAfterPick();}
        }
        /** Terug uit de contactkiezer; ook Annuleren telt als terug. */
        boolean result(int code,Intent data){
            synchronized(this){if(!active)return false;Uri u=code==android.app.Activity.RESULT_OK&&data!=null?data.getData():null;uri=u;state=u==null?"cancel":"picked";}
            return true;
        }
        synchronized void cancel(){active=false;uri=null;state="";}
        /** Op de hoofdthread, binnen het venster terug in de app (of meteen na een geweigerde vraag zonder venster). */
        void continueAfterPick(){
            String s;Uri u;String t;
            synchronized(this){if(!active)return;s=state;u=uri;t=token;}
            if(!a.masterSession.accepts(t)||!view.equals(a.masterView)||dataset!=MasterSession.datasetEpoch()){finish("",Taal.T("Ontgrendel de stamgegevens opnieuw"),null);return;}
            if("granted".equals(s)){synchronized(this){state="";}launch();return;}
            if("denied".equals(s)){finish("denied",Taal.T("Geen toegang tot Contacten. Je kunt die geven bij Android-instellingen → Apps → Tools → Rechten."),null);return;}
            if(!"picked".equals(s)||u==null){finish("cancel",null,null);return;}
            a.h.post(a::scheduleMasterExpiry);
            new Thread(()->{
                JSONObject contact=null;String error=null;
                try{contact=read(a,u);}catch(Throwable e){error=e instanceof MasterError?e.getMessage():Taal.T("Dit contact kan niet worden gekoppeld");}
                if(!a.masterSession.accepts(t)){finish("",Taal.T("Ontgrendel de stamgegevens opnieuw"),null);return;}
                finish("",error,contact);
            },"master-contact").start();
        }
        private void finish(String how,String error,JSONObject contact){
            String r,t;synchronized(this){r=request;t=token;active=false;uri=null;state="";}
            try{
                JSONObject out=new JSONObject().put("request",r).put("token",t);
                if(contact!=null)out.put("contact",contact);if(error!=null)out.put("error",error);if(!how.isEmpty())out.put(how,true);
                a.js("onMasterContactPicked",out.toString());
            }catch(Throwable ignored){ }
        }
    }
}
