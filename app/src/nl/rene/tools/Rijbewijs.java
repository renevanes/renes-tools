package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gewone gedrukte velden van een Europees rijbewijs. Geen afleiding van BSN of correctie van persoonsnamen. */
final class Rijbewijs {
    private Rijbewijs() { }
    static final Pattern LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9.,])(4\\s*[abc]|[12359])(?:\\s*[.:)]\\s*|\\s+)");
    static final Pattern DATE = Pattern.compile("(?<![A-Za-z0-9])([0-3]?[0-9])[. /-]([01]?[0-9])[. /-]((?:19|20)[0-9]{2})(?![A-Za-z0-9])");
    static final class Line {
        final String text; final float confidence; final int left, top, right, bottom;
        Line(String text,float confidence,int left,int top,int right,int bottom) {
            this.text=text;this.confidence=confidence;this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
    }
    static final class Field {
        String value; float confidence; boolean ambiguous;
        Field(String value,float confidence) { this.value=value;this.confidence=confidence; }
    }
    private static void add(Map<String,Field> fields,String key,String value,float confidence) {
        if(value.isEmpty()||confidence<MIN_FIELD)return;
        Field old=fields.get(key);
        if(old==null){fields.put(key,new Field(value,confidence));return;}
        if(!old.value.equals(value))old.ambiguous=true;
        if(confidence>old.confidence){old.value=value;old.confidence=confidence;}
    }
    static String date(String value) {
        Matcher m=DATE.matcher(value);if(!m.find())return "";
        int day=Integer.parseInt(m.group(1)),month=Integer.parseInt(m.group(2)),year=Integer.parseInt(m.group(3));
        Calendar c=new GregorianCalendar(TimeZone.getTimeZone("UTC"),Locale.ROOT);c.clear();c.setLenient(false);c.set(year,month-1,day);
        try{c.getTimeInMillis();return String.format(Locale.ROOT,"%04d-%02d-%02d",year,month,day);}catch(IllegalArgumentException e){return "";}
    }
    private static String name(String value) {
        value=value.replaceAll("\\s+"," ").trim();
        return value.length()>=2&&value.length()<=120&&value.matches("[\\p{L}\\p{M} .'’\\-]+")&&value.replaceAll("[^\\p{L}]","").length()>=2?value:"";
    }
    private static String number(String value) {
        value=value.replaceAll("\\s+","").toUpperCase(Locale.ROOT);
        return value.matches("[A-Z0-9]{8,20}")&&value.matches(".*[0-9].*")?value:"";
    }
    static String categories(String value) {
        Set<String> out=new LinkedHashSet<>();
        for(String part:value.toUpperCase(Locale.ROOT).trim().split("[\\s,/;|]+")) {
            if(!part.matches("AM|A1|A2|A|B1|BE|B|C1E|C1|CE|C|D1E|D1|DE|D|T|L"))return "";
            out.add(part);
        }
        StringBuilder text=new StringBuilder();for(String item:out){if(text.length()>0)text.append(", ");text.append(item);}return text.toString();
    }
    private static boolean inDate(String text,int index) {
        Matcher m=DATE.matcher(text);while(m.find())if(index>=m.start()&&index<m.end())return true;return false;
    }
    private static Line nearby(Line label,List<Line> lines) {
        Line found=null;double distance=Double.MAX_VALUE;
        for(Line line:lines) {
            if(line==label||line.text==null||line.confidence<50||LABEL.matcher(line.text).find())continue;
            int h=Math.max(label.bottom-label.top,line.bottom-line.top),dy=Math.abs((line.top+line.bottom)-(label.top+label.bottom))/2;
            if(dy>h*.65||line.left<label.right-4)continue;
            double d=line.left-label.right+dy*3;if(d<distance){distance=d;found=line;}
        }
        return found;
    }
    /** Hoe het lezen ging, voor de hulp bij mislukken: alleen aantallen en ja/nee, nooit tekst. */
    static final class Stand { int regels; boolean rijbewijs, onderregel; }

    static JSONObject read(List<Line> lines,String today)throws Exception { return read(lines,today,null); }
    static JSONObject read(List<Line> lines,String today,Stand stand)throws Exception {
        if(stand!=null){stand.regels=0;stand.rijbewijs=false;stand.onderregel=false;}
        if(lines==null||lines.size()>200)return null;
        List<Float> sureness=new ArrayList<>();
        Map<String,Field> fields=new LinkedHashMap<>();Set<String> labels=new LinkedHashSet<>();StringBuilder all=new StringBuilder();List<String> texts=new ArrayList<>();
        for(Line line:lines) {
            if(line.text==null||line.text.length()>500||line.confidence<MIN_LINE||Float.isNaN(line.confidence)||Float.isInfinite(line.confidence))continue;
            String text=line.text.replace(' ',' ').trim();all.append(text).append(' ');texts.add(text);sureness.add(line.confidence);
            Matcher m=LABEL.matcher(text);List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();List<String> keys=new ArrayList<>();
            while(m.find()){if(inDate(text,m.start()))continue;starts.add(m.start());ends.add(m.end());keys.add(m.group(1).replaceAll("\\s","").toLowerCase(Locale.ROOT));}
            for(int i=0;i<keys.size();i++) {
                String key=keys.get(i),value=text.substring(ends.get(i),i+1<keys.size()?starts.get(i+1):text.length()).trim();
                float certainty=line.confidence;
                if(value.isEmpty()){Line neighbor=nearby(line,lines);if(neighbor!=null){value=neighbor.text.trim();certainty=Math.min(certainty,neighbor.confidence);}}
                if(!value.isEmpty()){labels.add(key);add(fields,key,value,certainty);}
            }
        }
        if(stand!=null)stand.regels=texts.size();
        if(all.length()>12000)return null;
        String header=java.text.Normalizer.normalize(all.toString(),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        String[] bottom=bottomLine(texts);
        boolean title=header.matches(".*(?:RIJBEW[IJ1L]{1,2}[JS]|DRIVINGLICEN[CS]E|FUHRERSCHEIN|PERMISDECONDUIRE).*"),licence=bottom!=null||title;
        if(stand!=null){stand.rijbewijs=licence;stand.onderregel=bottom!=null;}
        JSONObject doc=new JSONObject().put("documentsoort","rijbewijs"),person=new JSONObject(),confidence=new JSONObject();Set<String> doubtful=new LinkedHashSet<>();int count=0;
        for(Map.Entry<String,Field> entry:fields.entrySet()) {
            String label=entry.getKey(),key="",value="";boolean personal=false;Field f=entry.getValue();
            switch(label) {
                case "1":key="naam";value=name(f.value);personal=true;break;
                case "2":key="voornamen";value=name(f.value);personal=true;break;
                case "3":key="geboortedatum";value=date(f.value);if(!value.isEmpty()&&value.compareTo(today)>=0)value="";personal=true;break;
                case "4a":key="afgifte";value=date(f.value);break;
                case "4b":key="geldig";value=date(f.value);break;
                case "4c":key="afgegevenDoor";value=name(f.value);break;
                case "5":key="document";value=number(f.value);break;
                case "9":key="categorieen";value=categories(f.value);break;
                default:break;
            }
            if(value.isEmpty())continue;
            (personal?person:doc).put(key,value);confidence.put(key,Math.round(f.confidence));count++;
            if(f.confidence<90||f.ambiguous)doubtful.add(key);
        }
        if(licence) {
            // De regel onderaan (D1NLD…) heeft een controlecijfer: dat nummer gaat voor op een los gelezen veld 5.
            if(bottom!=null) {
                String old=doc.optString("document");boolean ok="ok".equals(bottom[1]);
                if(old.isEmpty()){doc.put("document",bottom[0]);count++;}else if(ok)doc.put("document",bottom[0]);
                // Het controlecijfer vangt niet alles (twee verwisselde cijfers die 5 verschillen): alleen zeker als ook
                // veld 5 hetzelfde zegt, of als de regel zelf goed gelezen is.
                float lineSure=bottom.length>2?sureness.get(Integer.parseInt(bottom[2])):0;
                boolean sure=ok&&(old.equals(bottom[0])||old.isEmpty()&&lineSure>=85);
                confidence.put("document",sure?100:60);if(sure)doubtful.remove("document");else doubtful.add("document");
            } else if(!doc.has("document")&&!labels.contains("5")) {
                // Zonder veldnummer: één los nummer van tien cijfers (het Nederlandse rijbewijsnummer), als voorstel.
                Set<String> numbers=new LinkedHashSet<>();Matcher m=TEN_DIGITS.matcher(String.join("\n",texts));while(m.find())numbers.add(m.group());
                if(numbers.size()==1){doc.put("document",numbers.iterator().next());confidence.put("document",50);doubtful.add("document");count++;}
            }
            // Datums zonder (leesbaar) veldnummer: afgifte en geldig tot liggen 5 of 10 jaar uit elkaar; de geboortedatum ruim ervoor.
            // Alleen met de titel (voorkant): op de achterkant staan datums per categorie, die zijn geen afgifte of geboorte.
            List<String> dates=new ArrayList<>();if(title)for(String text:texts){Matcher m=DATE.matcher(text);while(m.find()){String d=date(m.group());if(!d.isEmpty()&&!dates.contains(d))dates.add(d);}}
            java.util.Collections.sort(dates);
            if(!doc.has("geldig")&&!labels.contains("4b")) {
                String[] pair=null;
                for(int a=0;a<dates.size();a++)for(int b=a+1;b<dates.size();b++)if(term(dates.get(a),dates.get(b))&&(pair==null||dates.get(b).compareTo(pair[1])>0))pair=new String[]{dates.get(a),dates.get(b)};
                if(pair!=null) {
                    doc.put("geldig",pair[1]);confidence.put("geldig",50);doubtful.add("geldig");count++;
                    if(!doc.has("afgifte")&&!labels.contains("4a")){doc.put("afgifte",pair[0]);confidence.put("afgifte",50);doubtful.add("afgifte");count++;}
                }
            }
            if(!person.has("geboortedatum")&&!labels.contains("3")) {
                String first=doc.optString("afgifte",doc.optString("geldig",today));
                for(String d:dates)if(d.compareTo(today)<0&&yearsBetween(d,first)>=16){person.put("geboortedatum",d);confidence.put("geboortedatum",50);doubtful.add("geboortedatum");count++;break;}
            }
        }
        if(!licence&&!(labels.contains("1")&&labels.contains("2")&&labels.contains("3")&&labels.contains("4b")&&labels.contains("5")&&doc.has("document")&&person.has("geboortedatum")))return null;
        boolean sureNumber=bottom!=null&&"ok".equals(bottom[1]);
        if((count<2&&!sureNumber)||!doc.has("document")&&!person.has("geboortedatum"))return null;
        String birth=person.optString("geboortedatum"),issued=doc.optString("afgifte"),expiry=doc.optString("geldig");
        if(!birth.isEmpty()&&!issued.isEmpty()&&issued.compareTo(birth)<=0){doc.remove("afgifte");issued="";count--;}
        if(!expiry.isEmpty()&&(!birth.isEmpty()&&expiry.compareTo(birth)<=0||!issued.isEmpty()&&expiry.compareTo(issued)<0)){doc.remove("geldig");count--;}
        if(count<2&&!sureNumber)return null;
        JSONArray twijfel=new JSONArray();for(String k:doubtful)if(doc.has(k)||person.has(k))twijfel.put(k);
        return new JSONObject().put("soort","rijbewijs").put("bron","tekst").put("zeker",false).put("gelezen",count)
                .put("document",doc).put("persoon",person).put("twijfel",twijfel).put("betrouwbaarheid",confidence);
    }

    static final float MIN_LINE=20, MIN_FIELD=30;
    static final Pattern TEN_DIGITS=Pattern.compile("(?<![0-9A-Za-z])[0-9]{10}(?![0-9A-Za-z])");

    /** Twee datums van een afgifte en de einddatum: 5, 10 of 15 jaar later, dezelfde dag of een dag eerder. */
    static boolean term(String from,String to) {
        for(int years:new int[]{5,10,15}) {
            String same=String.format(Locale.ROOT,"%04d%s",Integer.parseInt(from.substring(0,4))+years,from.substring(4));
            if(to.equals(same))return true;
            try{
                Calendar c=new GregorianCalendar(TimeZone.getTimeZone("UTC"),Locale.ROOT);c.clear();c.setLenient(true);
                c.set(Integer.parseInt(same.substring(0,4)),Integer.parseInt(same.substring(5,7))-1,Integer.parseInt(same.substring(8,10)));c.add(Calendar.DAY_OF_MONTH,-1);
                if(to.equals(String.format(Locale.ROOT,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH))))return true;
            }catch(Exception ignored){ }
        }
        return false;
    }
    static int yearsBetween(String from,String to) {
        int y=Integer.parseInt(to.substring(0,4))-Integer.parseInt(from.substring(0,4));
        if(to.substring(5).compareTo(from.substring(5))<0)y--;return y;
    }

    /**
     * De leesregel onderaan de voorkant van een Nederlands rijbewijs: 30 tekens, D1NLD, een cijfer, het rijbewijsnummer
     * (10 cijfers), nog 13 tekens en een controlecijfer (7-3-1 over de eerste 29 tekens). Geeft {nummer, "ok"|"twijfel"}
     * of null. Op cijferplekken worden letters die er op lijken (O, I, S, B …) als cijfer gelezen; het controlecijfer beslist.
     */
    static String[] bottomLine(List<String> texts) {
        String[] best=null;
        for(int t=0;t<texts.size();t++) {
            String text=texts.get(t);
            String s=text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9<]","");
            for(int i=s.indexOf("NLD");i>=0;i=s.indexOf("NLD",i+1)) {
                if(i<2||s.length()<i-2+30)continue;
                char[] c=s.substring(i-2,i-2+30).toCharArray();
                if(c[0]!='D')continue;
                c[1]=digit(c[1]);for(int k=5;k<16;k++)c[k]=digit(c[k]);c[29]=digit(c[29]);
                boolean digits=Character.isDigit(c[1]);for(int k=5;k<16;k++)digits&=Character.isDigit(c[k]);
                if(!digits)continue;
                String line=new String(c),number=line.substring(6,16);
                boolean ok=Character.isDigit(c[29])&&Mrz.digit(line.substring(0,29))==c[29]-'0';
                if(ok)return new String[]{number,"ok",String.valueOf(t)};
                if(best==null)best=new String[]{number,"twijfel",String.valueOf(t)};
            }
        }
        return best;
    }
    private static char digit(char c) {
        switch(c){case 'O':case 'Q':case 'D':return '0';case 'I':case 'L':return '1';case 'Z':return '2';case 'S':return '5';case 'G':return '6';case 'B':return '8';default:return c;}
    }

    /** Twee lezingen (andere draaiing of bewerking van de foto) samenvoegen: wat ontbreekt aanvullen, zeker gaat voor twijfel. */
    static JSONObject merge(JSONObject a,JSONObject b)throws Exception {
        if(a==null)return b;if(b==null)return a;
        JSONObject out=new JSONObject(a.toString());Set<String> twijfel=new LinkedHashSet<>();
        JSONArray ta=a.optJSONArray("twijfel"),tb=b.optJSONArray("twijfel");
        for(int i=0;ta!=null&&i<ta.length();i++)twijfel.add(ta.getString(i));
        Set<String> doubtB=new LinkedHashSet<>();for(int i=0;tb!=null&&i<tb.length();i++)doubtB.add(tb.getString(i));
        JSONObject conf=out.optJSONObject("betrouwbaarheid");if(conf==null)out.put("betrouwbaarheid",conf=new JSONObject());
        int count=0;
        for(String group:new String[]{"document","persoon"}) {
            JSONObject x=out.getJSONObject(group),y=b.optJSONObject(group);
            if(y!=null)for(java.util.Iterator<String> it=y.keys();it.hasNext();) {
                String k=it.next();if("documentsoort".equals(k))continue;
                boolean takeB=!x.has(k)||twijfel.contains(k)&&!doubtB.contains(k);
                if(takeB){x.put(k,y.get(k));conf.put(k,b.optJSONObject("betrouwbaarheid")==null?50:b.getJSONObject("betrouwbaarheid").optInt(k,50));if(doubtB.contains(k))twijfel.add(k);else twijfel.remove(k);}
                else if(!x.get(k).equals(y.get(k)))twijfel.add(k);
            }
            for(java.util.Iterator<String> it=x.keys();it.hasNext();)if(!"documentsoort".equals(it.next()))count++;
        }
        JSONArray t=new JSONArray();for(String k:twijfel)t.put(k);
        return out.put("twijfel",t).put("gelezen",count);
    }
}
