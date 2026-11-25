package nl.rene.tools;

/** Grenzen voor mappen en toelichtingen, onafhankelijk van het bestandsformaat (de map heette eerst categorie). */
final class DocumentMeta {
    private DocumentMeta() { }
    /** De map van een document; de foutteksten zeggen "map", zoals het scherm (en zijn te vertalen). */
    static String category(String value) {
        if(value==null)throw new IllegalArgumentException(Taal.T("De map ontbreekt"));
        if(value.trim().length()>60)throw new IllegalArgumentException(Taal.T("De naam van de map is te lang (hooguit 60 tekens)"));
        try{return text(value,60,false,"Map");}
        catch(IllegalArgumentException e){throw new IllegalArgumentException(Taal.T("De naam van de map bevat ongeldige tekens"));}
    }
    static String note(String value) { return text(value,1000,true,"Toelichting"); }
    static String text(String value,int max,boolean lines,String label) {
        if(value==null)throw new IllegalArgumentException(label+" ontbreekt");
        String clean=value.trim();
        if(clean.length()>max)throw new IllegalArgumentException(label+" is te lang (maximaal "+max+" tekens)");
        for(char c:clean.toCharArray())if(Character.isISOControl(c)&&!(lines&&(c=='\n'||c=='\r'||c=='\t')))
            throw new IllegalArgumentException(label+" bevat ongeldige tekens");
        return clean;
    }
}
