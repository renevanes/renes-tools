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
    static final Pattern LABEL = Pattern.compile("(?i)(?<!\\S)(4\\s*[abc]|[12359])(?:\\s*[.:)]\\s*|\\s+)");
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
        if(value.isEmpty()||confidence<50)return;
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
    static JSONObject read(List<Line> lines,String today)throws Exception {
        if(lines==null||lines.size()>200)return null;
        Map<String,Field> fields=new LinkedHashMap<>();Set<String> labels=new LinkedHashSet<>();StringBuilder all=new StringBuilder();
        for(Line line:lines) {
            if(line.text==null||line.text.length()>500||line.confidence<35||Float.isNaN(line.confidence)||Float.isInfinite(line.confidence))continue;
            String text=line.text.replace('\u00a0',' ').trim();all.append(text).append(' ');
            Matcher m=LABEL.matcher(text);List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();List<String> keys=new ArrayList<>();
            while(m.find()){if(inDate(text,m.start()))continue;starts.add(m.start());ends.add(m.end());keys.add(m.group(1).replaceAll("\\s","").toLowerCase(Locale.ROOT));}
            for(int i=0;i<keys.size();i++) {
                String key=keys.get(i),value=text.substring(ends.get(i),i+1<keys.size()?starts.get(i+1):text.length()).trim();
                float certainty=line.confidence;
                if(value.isEmpty()){Line neighbor=nearby(line,lines);if(neighbor!=null){value=neighbor.text.trim();certainty=Math.min(certainty,neighbor.confidence);}}
                if(!value.isEmpty()){labels.add(key);add(fields,key,value,certainty);}
            }
        }
        if(all.length()>12000)return null;
        String header=java.text.Normalizer.normalize(all.toString(),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        boolean licence=header.matches(".*(?:RIJBEW[IJ1L]{1,2}[JS]|DRIVINGLICEN[CS]E|FUHRERSCHEIN|PERMISDECONDUIRE).*");
        JSONObject doc=new JSONObject().put("documentsoort","rijbewijs"),person=new JSONObject(),confidence=new JSONObject();JSONArray doubtful=new JSONArray();int count=0;
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
            if(f.confidence<90||f.ambiguous)doubtful.put(key);
        }
        if(!licence&&!(labels.contains("1")&&labels.contains("2")&&labels.contains("3")&&labels.contains("4b")&&labels.contains("5")&&doc.has("document")&&person.has("geboortedatum")))return null;
        if(count<2||!doc.has("document")&&!person.has("geboortedatum"))return null;
        String birth=person.optString("geboortedatum"),issued=doc.optString("afgifte"),expiry=doc.optString("geldig");
        if(!birth.isEmpty()&&!issued.isEmpty()&&issued.compareTo(birth)<=0){doc.remove("afgifte");issued="";count--;}
        if(!expiry.isEmpty()&&(!birth.isEmpty()&&expiry.compareTo(birth)<=0||!issued.isEmpty()&&expiry.compareTo(issued)<0)){doc.remove("geldig");count--;}
        if(count<2)return null;
        return new JSONObject().put("soort","rijbewijs").put("bron","tekst").put("zeker",false).put("gelezen",count)
                .put("document",doc).put("persoon",person).put("twijfel",doubtful).put("betrouwbaarheid",confidence);
    }
}
