package org.nustaq.kontraktor.webapp.transpiler.jsx;

public class Inp {
    char[] file;
    int index;
    int failcount =0;
    public Inp(String s) {
        index = 0;
        file = s.toCharArray();
    }
    public char ch(int off) {
        if (index+off >= file.length) {
            failcount++;
            if ( failcount > 100 )
                throw new RuntimeException("prevent endlessloop, check for missing braces, tags or similar balanced chars");
            return 0;
        }
        if (index+off < 0)
            return 0;
        return file[index+off];
    }

    public void advance(int amount) {
        index+=amount;
    }

    public char ch() {
        return ch(0);
    }

    public void inc() {
        index++;
    }

    public String toString() {
        int start = Math.max(0, index - 50);
        return new String(file, start,index-start);
    }

    public boolean match(String str) {
        for ( int i=0; i < str.length(); i++)
            if ( ch(i) != str.charAt(i))
                return false;
        return true;
    }

    public int index() {
        return index;
    }

    public String substring(int lastBracePo, int index) {
        return new String(file,lastBracePo,index-lastBracePo);
    }

    public char at(int lastBracePo) {
        if (lastBracePo<0)
            return 0;
        if (lastBracePo>=file.length)
            return 0;
        return file[lastBracePo];
    }

    public void skipWS() {
        while ( Character.isWhitespace(ch()) ) {
            inc();
        }
    }

}
