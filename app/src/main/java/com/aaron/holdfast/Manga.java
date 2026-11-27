package com.aaron.holdfast;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as manga: reading apps (manga, manhwa, manhua, webtoons, comics) and reader sites.
 *
 * No list of names stays complete, so an app is recognised three ways: by what it calls itself,
 * by a list of known readers whose names don't say it, and by what it does (it opens links to a
 * reader site itself without being a general browser). Sites are recognised by their address.
 * General book apps (Kindle, Play Books) are not readers in this sense and are left alone.
 */
final class Manga {

    private Manga() {
    }

    /** Words in an app's name or id that mark a reader. */
    private static final String[] APP_WORDS = {
            "manga", "manhwa", "manhua", "webtoon", "doujin", "comic", "comix", "komik",
            "tachiyomi", "mihon", "aniyomi", "kotatsu", "cdisplay", "yacreader", "kuro reader",
            "kuroreader", "suwayomi", "tachidesk",
            "\u6f2b\u753b", "\u6f2b\u756b", "\u30de\u30f3\u30ac", "\u307e\u3093\u304c",
            "\u30b3\u30df\u30c3\u30af", "\ub9cc\ud654", "\uc6f9\ud230"};

    /** Words in a site's name that mark a reader site. */
    private static final String[] SITE_WORDS = {
            "manga", "manhwa", "manhua", "webtoon", "doujin", "hentai", "comick", "readcomic"};

    /**
     * Longer words that only contain "manga" by accident: places and names, a metal, a school
     * site, and words that end in "man" followed by one that starts with "ga" (hangman game).
     */
    private static final String[] NOT_MANGA = {
            "mangalor", "mangalur", "mangalam", "mangaldeep", "mangalsutra", "mangalya",
            "manganese", "mangahigh", "mangaming", "mangallery", "mangarden", "mangarage",
            "mangadget"};
    /** "...man game(s)" run together, as the end of a word only (a reader may be called MangaMe...). */
    private static final Pattern MAN_GAME = Pattern.compile("mangames?(?![a-z])");

    /** Readers whose name and id don't say what they are. */
    private static final Set<String> KNOWN_APPS = new HashSet<>(Arrays.asList(
            "com.tapastic",                    // Tapas
            "com.toomics.global.google",       // Toomics
            "com.contentsfirst.tappytoon",     // Tappytoon
            "com.comikey.banagher",            // Comikey
            "co.azuki.app",                    // Omoi (was Azuki)
            "com.viz.wsj.android",             // Shonen Jump
            "com.viz.wsjalpha",
            "net.manta.manta",                 // Manta
            "app.game.rentaapps",              // Renta!
            "com.mrblue.mrblue",               // Mr. Blue
            "com.initialcoms.ridi",            // Ridi
            "jp.kakao.piccoma",                // Piccoma
            "com.kakao.page",                  // KakaoPage
            "com.endyq.trackkun",              // MangaPin
            "com.rookiestudio.perfectviewer",  // Perfect Viewer
            "com.rookiestudio.perfectviewerx",
            "ani.dantotsu",                    // Dantotsu
            "ani.saikou"));                    // Saikou

    /** Reader sites whose address doesn't carry one of the words above. */
    private static final String[] SITES = {
            "tapas.io", "viz.com", "toomics.com", "tappytoon.com", "manta.net", "lezhinus.com",
            "lezhin.com", "inkr.com", "comikey.com", "omoi.com", "azuki.co", "piccoma.com",
            "bookwalker.jp", "bookwalker.com", "globalcomix.com", "pocketcomics.com",
            "copincomics.com", "bilibilicomics.com", "webcomicsapp.com", "toonsutra.com",
            "bato.to", "batotoo.com", "battwo.com", "mto.to", "wto.to", "zbato.org", "xbato.com",
            "weebcentral.com", "asuracomic.net", "asuratoon.com", "flamecomics.xyz",
            "flamecomics.com", "reapercomics.com", "toonily.com", "toonily.me", "toongod.org",
            "manytoon.com", "hiperdex.com", "readm.today", "comix.to", "mgeko.cc", "cubari.moe",
            "tcbonepiecechapters.com", "shonenjumpplus.com", "comic-days.com", "comic-walker.com",
            "pocket.shonenmagazine.com", "tonarinoyj.jp", "sunday-webry.com", "comic.pixiv.net",
            "comic.naver.com", "page.kakao.com", "ridibooks.com", "ac.qq.com", "atsu.moe",
            "kagane.org", "hivetoon.com", "vortextoon.com", "mto.to", "dto.to", "hto.to"};

    /** Addresses a reader app answers for, used to recognise such an app by what it does. */
    private static final String[] LINK_HOSTS = {
            "www.webtoons.com", "m.webtoons.com", "tapas.io", "mangaplus.shueisha.co.jp",
            "www.viz.com", "mangadex.org", "toomics.com", "www.tappytoon.com", "manta.net",
            "www.lezhinus.com", "www.lezhin.com", "inkr.com", "comikey.com", "www.omoi.com",
            "www.azuki.co", "piccoma.com", "global.bookwalker.jp", "bookwalker.jp",
            "kmanga.kodansha.com", "global.manga-up.com", "mangatoon.mobi",
            "www.bilibilicomics.com", "www.pocketcomics.com", "www.copincomics.com",
            "www.webcomicsapp.com", "globalcomix.com", "bato.to", "comic.naver.com",
            "page.kakao.com", "ridibooks.com", "shonenjumpplus.com", "manga.line.me"};
    /** Apps claim different parts of a site, so each address is tried in a few shapes. */
    private static final String[] LINK_PATHS = {"/", "/en/", "/series/1", "/title/1"};

    /** True if the text (lower case) carries one of the words, apart from the accidents. */
    private static boolean says(String text, String[] words) {
        for (String w : NOT_MANGA) {
            if (text.contains(w)) text = text.replace(w, " ");
        }
        if (text.contains("mangame")) text = MAN_GAME.matcher(text).replaceAll(" ");
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /** A reading app, judged by its id and the name it shows. */
    static boolean isApp(String pkg, String label) {
        if (pkg == null) return false;
        if (KNOWN_APPS.contains(pkg)) return true;
        if (says(pkg.toLowerCase(Locale.ROOT), APP_WORDS)) return true;
        return label != null && says(label.toLowerCase(Locale.ROOT), APP_WORDS);
    }

    /**
     * A reader site, judged by the site name alone (no scheme, path or query). Also true for
     * one reached through a translating or caching address.
     */
    static boolean isSite(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.indexOf('.') <= 0 || h.indexOf(' ') >= 0) return false;
        if (says(h, SITE_WORDS)) return true;
        for (String part : h.split("\\.")) {
            // Translation groups name their sites "somethingscans".
            if (part.length() > 5 && part.endsWith("scans")) return true;
        }
        for (String s : SITES) {
            if (h.equals(s) || h.endsWith("." + s)) return true;
            String dashed = s.replace("-", "--").replace('.', '-');
            if (h.endsWith(dashed + ".translate.goog") || h.endsWith(dashed + ".cdn.ampproject.org")) {
                return true;
            }
        }
        return false;
    }

    // ---------- reader pages, recognised without their address ----------

    /** Words a reader page uses about itself. */
    private static final String[] PAGE_WORDS = {
            "manga", "manhwa", "manhua", "webtoon", "scanlation", "doujin"};
    /** The words on their own: what an article or a shop calls the thing, not the name of a site. */
    private static final Set<String> BARE_WORDS = new HashSet<>(Arrays.asList(
            "manga", "mangas", "manhwa", "manhwas", "manhua", "manhuas", "webtoon", "webtoons",
            "mangaka", "mangakas", "doujin", "doujins", "doujinshi", "hentai"));
    /** Reader sites by the name they sign their page titles with, where the name lacks the word. */
    private static final Set<String> TITLE_NAMES = new HashSet<>(Arrays.asList(
            "manga plus", "manga plus by shueisha", "mangaplus by shueisha", "k manga", "manga up!",
            "bato.to", "batoto", "comick", "asura scans", "asura comics", "reaper scans",
            "flame comics", "flame scans", "weeb central", "toonily", "tappytoon", "toomics",
            "lezhin comics", "pocket comics", "inkr comics", "bilibili comics", "comikey",
            "webcomics"));
    /**
     * Sites whose pages are titled "something - Site" for any something: a search for a reader
     * site, a video or an article about one is not that reader site.
     */
    private static final String[] TITLE_HOSTS = {
            "search", "google", "bing", "duckduckgo", "yandex", "ecosia", "startpage", "qwant",
            "youtube", "wikipedia", "wiktionary", "fandom", "twitter", "facebook", "instagram",
            "tiktok", "pinterest", "amazon", "github", "google play", "app store", "quora",
            "linkedin", "imdb", "myanimelist", "anilist", "trustpilot"};
    private static final Pattern TITLE_SPLIT = Pattern.compile("\\s+[-|\u2013\u2014\u00b7\u00bb]+\\s+|\\s*:+\\s+");

    /**
     * True for a page title signed by a reader site: "One Piece - MangaDex", "MangaFire - Read
     * Manga Online", "Tower of God | WEBTOON". A title that merely uses the word ("Manga -
     * Wikipedia", "Best manga of the year") is not.
     */
    static boolean isReaderTitle(String title) {
        if (title == null) return false;
        String t = title.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.length() > 300) return false;
        String[] parts = TITLE_SPLIT.split(t);
        if (parts.length == 0) return false;
        String last = parts[parts.length - 1].trim();
        if (isSiteName(last, parts.length > 1)) return true;
        if (parts.length < 2) return false;
        for (String host : TITLE_HOSTS) {
            if (last.contains(host)) return false;
        }
        return isSiteName(parts[0].trim(), false);
    }

    private static boolean isSiteName(String part, boolean lastOfSeveral) {
        if (part.isEmpty() || part.length() > 40) return false;
        if (TITLE_NAMES.contains(part)) return true;
        if (part.indexOf(' ') >= 0) return false;
        if (BARE_WORDS.contains(part)) {
            // "Tower of God | WEBTOON" is the site; "Webtoon - Wikipedia" is an article about them.
            return lastOfSeveral && part.startsWith("webtoon");
        }
        return says(part, SITE_WORDS);
    }

    private static final Pattern LEADING_MARKS = Pattern.compile("^[^a-z0-9]+");
    private static final Pattern TURN = Pattern.compile(
            "^(?:read\\s+)?(next|prev|previous|first|last|latest|newest)\\s+(?:chapter|chap|ch)\\b");
    private static final Pattern CHAPTER_LIST = Pattern.compile("^(?:all\\s+chapters|chapters?\\s+list)\\b");
    private static final Pattern CHAPTER = Pattern.compile(
            "^(?:chapter|chap\\.?|ch\\.?)\\s*#?(\\d{1,4}(?:\\.\\d+)?)(?!\\d)");

    /**
     * What a web page shows of being a reader page, for sites whose name says nothing: it uses
     * the word, and it has chapter-turning links or a run of numbered chapters. An article, a
     * shop page or a search that only mentions manga has the word but not the rest; an online
     * book with chapters has the rest but not the word.
     */
    static final class PageSigns {
        private boolean word;
        private final Set<String> turns = new HashSet<>();
        private final Set<String> chapters = new HashSet<>();

        /** Takes one piece of text from the page (lower case; may be null). */
        void read(String t) {
            if (t == null || t.isEmpty() || t.length() > 80) return;
            if (!word && says(t, PAGE_WORDS)) word = true;
            if (t.length() > 60) return;
            String s = LEADING_MARKS.matcher(t).replaceFirst("");
            Matcher m = TURN.matcher(s);
            if (m.find()) {
                turns.add(m.group(1).startsWith("prev") ? "prev" : m.group(1));
                return;
            }
            if (CHAPTER_LIST.matcher(s).find()) {
                turns.add("list");
                return;
            }
            m = CHAPTER.matcher(s);
            if (m.find()) chapters.add(m.group(1));
        }

        boolean isReader() {
            return word && (turns.size() >= 2
                    || (turns.size() >= 1 && chapters.size() >= 1)
                    || chapters.size() >= 8);
        }
    }

    /** Every app that opens ordinary web links or answers a web search: browsers, in effect. */
    static Set<String> webApps(PackageManager pm) {
        Set<String> found = new HashSet<>();
        for (String url : new String[]{"https://example.com/", "http://example.com/"}) {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo ri : pm.queryIntentActivities(web, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null) found.add(ri.activityInfo.packageName);
            }
        }
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_WEB_SEARCH), PackageManager.MATCH_ALL)) {
            if (ri.activityInfo != null) found.add(ri.activityInfo.packageName);
        }
        return found;
    }

    /**
     * Apps that open links to a reader site themselves, without being able to open ordinary web
     * links: reader apps, whatever they are called.
     *
     * @param webApps apps that open any web link (they answer for every site, so they don't count)
     */
    static Set<String> linkApps(PackageManager pm, Set<String> webApps, String self) {
        Set<String> out = new HashSet<>();
        for (String host : LINK_HOSTS) {
            for (String path : LINK_PATHS) {
                Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + host + path))
                        .addCategory(Intent.CATEGORY_BROWSABLE);
                for (ResolveInfo ri : pm.queryIntentActivities(link, PackageManager.MATCH_ALL)) {
                    if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
                }
            }
        }
        out.removeAll(webApps);
        out.remove(self);
        out.remove("android");
        return out;
    }

    /** True for an app that came with the phone. Those are never treated as reading apps. */
    static boolean cameWithPhone(ApplicationInfo ai) {
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    /** Names of the installed apps the rule closes, sorted. For the app's own screen. */
    static List<String> installedApps(PackageManager pm, String self) {
        List<String> out = new ArrayList<>();
        Set<String> links;
        try {
            links = linkApps(pm, webApps(pm), self);
        } catch (Throwable t) {
            links = new HashSet<>();
        }
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai == null || ai.packageName == null || ai.packageName.equals(self)) continue;
            if (cameWithPhone(ai) || MainActivity.LEDGER_PKG.equals(ai.packageName)) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            if (isApp(ai.packageName, label) || links.contains(ai.packageName)) out.add(label);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
