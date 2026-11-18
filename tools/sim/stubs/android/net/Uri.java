package android.net;
public class Uri { public String s; public static Uri parse(String s) { Uri u = new Uri(); u.s = s; return u; } public String toString() { return s; } }
