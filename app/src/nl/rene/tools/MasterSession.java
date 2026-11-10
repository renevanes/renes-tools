package nl.rene.tools;

/** Bevoegdheid per Activity, monotone tijd en een nieuwe capability na iedere ontgrendeling. */
final class MasterSession {
    interface Clock { long now(); }
    static final long IDLE=5*60_000L, MAX=9*60_000L;
    private static final java.util.concurrent.atomic.AtomicLong DATASET = new java.util.concurrent.atomic.AtomicLong();
    private long dataset;
    static long datasetEpoch(){return DATASET.get();}
    synchronized void closeIf(String expected){if(!expected.isEmpty()&&token.equals(expected))close();}
    static void invalidateAll(){DATASET.incrementAndGet();}
    private final Clock clock;
    private String token="";
    private long idle,limit;
    MasterSession(){this(android.os.SystemClock::elapsedRealtime);}
    MasterSession(Clock clock){this.clock=clock;}
    synchronized String open(){return open(MAX);}
    /** max: korter dan MAX als de sleutel eerder ophoudt (geopend zonder vraag, Ontgrendeld). */
    synchronized String open(long max){long n=clock.now();token=java.util.UUID.randomUUID().toString();dataset=DATASET.get();limit=n+Math.max(0,Math.min(MAX,max));idle=Math.min(limit,n+IDLE);return token;}
    synchronized boolean isOpen(){if(dataset!=DATASET.get()||token.isEmpty()||clock.now()>=idle||clock.now()>=limit){close();return false;}return true;}
    synchronized String consumerToken(){return isOpen()?token:"";}
    synchronized boolean accepts(String value){return isOpen()&&token.equals(value);}
    synchronized void require(String value)throws Exception{if(!accepts(value))throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw"));}
    synchronized void touch(String value)throws Exception{require(value);idle=Math.min(limit,clock.now()+IDLE);}
    synchronized long remaining(){return isOpen()?Math.max(0,Math.min(idle,limit)-clock.now()):0;}
    synchronized void close(){token="";idle=0;limit=0;}
}
