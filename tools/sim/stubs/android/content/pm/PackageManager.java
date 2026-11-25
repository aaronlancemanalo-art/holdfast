package android.content.pm;
import java.util.*;
public class PackageManager {
    public static class NameNotFoundException extends Exception { }
    public static class App { public String label; public boolean system; public String installer; public long firstInstall; }
    public Map<String, App> apps = new HashMap<>();
    public Set<String> browsers = new HashSet<>(), searchApps = new HashSet<>(), redditApps = new HashSet<>();
    /** Apps that answer for links to a site: a piece of the address -> the apps. */
    public Map<String, Set<String>> linkHandlers = new HashMap<>();
    public int linkLookups = 0;
    public boolean ledgerSigned = true;
    public void add(String pkg, String label, boolean system, String installer, long firstInstall) {
        App a = new App(); a.label = label; a.system = system; a.installer = installer; a.firstInstall = firstInstall; apps.put(pkg, a);
    }
    public PackageInfo getPackageInfo(String pkg, int flags) throws NameNotFoundException {
        App a = apps.get(pkg); if (a == null) throw new NameNotFoundException();
        PackageInfo pi = new PackageInfo(); pi.packageName = pkg; pi.firstInstallTime = a.firstInstall; pi.applicationInfo = getApplicationInfo(pkg, 0); return pi;
    }
    public ApplicationInfo getApplicationInfo(String pkg, int flags) throws NameNotFoundException {
        App a = apps.get(pkg); if (a == null) throw new NameNotFoundException();
        ApplicationInfo ai = new ApplicationInfo(); ai.flags = a.system ? 1 : 0; ai.label = a.label; ai.packageName = pkg; return ai;
    }
    public CharSequence getApplicationLabel(ApplicationInfo ai) { return ai.label; }
    public List<ResolveInfo> queryIntentActivities(android.content.Intent i, int flags) {
        Set<String> out = new LinkedHashSet<>();
        if ("android.intent.action.WEB_SEARCH".equals(i.action)) out.addAll(searchApps);
        else if (i.uri != null) {
            linkLookups++;
            out.addAll(browsers);
            if (i.uri.contains("reddit.com") || i.uri.contains("redd.it")) out.addAll(redditApps);
            for (Map.Entry<String, Set<String>> e : linkHandlers.entrySet()) if (i.uri.contains(e.getKey())) out.addAll(e.getValue());
        }
        List<ResolveInfo> l = new ArrayList<>();
        for (String p : out) { if (!apps.containsKey(p)) continue; ResolveInfo r = new ResolveInfo(); r.activityInfo = new ActivityInfo(); r.activityInfo.packageName = p; l.add(r); }
        return l;
    }
    public int checkSignatures(String a, String b) { return ledgerSigned ? 0 : -3; }
    public InstallSourceInfo getInstallSourceInfo(String pkg) throws NameNotFoundException {
        App a = apps.get(pkg); if (a == null) throw new NameNotFoundException();
        InstallSourceInfo s = new InstallSourceInfo(); s.installer = a.installer; return s;
    }
    public String getInstallerPackageName(String pkg) { App a = apps.get(pkg); return a == null ? null : a.installer; }
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        List<ApplicationInfo> l = new ArrayList<>();
        for (String p : apps.keySet()) { try { l.add(getApplicationInfo(p, 0)); } catch (NameNotFoundException e) { } }
        return l;
    }
    public List<PackageInfo> getInstalledPackages(int flags) {
        List<PackageInfo> l = new ArrayList<>();
        for (String p : apps.keySet()) { try { l.add(getPackageInfo(p, 0)); } catch (NameNotFoundException e) { } }
        return l;
    }
}
