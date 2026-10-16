package nl.rene.tools;

/** Grenzen voor categorieën en toelichtingen, onafhankelijk van het bestandsformaat. */
final class DocumentMeta {
    private DocumentMeta() { }
    static String category(String value) { return text(value,60,false,"Categorie"); }
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
