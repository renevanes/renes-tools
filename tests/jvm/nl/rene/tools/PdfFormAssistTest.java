package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

public final class PdfFormAssistTest {
    interface Checked{void run()throws Exception;}
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
    static void rejects(Checked call)throws Exception{boolean fail=false;try{call.run();}catch(Exception e){fail=true;}check(fail,"ongeldige invulhulp geweigerd");}
    static JSONObject field(String id,String type,String role,String key)throws Exception{
        return new JSONObject().put("id",id).put("label",id).put("type",type).put("sources",new JSONArray().put(new JSONObject().put("role",role).put("key",key)));
    }
    static JSONObject request()throws Exception {
        JSONArray fields=new JSONArray().put(field("kind-naam","text","kind","naam")).put(field("kind-geboortedatum","date","kind","geboortedatum"))
                .put(field("kind-bsn","bsn","kind","bsn")).put(field("van","date","reis","van")).put(field("land","text","reis","land"))
                .put(field("alleen","select","reis","alleen").put("options",new JSONArray().put("ja").put("nee")));
        JSONArray sources=new JSONArray().put(new JSONObject().put("id","bron-1").put("label","Testkind").put("role","kind").put("type","persoon")
                .put("fields",new JSONObject().put("naam","Zoë <voorbeeld>").put("geboortedatum","2015-02-03").put("bsn","123456782")))
                .put(new JSONObject().put("id","bron-2").put("label","Ouder").put("role","gever1").put("type","persoon").put("fields",new JSONObject().put("naam","Ander")))
                .put(new JSONObject().put("id","bron-3").put("label","Zomerreis").put("role","reis").put("type","reis").put("fields",new JSONObject().put("van","2027-07-12").put("land","België").put("alleen","nee")));
        return PdfFormAssist.request(new JSONObject().put("fields",fields).put("sources",sources).put("text","We reizen op 12-07-2027 naar België. Het kind reist niet alleen.").toString());
    }
    public static void main(String[] args)throws Exception {
        JSONObject request=request();
        JSONArray good=PdfFormAssist.resolve(request,"```json\n{\"velden\":[{\"id\":\"kind-naam\",\"bron\":\"bron-1\",\"sleutel\":\"naam\",\"waarde\":\"Verzonnen\"},{\"id\":\"van\",\"citaat\":\"12-07-2027\"}]}\n```");
        check(good.length()==2,"bron en letterlijk citaat gebruikt");
        check(good.getJSONObject(0).getString("value").equals("Zoë <voorbeeld>"),"alleen de werkelijke bronwaarde, nooit een modelwaarde");
        check(good.getJSONObject(1).getString("value").equals("2027-07-12"),"volledige datum exact omgezet");
        check(good.getJSONObject(1).getString("sourceType").equals("text"),"toelichting herkenbaar voor vertaling");
        JSONArray rejected=PdfFormAssist.resolve(request,"{\"velden\":[{\"id\":\"kind-naam\",\"bron\":\"bron-2\",\"sleutel\":\"naam\"},{\"id\":\"kind-bsn\",\"citaat\":\"999999999\"},{\"id\":\"onbekend\",\"citaat\":\"België\"},{\"id\":\"alleen\",\"citaat\":\"niet alleen\"}]}");
        check(rejected.length()==0,"andere persoon, verzonnen nummers, onbekende velden en afgeleide toestemming geweigerd");
        check(PdfFormAssist.resolve(request,"{\"velden\":[{\"id\":\"kind-bsn\",\"bron\":\"bron-1\",\"sleutel\":\"bsn\"}]}").getJSONObject(0).getString("value").equals("123456782"),"gekozen identiteitsgegeven exact gekopieerd");
        rejects(()->PdfFormAssist.resolve(request,"{\"velden\":[{\"id\":\"van\",\"citaat\":\"12-07-2027\"},{\"id\":\"van\",\"bron\":\"bron-3\",\"sleutel\":\"van\"}]}"));
        rejects(()->PdfFormAssist.resolve(request,"geen JSON"));
        request.put("text","31-02-2027");check(PdfFormAssist.resolve(request,"{\"velden\":[{\"id\":\"van\",\"citaat\":\"31-02-2027\"}]}").length()==0,"onmogelijke datum niet overgenomen");
        JSONObject bad=request();bad.getJSONArray("sources").getJSONObject(0).getJSONObject("fields").put("email","niet-gekozen@example.org");rejects(()->PdfFormAssist.request(bad.toString()));
        JSONObject trip=new JSONObject().put("type","reis").put("label","Herbruikbare reis").put("fields",new JSONObject().put("van","2027-07-12").put("tot","2027-07-30").put("alleen","nee").put("land","België"));
        check(MasterSchema.clean(trip).getJSONObject("fields").getString("land").equals("België"),"reis als vaste dataset");
        check(MasterSchema.projection(trip,"formulieren","reis").has("van"),"reisprojectie");
        check(MasterSchema.projection(trip,"formulieren","kind").length()==0,"reis geeft geen persoonsvelden vrij");
        trip.getJSONObject("fields").put("tot","2027-07-01");rejects(()->MasterSchema.clean(trip));trip.getJSONObject("fields").put("tot","2027-07-30").put("alleen","misschien");rejects(()->MasterSchema.clean(trip));
        System.out.println(checks+" controles: gekozen bronnen, bewijs, rollen, datums en herbruikbare reizen");
    }
}
