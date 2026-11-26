package nl.rene.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * De strook (MRZ) uit de tekst van de gewone tekstherkenning (Tesseract, RijbewijsOcr): een tweede manier naast MrzOcr,
 * voor stroken in een lettertype dat MrzOcr niet goed leest (zoals de gelaserde strook van een Nederlands paspoort).
 * De tekstherkenning maakt eigen fouten: een &lt; als «, K, C of E; een O voor een 0; een los teken (/ ] .) erbij; een teken
 * dubbel gelezen (1I, 7T); een &lt; te veel of te weinig. Die worden hier rechtgezet; wat dan niet met de drie
 * controlecijfers en echte datums klopt, telt niet. Wat op meer manieren klopt, of waarvoor iets weggelaten moest worden,
 * is een voorstel (twijfel), net als de namen als die regel rechtgezet moest worden.
 * Zuiver (geen Android), getest in MrzTekstTest. Nooit tekst in het logboek: alleen aantallen (Telling).
 */
final class MrzTekst {
    private MrzTekst(){ }

    /** Een stuk tekst met zijn plek op de pagina (een regel of een deel ervan). */
    static final class Stuk {
        final String tekst;final int links,boven,rechts,onder;
        Stuk(String tekst,int links,int boven,int rechts,int onder){this.tekst=tekst==null?"":tekst;this.links=links;this.boven=boven;this.rechts=rechts;this.onder=onder;}
    }
    /** Hoeveel regels op een strookregel leken en hoeveel lezingen geprobeerd zijn (voor het logboek; nooit de tekst). */
    static final class Telling {int kandidaten,lezingen;boolean gevonden;}

    /** De tekens die in een strook kunnen staan (voor de tekstherkenning). */
    static final String TEKENS="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<";
    /** Hoeveel tekens een regel hooguit te lang of te kort mag zijn. */
    static final int SPELING=4;
    /** Hooguit zoveel dubbel gelezen tekens weglaten in het stuk met de controlecijfers. */
    static final int MAX_WEG=2;
    /** Kosten van een verwisseling die de tekstherkenning vaak maakt (O/0, S/5, …): genoeg twijfel om te mogen verbeteren. */
    static final double VERWISSEL=4;
    /** Hooguit zoveel lezingen per keer (rekentijd op de telefoon). */
    static final int MAX_LEZINGEN=20000;

    /** Stukken op dezelfde hoogte (tekstherkenning in losse woorden) worden één regel, van links naar rechts; van boven naar onder. */
    static List<String> regels(List<Stuk> stukken){
        List<List<Stuk>> groepen=new ArrayList<>();
        List<Stuk> op=new ArrayList<>(stukken);
        op.sort((a,b)->a.boven!=b.boven?Integer.compare(a.boven,b.boven):Integer.compare(a.links,b.links));
        for(Stuk s:op){
            List<Stuk> bij=null;
            for(List<Stuk> g:groepen){Stuk e=g.get(0);int overlap=Math.min(e.onder,s.onder)-Math.max(e.boven,s.boven);
                if(overlap*2>Math.min(e.onder-e.boven,s.onder-s.boven)){bij=g;break;}}
            if(bij==null){bij=new ArrayList<>();groepen.add(bij);}
            bij.add(s);
        }
        List<String> uit=new ArrayList<>();
        for(List<Stuk> g:groepen){g.sort((a,b)->Integer.compare(a.links,b.links));StringBuilder b=new StringBuilder();for(Stuk s:g)b.append(s.tekst);uit.add(b.toString());}
        return uit;
    }

    /** Hoe een ander teken (/ ] . enz.) gelezen wordt: als &lt;, weggelaten, of als het cijfer dat erop lijkt (/ = 7, | = 1). */
    static final int ALS_VUL=0,WEG=1,ALS_CIJFER=2;
    /** Alleen tekens van de strook: hoofdletters en cijfers, spaties weg; « is &lt;&lt;, ‹ ( € zijn &lt;; de rest zoals modus zegt. */
    static String schoon(String s,int modus){
        StringBuilder b=new StringBuilder();
        for(char c:s.toUpperCase(Locale.ROOT).toCharArray()){
            if(Character.isWhitespace(c))continue;
            if(c>='A'&&c<='Z'||c>='0'&&c<='9')b.append(c);
            else if(c=='«'||c=='≪')b.append("<<");
            else if(c=='<'||c=='‹'||c=='('||c=='€')b.append('<');
            else if(modus==ALS_VUL)b.append('<');
            else if(modus==ALS_CIJFER&&c=='/')b.append('7');
            else if(modus==ALS_CIJFER&&c=='|')b.append('1');
        }
        return b.toString();
    }
    static String schoon(String s){return schoon(s,ALS_VUL);}

    /** Een K, C, E, S of X naast een &lt; in een vulstuk is een gelezen &lt; (zolang er iets verandert). */
    static String vul(String s){
        char[] t=s.toCharArray();boolean anders=true;
        while(anders){anders=false;
            for(int i=0;i<t.length;i++)if("KCESX".indexOf(t[i])>=0&&(i>0&&t[i-1]=='<'||i+1<t.length&&t[i+1]=='<')){t[i]='<';anders=true;}}
        return new String(t);
    }
    /**
     * De naamregel: in het stuk aan het eind met alleen &lt;, K, C, E, S, X en cijfers (die horen nooit in een naam) is alles
     * vanaf de eerste &lt; een &lt;.
     */
    static String naam(String s,int van){
        int e=s.length();while(e>van&&"<KCESX0123456789".indexOf(s.charAt(e-1))>=0)e--;
        int q=s.indexOf('<',Math.max(e,van));if(q<0)return s;
        return s.substring(0,q)+Mrz.rep('<',s.length()-q);
    }

    /** Lijkt dit op een regel van de strook? (lang genoeg en een &lt; of veel cijfers) */
    static boolean kandidaat(String s){
        if(s.length()>=20&&s.length()<30-SPELING&&s.contains("<<"))return true; // een naamregel waar het vulstuk deels ontbreekt
        if(s.length()<30-SPELING||s.length()>44+SPELING+MAX_WEG)return false;
        int cijfers=0,vul=0;for(char c:s.toCharArray()){if(c>='0'&&c<='9')cijfers++;if(c=='<')vul++;}
        return vul>=2||cijfers>=10;
    }

    /** Een regel op lengte brengen: &lt; erbij of eraf in de langste rij &lt;, of aan het eind; hooguit SPELING tekens. */
    static List<String> passend(String s,int lengte){
        Set<String> uit=new LinkedHashSet<>();int verschil=lengte-s.length();
        if(verschil==0){uit.add(s);return new ArrayList<>(uit);}
        if(Math.abs(verschil)>SPELING)return new ArrayList<>(uit);
        int van=-1,tot=-1;for(int i=0;i<s.length();){if(s.charAt(i)!='<'){i++;continue;}int j=i;while(j<s.length()&&s.charAt(j)=='<')j++;if(j-i>tot-van){van=i;tot=j;}i=j;}
        if(verschil>0){
            String erbij=Mrz.rep('<',verschil);
            if(van>=0)uit.add(s.substring(0,tot)+erbij+s.substring(tot));
            uit.add(s+erbij);
        }else{
            int weg=-verschil;
            if(van>=0&&tot-van>weg)uit.add(s.substring(0,van)+s.substring(van+weg));
            uit.add(s.substring(0,s.length()-weg));
        }
        return new ArrayList<>(uit);
    }

    /** Wat de tekstherkenning vaak verwisselt; per teken de andere lezingen. */
    static String verwisselbaar(char c){
        switch(c){
            case 'O':return "0Q";case '0':return "OD";case 'D':return "0";case 'Q':return "0O";
            case 'I':return "1L";case '1':return "IL";case 'L':return "1I";
            case 'Z':return "2";case '2':return "Z";case 'S':return "5";case '5':return "S";
            case 'B':return "8";case '8':return "B";case 'G':return "6";case '6':return "G";
            case 'A':return "4";case '4':return "A";case 'T':return "7";case '7':return "T";
            case 'K':return "<";case 'C':return "<";case 'E':return "<";case '<':return "K";
            default:return "";
        }
    }

    /** Tot waar de controlecijfers van een regel lopen (0 = geen): daarna komen alleen nog vulstukken en het eindcijfer. */
    static int kop(Mrz.Format f,int regel){
        if(f==Mrz.TD1)return regel==0||regel==1?15:0;
        return regel==1?28:0;
    }
    /** Welke regel de namen heeft (zonder controlecijfer), en vanaf welke plek. */
    static int naamRegel(Mrz.Format f){return f==Mrz.TD1?2:0;}
    static int naamVan(Mrz.Format f){return f==Mrz.TD1?0:5;}

    /** Een lezing van één regel: de tekst en hoeveel dubbel gelezen tekens weggelaten zijn. */
    static final class Variant {final String tekst;final int weg;Variant(String tekst,int weg){this.tekst=tekst;this.weg=weg;}}

    /** Mogelijke lezingen van één regel van dit formaat. */
    static List<Variant> varianten(Mrz.Format f,int regel,String s){
        List<Variant> uit=new ArrayList<>();Set<String> gezien=new LinkedHashSet<>();
        for(String v:passend(s,f.length))if(gezien.add(v))uit.add(new Variant(v,0));
        if(regel==naamRegel(f)){
            // Alleen de documentcode en het land tellen mee (plausibel); de namen zelf zijn bij twijfel een voorstel.
            List<String> bases=new ArrayList<>();bases.add(s);
            // De P van een paspoort weggevallen ("<NLD…") of als C, R, F, B, D of K gelezen (nooit een andere letter, zoals de V van een visum)
            if(f==Mrz.TD3&&s.length()>5&&s.charAt(0)=='<')bases.add("P"+s);
            if(f==Mrz.TD3&&s.length()>5&&"CRFBDK".indexOf(s.charAt(0))>=0)bases.add("P"+s.substring(1));
            for(String b:bases){
                for(String v:passend(naam(b,naamVan(f)),f.length))if(gezien.add(v))uit.add(new Variant(v,0));
                String n=naam(b,naamVan(f));
                String v=n.length()>=f.length?n.substring(0,f.length):n+Mrz.rep('<',f.length-n.length());
                if(gezien.add(v))uit.add(new Variant(v,0));
            }
            return uit;
        }
        int k=kop(f,regel);
        if(k==0){for(String v:passend(vul(s),f.length))if(gezien.add(v))uit.add(new Variant(v,0));return uit;}
        // Het stuk met de controlecijfers precies, de rest als vulstuk; eventueel met één of twee dubbel gelezen tekens weg
        // (alleen een teken naast zijn verwisseling of naast zichzelf: 1I, 7T, 00)
        List<Integer> dubbel=new ArrayList<>();int tot=Math.min(s.length(),k+MAX_WEG);
        for(int i=0;i<tot;i++){char c=s.charAt(i);String v=verwisselbaar(c);
            if(i>0&&(v.indexOf(s.charAt(i-1))>=0||s.charAt(i-1)==c)||i+1<s.length()&&v.indexOf(s.charAt(i+1))>=0)dubbel.add(i);}
        List<int[]> weg=new ArrayList<>();weg.add(new int[0]);
        for(int a:dubbel)weg.add(new int[]{a});
        for(int a=0;a<dubbel.size();a++)for(int b=a+1;b<dubbel.size();b++)weg.add(new int[]{dubbel.get(a),dubbel.get(b)});
        for(int[] w:weg){
            StringBuilder b=new StringBuilder(s);for(int j=w.length-1;j>=0;j--)b.deleteCharAt(w[j]);
            if(b.length()<k)continue;
            String hoofd=b.substring(0,k),staart=vul(b.substring(k));
            for(String rest:passend(staart,f.length-k)){String v=hoofd+rest;if(gezien.add(v))uit.add(new Variant(v,w.length));}
        }
        return uit;
    }

    /** Kandidaten per positie: het gelezen teken en zijn verwisselingen, alleen wat er mag staan; anders onbekend (nooit gokken). */
    static Mrz.Pos[][] posities(Mrz.Format f,String[] regels){
        Mrz.Pos[][] p=new Mrz.Pos[f.lines][f.length];
        for(int l=0;l<f.lines;l++)for(int i=0;i<f.length;i++){
            char c=regels[l].charAt(i),soort=f.types[l].charAt(i);
            List<Character> cs=new ArrayList<>();List<Double> ks=new ArrayList<>();
            if(Mrz.allowed(soort,c)){cs.add(c);ks.add(0d);}
            for(char v:verwisselbaar(c).toCharArray())if(Mrz.allowed(soort,v)&&!cs.contains(v)){cs.add(v);ks.add(VERWISSEL);}
            if(cs.isEmpty()){p[l][i]=Mrz.Pos.unknown();continue;}
            char[] a=new char[cs.size()];double[] k=new double[cs.size()];for(int j=0;j<a.length;j++){a[j]=cs.get(j);k[j]=ks.get(j);}
            p[l][i]=new Mrz.Pos(a,k);
        }
        return p;
    }

    /** Een bruikbare lezing, met hoeveel tekens weggelaten zijn en of de naamregel precies zo gelezen is. */
    static final class Lezing {
        final Mrz.Result r;final int weg;final boolean netjes,schoonNaam;
        /** netjes: na de namen alleen nog &lt;; schoonNaam: bovendien precies zo gelezen. */
        Lezing(Mrz.Result r,int weg,boolean netjes,boolean schoonNaam){this.r=r;this.weg=weg;this.netjes=netjes;this.schoonNaam=schoonNaam;}
    }

    /** Beste bruikbare strook in deze regels, of null: drie controlecijfers kloppen, echte datums en de juiste documentcode. */
    static Mrz.Result beste(List<String> ruw,Telling telling){
        List<Lezing> alle=lees(ruw,telling);
        Lezing best=null;for(Lezing l:alle)if(beter(l,best))best=l;
        if(best==null)return null;
        Mrz.Result r=best.r;
        // Klopt het ook op een andere manier (met evenveel weglatingen en verbeteringen)? Dan is dat veld een voorstel.
        for(Lezing l:alle){
            if(l.r.format!=r.format||l.weg!=best.weg||l.r.repaired.size()!=r.repaired.size())continue;
            if(!Mrz.field(l.r.text,r.format.doc).equals(Mrz.field(r.text,r.format.doc)))r.repaired.add("doc");
            if(!Mrz.field(l.r.text,r.format.birth).equals(Mrz.field(r.text,r.format.birth)))r.repaired.add("birth");
            if(!Mrz.field(l.r.text,r.format.expiry).equals(Mrz.field(r.text,r.format.expiry)))r.repaired.add("expiry");
        }
        // Iets weggelaten om de controlecijfers te laten kloppen: documentnummer en datums zijn dan een voorstel
        if(best.weg>0){r.repaired.add("doc");r.repaired.add("birth");r.repaired.add("expiry");}
        r.naamTwijfel=!best.schoonNaam||best.weg>0;
        if(telling!=null)telling.gevonden=true;
        return r;
    }

    static List<Lezing> lees(List<String> ruw,Telling telling){
        List<String[]> rs=new ArrayList<>();
        for(String s:ruw)rs.add(new String[]{schoon(s,ALS_VUL),schoon(s,WEG),schoon(s,ALS_CIJFER)});
        List<Lezing> uit=new ArrayList<>();int[] teller={0};
        for(String[] s:rs)if(eenKandidaat(s)&&telling!=null)telling.kandidaten++;
        for(int i=0;i<rs.size();i++){
            if(!eenKandidaat(rs.get(i)))continue;
            for(Mrz.Format f:Mrz.FORMATS){
                if(i+f.lines>rs.size())continue;
                List<List<Variant>> v=new ArrayList<>();boolean ok=true;
                for(int l=0;l<f.lines&&ok;l++){
                    String[] r=rs.get(i+l);List<Variant> lijst=new ArrayList<>();Set<String> gezien=new LinkedHashSet<>();
                    for(String s:r)if(kandidaat(s))for(Variant x:varianten(f,l,s))if(gezien.add(x.tekst))lijst.add(x);
                    ok=!lijst.isEmpty();v.add(lijst);
                }
                if(!ok)continue;
                combineer(f,v,0,new String[f.lines],0,rs.get(i+naamRegel(f))[0],uit,telling,teller);
            }
        }
        return uit;
    }
    private static boolean eenKandidaat(String[] s){for(String x:s)if(kandidaat(x))return true;return false;}
    private static void combineer(Mrz.Format f,List<List<Variant>> v,int l,String[] gekozen,int weg,String naamRuw,List<Lezing> uit,Telling telling,int[] teller){
        if(teller[0]>=MAX_LEZINGEN)return;
        if(l==f.lines){
            teller[0]++;if(telling!=null)telling.lezingen++;
            Mrz.Result r=Mrz.decode(f,posities(f,gekozen));
            if(r==null||!r.usable()||!r.plausibel())return;
            // Met iets weggelaten alleen als de drie controlecijfers zonder verbetering kloppen
            if(weg>0&&(r.repaired.contains("doc")||r.repaired.contains("birth")||r.repaired.contains("expiry")))return;
            int nr=naamRegel(f);
            boolean netjes=r.line(nr).equals(naam(r.line(nr),naamVan(f)));
            uit.add(new Lezing(r,weg,netjes,netjes&&naamRuw.equals(r.line(nr))));
            return;
        }
        for(Variant s:v.get(l)){gekozen[l]=s.tekst;combineer(f,v,l+1,gekozen,weg+s.weg,naamRuw,uit,telling,teller);}
    }

    /** Minder weggelaten gaat voor, dan minder verbeterd, dan zeker, een precies gelezen naamregel, meer kloppende controles, minder afwijking. */
    static boolean beter(Lezing a,Lezing b){
        if(b==null)return true;
        if(a.weg!=b.weg)return a.weg<b.weg;
        if(a.r.repaired.size()!=b.r.repaired.size())return a.r.repaired.size()<b.r.repaired.size();
        if(a.netjes!=b.netjes)return a.netjes;
        if(a.schoonNaam!=b.schoonNaam)return a.schoonNaam;
        if(a.r.sure()!=b.r.sure())return a.r.sure();
        if(a.r.valid()!=b.r.valid())return a.r.valid()>b.r.valid();
        return a.r.mean<b.r.mean;
    }
}
