package nl.rene.tools;

public final class PdfSignatureTest {
    static void reject(double ratio,double[][][] paths){try{PdfSignature.validate(ratio,paths);throw new AssertionError("Ongeldige handtekening aanvaard");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        PdfSignature.validate(.3,new double[][][]{{{0,0},{.5,.2},{1,1}}});
        reject(.3,new double[0][][]);reject(.3,new double[][][]{{{0,0}}});reject(.3,new double[][][]{{{.2,.2},{.2,.2}}});
        reject(Double.NaN,new double[][][]{{{0,0},{1,1}}});reject(100,new double[][][]{{{0,0},{1,1}}});
        reject(.3,new double[][][]{{{0,0},{Double.POSITIVE_INFINITY,1}}});reject(.3,new double[][][]{{{0,0},{1.1,1}}});
        double[][] many=new double[10001][2];reject(.3,new double[][][]{many});
        if(!DocumentMeta.category("  Verzekeringen  ").equals("Verzekeringen"))throw new AssertionError();
        if(!DocumentMeta.note("Polis\nTot december").contains("\n"))throw new AssertionError();
        try{DocumentMeta.category("A\nB");throw new AssertionError();}catch(IllegalArgumentException expected){}
        try{DocumentMeta.note(new String(new char[1001]).replace('\0','a'));throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("Handtekening en documentgegevens: geldige lijnen, grenzen en ongeldige invoer gecontroleerd");
    }
}
