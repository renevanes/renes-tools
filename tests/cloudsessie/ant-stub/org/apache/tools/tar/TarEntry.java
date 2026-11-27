package org.apache.tools.tar;

/** Alleen om de app te kunnen compileren in een cloudsessie zonder Maven Central (zie tests/cloudsessie/LEESMIJ.md). Doet niets. */
public class TarEntry {
    public String getName() { return ""; }
    public boolean isGNUSparse() { return false; }
    public boolean isDirectory() { return false; }
    public boolean isFile() { return false; }
    public long getSize() { return 0; }
    public byte getLinkFlag() { return 0; }
}
