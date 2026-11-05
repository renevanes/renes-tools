package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Eén lokale invulvraag per Activity, gekoppeld aan de ontgrendelde gegevenssessie. */
final class PdfFormAi {
    final MainActivity activity;
    private Run current;
    PdfFormAi(MainActivity activity){this.activity=activity;}
    synchronized String start(String token,String json,String request){
        if(current!=null)return AiBridge.BUSY;
        try{
            if(!activity.masterForeground||!activity.masterFormBound||!activity.masterSession.accepts(token))return Taal.T("Ontgrendel de stamgegevens opnieuw");
            if(request==null||!request.matches("[a-zA-Z0-9-]{1,80}"))return Taal.T("De gekozen gegevens passen niet bij deze formulierstap.");
            JSONObject input=PdfFormAssist.request(json);Run run=new Run(this,token,request,input);current=run;
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content",input.toString()));
            run.job=LocalAi.privateChatJob(activity.getApplicationContext(),PdfFormAssist.SYSTEM,messages,1100,run,true);
            if(run.job==0){current=null;return AiBridge.BUSY;}
            activity.masterSession.touch(token);activity.h.post(activity::scheduleMasterExpiry);
            return "";
        }catch(Throwable e){cancel(null);return e instanceof MasterError?e.getMessage():Taal.T("De lokale AI kon niet starten");}
    }
    synchronized boolean live(Run run){return current==run&&activity.masterForeground&&activity.masterSession.accepts(run.token);}
    synchronized void cancel(String request){if(current!=null&&(request==null||request.equals(current.request))){Run run=current;current=null;LocalAi.cancelJob(run.job);synchronized(run){run.reply.setLength(0);}}}
    void finish(Run run,boolean cancelled,String error){
        synchronized(this){if(current!=run)return;current=null;}
        if(!activity.masterForeground||!activity.masterSession.accepts(run.token))return;
        JSONObject result=new JSONObject();
        try{
            result.put("request",run.request).put("token",run.token);
            if(cancelled)result.put("cancelled",true);
            else if(error!=null)result.put("error",Taal.t(activity,error));
            else{
                JSONArray rows;
                synchronized(run){rows=PdfFormAssist.resolve(run.input,run.reply.toString());run.reply.setLength(0);}
                if(rows.length()==0)result.put("error",Taal.t(activity,"De lokale AI heeft geen controleerbare invulling gevonden. Gebruik de vaste gegevens of vul je toelichting aan."));
                else result.put("fields",rows);
            }
        }catch(Throwable ignored){try{result.put("error",Taal.t(activity,"Het AI-antwoord kon niet worden gecontroleerd. Probeer opnieuw of gebruik de vaste gegevens."));}catch(Exception ignoredAgain){ }}
        if(activity.masterForeground&&activity.masterSession.accepts(run.token))activity.js("onPdfFormAi",result.toString());
    }
    static final class Run implements LocalAi.Sink {
        final PdfFormAi owner;final String token,request;final JSONObject input;final StringBuilder reply=new StringBuilder();long job;
        Run(PdfFormAi owner,String token,String request,JSONObject input){this.owner=owner;this.token=token;this.request=request;this.input=input;}
        @Override public void status(String text){ }
        @Override public void sources(JSONArray items){ }
        @Override public void memory(JSONObject o){ }
        @Override public void token(String text){
            if(!owner.live(this))return;
            boolean full;synchronized(this){full=reply.length()+text.length()>PdfFormAssist.MAX_REPLY;if(!full)reply.append(text);}
            if(full){LocalAi.cancelJob(job);owner.finish(this,false,Taal.T("Het AI-antwoord is te lang. Gebruik minder gegevens."));}
        }
        @Override public void done(JSONObject info){boolean cancelled=info.optBoolean("cancelled");owner.finish(this,cancelled,!cancelled&&!PdfFormAssist.complete(info)?Taal.T("De brongegevens passen niet in het taalmodel. Kies minder velden of kort de toelichting in."):null);}
        @Override public void error(String message){owner.finish(this,false,message);}
    }
}
