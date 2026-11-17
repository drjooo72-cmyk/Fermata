package me.aap.fermata.addon.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.tv.m3u.TvM3uFileSystemProvider;
import me.aap.utils.log.Log;

/**
 * Youssef Auto: the owner's IPTV subscription is added to the TV section by itself on the first
 * run. The account comes from the build (a private CI secret), never from the source code.
 * Only the live channels are written to a local playlist, the full provider list is too big for
 * the phone. The playlist is rebuilt once a week.
 */
final class IptvPreset {
	private static final String PREFS = "youssef_iptv";
	private static final String KEY_ADDED = "added";
	private static final String FILE = "iptv_live.m3u";
	private static final String NAME = "IPTV";
	private static final long REFRESH = 7L * 24 * 3600_000;
	private static final long RETRY_AFTER = 3600_000;
	private static final int MIN_CHANNELS = 10;
	private static final Handler handler = new Handler(Looper.getMainLooper());
	private static TvRootItem root;
	private static boolean running;
	private static long lastAttempt;

	private IptvPreset() {
	}

	/**
	 * Called on the main thread when the TV root item is created.
	 */
	static void attach(TvRootItem r) {
		root = r;
		String[] acc = account();
		if (acc == null) return;
		Context ctx = FermataApplication.get();
		File f = file(ctx);
		boolean added = prefs(ctx).getBoolean(KEY_ADDED, false);
		boolean fresh = f.isFile() && ((System.currentTimeMillis() - f.lastModified()) < REFRESH);

		if (fresh) {
			if (!added) addSource(ctx, f);
			return;
		}

		long now = System.currentTimeMillis();
		if (running || ((now - lastAttempt) < RETRY_AFTER)) return;
		running = true;
		lastAttempt = now;

		Thread t = new Thread(() -> {
			boolean ok = false;
			try {
				ok = build(acc, f);
			} catch (Throwable ex) {
				Log.e(ex, "IPTV: failed to build the channel list");
			}
			boolean built = ok;
			handler.post(() -> {
				running = false;
				if (built && !prefs(ctx).getBoolean(KEY_ADDED, false)) addSource(ctx, f);
			});
		}, "IptvPreset");
		t.setDaemon(true);
		t.start();
	}

	private static void addSource(Context ctx, File f) {
		TvRootItem r = root;
		if (r == null) return;
		new TvM3uFileSystemProvider().create(NAME, f.getAbsolutePath()).main().onCompletion((m3u, err) -> {
			if (m3u == null) {
				Log.e(err, "IPTV: failed to add the TV source");
				return;
			}
			// Added once. If the owner removes it later it is not added again.
			prefs(ctx).edit().putBoolean(KEY_ADDED, true).apply();
			r.addSource(m3u);
		});
	}

	/**
	 * @return host, user, password or null if the build has no account.
	 */
	private static String[] account() {
		String b64 = BuildConfig.IPTV_ACCOUNT;
		if ((b64 == null) || b64.isEmpty()) return null;
		try {
			String s = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
			String[] a = s.split("\n");
			if ((a.length < 3) || !a[0].startsWith("http")) return null;
			String host = a[0].trim();
			while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
			return new String[]{host, a[1].trim(), a[2].trim()};
		} catch (Throwable ex) {
			return null;
		}
	}

	private static boolean build(String[] acc, File f) throws Exception {
		String host = acc[0];
		String user = URLEncoder.encode(acc[1], "UTF-8").replace("+", "%20");
		String pass = URLEncoder.encode(acc[2], "UTF-8").replace("+", "%20");
		String api = host + "/player_api.php?username=" + user + "&password=" + pass + "&action=";

		Map<String, String> groups = new HashMap<>();
		JSONArray cats = new JSONArray(get(api + "get_live_categories"));
		for (int i = 0; i < cats.length(); i++) {
			JSONObject c = cats.getJSONObject(i);
			groups.put(c.optString("category_id"), c.optString("category_name"));
		}

		JSONArray streams = new JSONArray(get(api + "get_live_streams"));
		if (streams.length() < MIN_CHANNELS) return false;

		File tmp = new File(f.getPath() + ".tmp");
		int n = 0;
		try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
			w.write("#EXTM3U\n");
			for (int i = 0; i < streams.length(); i++) {
				JSONObject s = streams.getJSONObject(i);
				long id = s.optLong("stream_id", -1);
				if (id < 0) continue;
				String name = clean(s.optString("name"));
				String group = clean(groups.getOrDefault(s.optString("category_id"), ""));
				String logo = clean(s.optString("stream_icon"));
				String epg = clean(s.optString("epg_channel_id"));
				w.write("#EXTINF:-1");
				if (!epg.isEmpty()) w.write(" tvg-id=\"" + epg + '"');
				if (!logo.isEmpty()) w.write(" tvg-logo=\"" + logo + '"');
				if (!group.isEmpty()) w.write(" group-title=\"" + group + '"');
				w.write("," + name + '\n');
				w.write(host + "/live/" + user + '/' + pass + '/' + id + ".ts\n");
				n++;
			}
		}

		if (n < MIN_CHANNELS) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			return false;
		}
		if (!tmp.renameTo(f)) {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
			if (!tmp.renameTo(f)) return false;
		}
		Log.i("IPTV: live channels: ", n);
		return true;
	}

	private static String clean(String s) {
		if ((s == null) || s.equals("null")) return "";
		return s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String get(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		try {
			c.setConnectTimeout(20_000);
			c.setReadTimeout(120_000);
			c.setRequestProperty("User-Agent", "Fermata/" + BuildConfig.VERSION_NAME);
			if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[16384];
				for (int r = in.read(buf); r != -1; r = in.read(buf)) out.write(buf, 0, r);
				return out.toString("UTF-8");
			}
		} finally {
			c.disconnect();
		}
	}

	private static File file(Context ctx) {
		return new File(ctx.getFilesDir(), FILE);
	}

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
