package me.aap.fermata.auto;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.aap.fermata.R;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;

/**
 * Speed camera alerts (added in the "Youssef Auto" fork of Fermata).
 * <p>
 * Uses the phone GPS and a list of speed cameras: the cameras in Egypt from OpenStreetMap
 * (assets/radars.csv, (c) OpenStreetMap contributors, ODbL) plus the cameras marked by the user
 * with the "Radar here" button (files/my_radars.csv). When a camera is ahead on the road, the
 * distance and the speed limit are spoken through the car speakers.
 * Read-only: nothing here changes anything in the car.
 */
final class RadarAlerts implements LocationListener {
	private static final String ASSET = "radars.csv";
	private static final String MY_FILE = "my_radars.csv";
	private static final String CHECKPOINT_FILE = "my_checkpoints.csv";
	// A checkpoint (mobile camera or police) is kept for 3 hours
	private static final long CHECKPOINT_TTL = 3L * 3600_000;
	private static final float CHECKPOINT_UNMARK_DIST = 150f;
	// A camera is "ahead" when it is within this angle of the driving direction
	private static final float AHEAD_ANGLE = 35f;
	// Below this speed (m/s, ~11 km/h) nothing is spoken
	private static final float MIN_SPEED = 3f;
	// Distance for the second warning, when driving faster than the limit
	private static final float CLOSE_DIST = 250f;
	// The same camera is not announced again within this time
	private static final long REPEAT = 4 * 60_000;
	// A marked camera closer than this to the current location is removed instead of added
	private static final float UNMARK_DIST = 80f;
	private static final double EARTH_R = 6_371_000d;
	// The camera list downloaded from OpenStreetMap, replaces the one in the assets
	private static final String ONLINE_FILE = "radars_online.csv";
	// Download a fresh list when the current one is older than this
	private static final long UPDATE_EVERY = 7L * 24 * 3600_000;
	// Wait this long before trying again after a failed download
	private static final long RETRY_AFTER = 6L * 3600_000;
	// A downloaded list with fewer cameras is treated as broken and ignored
	private static final int MIN_ONLINE = 100;
	// Radarbot (free and paid). When one is installed it alerts for the known cameras
	private static final String[] RADARBOT = {"com.vialsoft.radarbot_free", "com.vialsoft.radarbot"};
	private static final long RADARBOT_CHECK = 60_000;
	// Egypt. Only this box is sent to the server, never the car location.
	private static final String QUERY = "[out:csv(::lat,::lon,maxspeed;false;\",\")][timeout:120];" +
			"node[\"highway\"=\"speed_camera\"](21.9,24.6,31.8,37.0);out;";
	private static final String[] SERVERS = {
			"https://overpass-api.de/api/interpreter",
			"https://overpass.private.coffee/api/interpreter",
			"https://maps.mail.ru/osm/tools/overpass/api/interpreter"};
	private static long lastAttempt;
	private static boolean updating;
	private static RadarAlerts instance;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Context ctx;
	private final List<Radar> radars = new ArrayList<>();
	private final Map<Radar, Long> announced = new HashMap<>();
	private final Map<Radar, Long> warned = new HashMap<>();
	private LocationManager lm;
	private Location last;
	private Location prev;
	private boolean listening;
	private long radarbotChecked;
	private boolean radarbot;

	private RadarAlerts(Context ctx) {
		this.ctx = ctx.getApplicationContext();
		load();
	}

	/**
	 * Called when the phone is connected to the car.
	 */
	static void start(Context c) {
		new Handler(Looper.getMainLooper()).post(() -> {
			if (instance == null) instance = new RadarAlerts(c);
			MediaSessionCallback.radarMarker = RadarAlerts::markHere;
			MediaSessionCallback.checkpointMarker = RadarAlerts::checkpointHere;
			instance.listen();
		});
	}

	/**
	 * Called when the phone is disconnected from the car.
	 */
	static void stop() {
		new Handler(Looper.getMainLooper()).post(() -> {
			MediaSessionCallback.radarMarker = null;
			MediaSessionCallback.checkpointMarker = null;
			if (instance != null) instance.unlisten();
		});
	}

	/**
	 * Called when a radar setting is changed.
	 */
	static void settingsChanged() {
		new Handler(Looper.getMainLooper()).post(() -> {
			if ((instance != null) && (MediaSessionCallback.radarMarker != null)) instance.listen();
		});
	}

	/**
	 * The "Radar here" button: adds a camera at the current location, or removes a camera marked
	 * before if it is very close.
	 */
	static void markHere() {
		new Handler(Looper.getMainLooper()).post(() -> {
			RadarAlerts r = instance;
			if (r != null) r.mark();
		});
	}

	static void checkpointHere() {
		new Handler(Looper.getMainLooper()).post(() -> {
			RadarAlerts r = instance;
			if (r != null) r.markCheckpoint();
		});
	}

	private static MainActivityPrefs prefs() {
		return MainActivityPrefs.get();
	}

	private static boolean enabled() {
		try {
			return prefs().getBooleanPref(MainActivityPrefs.CAR_RADAR_ALERT);
		} catch (Throwable ex) {
			return false;
		}
	}

	@SuppressLint("MissingPermission")
	private void listen() {
		if (!enabled()) {
			unlisten();
			return;
		}
		maybeUpdate();
		if (listening) return;

		if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) {
			Log.w("Radar alerts: the location permission is not granted");
			toast(R.string.car_radar_no_location);
			return;
		}

		try {
			lm = ctx.getSystemService(LocationManager.class);
			if (lm == null) return;
			lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this,
					Looper.getMainLooper());
			listening = true;
			Log.i("Radar alerts started. Cameras: ", radars.size());
		} catch (Throwable ex) {
			Log.e(ex, "Radar alerts: failed to start the GPS");
			toast(R.string.car_radar_no_location);
		}
	}

	private void unlisten() {
		if (!listening) return;
		listening = false;
		try {
			lm.removeUpdates(this);
		} catch (Throwable ex) {
			Log.d(ex, "Radar alerts: failed to stop the GPS");
		}
		announced.clear();
		warned.clear();
		prev = null;
		Log.i("Radar alerts stopped");
	}

	@Override
	public void onLocationChanged(Location l) {
		try {
			check(l);
		} catch (Throwable ex) {
			Log.d(ex, "Radar alert failed");
		}
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
	}

	@Override
	public void onProviderEnabled(String provider) {
	}

	@Override
	public void onProviderDisabled(String provider) {
	}

	private void check(Location l) {
		last = l;
		float heading;

		if (l.hasBearing() && l.hasSpeed() && (l.getSpeed() >= MIN_SPEED)) {
			heading = l.getBearing();
			prev = l;
		} else if ((prev != null) && (prev.distanceTo(l) > 15)) {
			heading = prev.bearingTo(l);
			prev = l;
		} else {
			if (prev == null) prev = l;
			return;
		}

		float speed = l.hasSpeed() ? l.getSpeed() : 0f;
		if (speed < MIN_SPEED) return;
		int speedKmh = Math.round(speed * 3.6f);

		MainActivityPrefs p = prefs();
		int dist = Math.max(100, p.getIntPref(MainActivityPrefs.CAR_RADAR_DISTANCE));
		// Give at least ~12 seconds to slow down
		float alertDist = Math.min(1500f, Math.max(dist, speed * 12f));
		boolean onlySpeeding = p.getBooleanPref(MainActivityPrefs.CAR_RADAR_ONLY_SPEEDING);
		long now = System.currentTimeMillis();
		// Radarbot alerts for the known cameras, so only the ones marked here are spoken
		boolean onlyMine = p.getBooleanPref(MainActivityPrefs.CAR_RADAR_RADARBOT) && radarbot(now);
		double lat = l.getLatitude();
		double lon = l.getLongitude();
		double cos = Math.cos(Math.toRadians(lat));

		if (removeExpired(now)) saveCheckpoints();

		Radar next = null;
		float nextDist = Float.MAX_VALUE;

		for (Radar r : radars) {
			if (onlyMine && !r.mine) continue;
			// Fast rough distance first
			double dy = Math.toRadians(r.lat - lat) * EARTH_R;
			double dx = Math.toRadians(r.lon - lon) * EARTH_R * cos;
			double d = Math.sqrt(dx * dx + dy * dy);
			if (d > alertDist) continue;
			float bearing = (float) Math.toDegrees(Math.atan2(dx, dy));
			if (angle(heading, bearing) > AHEAD_ANGLE) continue;
			if (d < nextDist) {
				nextDist = (float) d;
				next = r;
			}
		}

		if (next == null) return;
		boolean speeding = (next.limit > 0) && (speedKmh > next.limit);

		Long t = announced.get(next);
		if ((t == null) || ((now - t) > REPEAT)) {
			announced.put(next, now);
			if (onlySpeeding && (next.limit > 0) && !speeding) return;
			int meters = Math.max(50, Math.round(nextDist / 50f) * 50);
			if (next.expires != 0) {
				CarAlerts.say(ctx.getString(R.string.car_say_checkpoint, meters));
				return;
			}
			CarAlerts.say((next.limit > 0) ? ctx.getString(R.string.car_say_radar_limit, meters,
					next.limit) : ctx.getString(R.string.car_say_radar, meters));
			return;
		}

		if (speeding && (nextDist <= CLOSE_DIST)) {
			Long w = warned.get(next);
			if ((w == null) || ((now - w) > REPEAT)) {
				warned.put(next, now);
				CarAlerts.say(ctx.getString(R.string.car_say_radar_slow, next.limit));
			}
		}
	}

	private static float angle(float a, float b) {
		float d = Math.abs(a - b) % 360f;
		return (d > 180f) ? 360f - d : d;
	}

	private void mark() {
		Location l = last;
		if ((l == null) || ((System.currentTimeMillis() - l.getTime()) > 30_000)) {
			if (!listening) listen();
			toast(R.string.car_radar_no_location);
			return;
		}

		Radar near = null;
		float nearDist = Float.MAX_VALUE;
		for (Radar r : radars) {
			if (!r.mine || (r.expires != 0)) continue;
			float[] d = new float[1];
			Location.distanceBetween(l.getLatitude(), l.getLongitude(), r.lat, r.lon, d);
			if ((d[0] < UNMARK_DIST) && (d[0] < nearDist)) {
				nearDist = d[0];
				near = r;
			}
		}

		if (near != null) {
			radars.remove(near);
			announced.remove(near);
			warned.remove(near);
			CarAlerts.say(ctx.getString(R.string.car_say_radar_removed));
		} else {
			Radar r = new Radar(l.getLatitude(), l.getLongitude(), 0, true);
			radars.add(r);
			// Do not announce the camera that was just marked
			announced.put(r, System.currentTimeMillis());
			CarAlerts.say(ctx.getString(R.string.car_say_radar_marked));
		}

		saveMine();
	}

	private void load() {
		radars.clear();
		announced.clear();
		warned.clear();
		File online = new File(ctx.getFilesDir(), ONLINE_FILE);
		boolean loaded = false;

		if (online.isFile()) {
			try (Reader in = new FileReader(online)) {
				read(in, false);
				loaded = radars.size() >= MIN_ONLINE;
			} catch (Throwable ex) {
				Log.e(ex, "Failed to load the downloaded speed cameras");
			}
			if (!loaded) radars.clear();
		}

		if (!loaded) {
			try (Reader in = new InputStreamReader(ctx.getAssets().open(ASSET),
					StandardCharsets.US_ASCII)) {
				read(in, false);
			} catch (Throwable ex) {
				Log.e(ex, "Failed to load the speed cameras");
			}
		}

		CarState.put("radar_count", radars.size());
		CarState.put("radar_list_date", loaded ?
				String.format(Locale.US, "%tF (downloaded)", online.lastModified()) :
				"2026-11-15 (built in)");

		File f = new File(ctx.getFilesDir(), MY_FILE);
		if (f.isFile()) {
			try (Reader in = new FileReader(f)) {
				read(in, true);
			} catch (Throwable ex) {
				Log.e(ex, "Failed to load the marked speed cameras");
			}
		}

		loadCheckpoints();
	}

	private void loadCheckpoints() {
		File f = new File(ctx.getFilesDir(), CHECKPOINT_FILE);
		if (!f.isFile()) return;
		long now = System.currentTimeMillis();
		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				if (line.startsWith("#")) continue;
				String[] s = line.trim().split(",");
				if (s.length < 3) continue;
				try {
					long exp = Long.parseLong(s[2].trim());
					if ((exp <= now) || (exp > now + CHECKPOINT_TTL)) continue;
					radars.add(new Radar(Double.parseDouble(s[0].trim()), Double.parseDouble(s[1].trim()),
							0, true, exp));
				} catch (NumberFormatException ignore) {
				}
			}
		} catch (Throwable ex) {
			Log.e(ex, "Failed to load the checkpoints");
		}
	}

	private boolean removeExpired(long now) {
		boolean removed = false;
		for (java.util.Iterator<Radar> it = radars.iterator(); it.hasNext(); ) {
			Radar r = it.next();
			if ((r.expires != 0) && (r.expires <= now)) {
				it.remove();
				announced.remove(r);
				warned.remove(r);
				removed = true;
			}
		}
		return removed;
	}

	private void markCheckpoint() {
		Location l = last;
		long now = System.currentTimeMillis();
		if ((l == null) || ((now - l.getTime()) > 30_000)) {
			if (!listening) listen();
			toast(R.string.car_radar_no_location);
			return;
		}
		removeExpired(now);

		Radar near = null;
		float nearDist = Float.MAX_VALUE;
		for (Radar r : radars) {
			if (r.expires == 0) continue;
			float[] d = new float[1];
			Location.distanceBetween(l.getLatitude(), l.getLongitude(), r.lat, r.lon, d);
			if ((d[0] < CHECKPOINT_UNMARK_DIST) && (d[0] < nearDist)) {
				nearDist = d[0];
				near = r;
			}
		}

		if (near != null) {
			radars.remove(near);
			announced.remove(near);
			warned.remove(near);
			CarAlerts.say(ctx.getString(R.string.car_say_checkpoint_removed));
		} else {
			Radar r = new Radar(l.getLatitude(), l.getLongitude(), 0, true, now + CHECKPOINT_TTL);
			radars.add(r);
			// Do not announce the checkpoint that was just marked
			announced.put(r, now);
			CarAlerts.say(ctx.getString(R.string.car_say_checkpoint_marked));
		}

		saveCheckpoints();
	}

	private void saveCheckpoints() {
		File f = new File(ctx.getFilesDir(), CHECKPOINT_FILE);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.US_ASCII)) {
			w.write("# Checkpoints marked with the Checkpoint button. Columns: lat,lon,expires (ms)\n");
			for (Radar r : radars) {
				if (r.expires == 0) continue;
				w.write(String.format(Locale.US, "%.6f,%.6f,%d\n", r.lat, r.lon, r.expires));
			}
		} catch (Throwable ex) {
			Log.e(ex, "Failed to save the checkpoints");
		}
	}

	private void read(Reader in, boolean mine) throws Exception {
		BufferedReader r = new BufferedReader(in);
		for (String line = r.readLine(); line != null; line = r.readLine()) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			String[] s = line.split(",");
			if (s.length < 2) continue;
			try {
				double lat = Double.parseDouble(s[0].trim());
				double lon = Double.parseDouble(s[1].trim());
				if ((lat < -90) || (lat > 90) || (lon < -180) || (lon > 180)) continue;
				radars.add(new Radar(lat, lon, (s.length > 2) ? limit(s[2]) : 0, mine));
			} catch (NumberFormatException ignore) {
			}
		}
	}

	/**
	 * The leading number of an OpenStreetMap max speed, e.g. "80" or "60 mph". 0 if unknown.
	 */
	private static int limit(String s) {
		s = s.trim();
		int n = 0;
		int i = 0;
		for (; (i < s.length()) && (i < 3) && Character.isDigit(s.charAt(i)); i++) {
			n = n * 10 + (s.charAt(i) - '0');
		}
		if (n == 0) return 0;
		if (s.substring(i).trim().startsWith("mph")) n = Math.round(n * 1.609f);
		return n;
	}

	/**
	 * Downloads a fresh camera list from OpenStreetMap once a week, in the background. The new
	 * list is used only if it looks complete; otherwise the current one is kept.
	 */
	private void maybeUpdate() {
		try {
			if (!prefs().getBooleanPref(MainActivityPrefs.CAR_RADAR_AUTO_UPDATE)) return;
		} catch (Throwable ex) {
			return;
		}
		long now = System.currentTimeMillis();
		File online = new File(ctx.getFilesDir(), ONLINE_FILE);
		if (updating || ((now - lastAttempt) < RETRY_AFTER)) return;
		if (online.isFile() && ((now - online.lastModified()) < UPDATE_EVERY)) return;
		updating = true;
		lastAttempt = now;

		Thread t = new Thread(() -> {
			boolean ok = false;
			try {
				ok = download(online);
			} catch (Throwable ex) {
				Log.d(ex, "Speed camera list download failed");
			}
			boolean done = ok;
			handler.post(() -> {
				updating = false;
				if (done) load();
			});
		}, "RadarListUpdate");
		t.setDaemon(true);
		t.start();
	}

	private boolean download(File dest) throws Exception {
		String body = "data=" + java.net.URLEncoder.encode(QUERY, "UTF-8");

		for (String server : SERVERS) {
			java.net.HttpURLConnection c = null;
			try {
				c = (java.net.HttpURLConnection) new java.net.URL(server).openConnection();
				c.setConnectTimeout(20_000);
				c.setReadTimeout(150_000);
				c.setRequestMethod("POST");
				c.setDoOutput(true);
				// A generic name - nothing about the owner is sent
				c.setRequestProperty("User-Agent", "car-radar-list/1.0");
				c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
				c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
				if (c.getResponseCode() != 200) {
					Log.i("Speed camera list: HTTP ", c.getResponseCode(), " from ", server);
					continue;
				}

				StringBuilder sb = new StringBuilder(32 * 1024);
				int rows = 0;
				sb.append("# Speed cameras in Egypt. Data (c) OpenStreetMap contributors, ODbL 1.0 - ")
						.append("https://www.openstreetmap.org/copyright\n");
				try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
						StandardCharsets.UTF_8))) {
					for (String line = r.readLine(); line != null; line = r.readLine()) {
						String[] s = line.split(",");
						if (s.length < 2) continue;
						try {
							double lat = Double.parseDouble(s[0].trim());
							double lon = Double.parseDouble(s[1].trim());
							int lim = (s.length > 2) ? limit(s[2]) : 0;
							sb.append(String.format(Locale.US, "%.6f,%.6f,%d\n", lat, lon, lim));
							rows++;
						} catch (NumberFormatException ignore) {
						}
					}
				}

				if (rows < MIN_ONLINE) {
					Log.i("Speed camera list: only ", rows, " cameras from ", server, " - ignored");
					continue;
				}

				File tmp = new File(dest.getPath() + ".tmp");
				try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp),
						StandardCharsets.US_ASCII)) {
					w.write(sb.toString());
				}
				if (!tmp.renameTo(dest)) {
					//noinspection ResultOfMethodCallIgnored
					tmp.delete();
					return false;
				}
				Log.i("Speed camera list updated from ", server, ". Cameras: ", rows);
				return true;
			} catch (Throwable ex) {
				Log.i("Speed camera list: ", server, " failed: ", ex);
			} finally {
				if (c != null) c.disconnect();
			}
		}
		return false;
	}

	private void saveMine() {
		File f = new File(ctx.getFilesDir(), MY_FILE);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.US_ASCII)) {
			w.write("# Speed cameras marked with the Radar here button. Columns: lat,lon,max speed\n");
			for (Radar r : radars) {
				if (!r.mine || (r.expires != 0)) continue;
				w.write(String.format(Locale.US, "%.6f,%.6f,%d\n", r.lat, r.lon, r.limit));
			}
		} catch (Throwable ex) {
			Log.e(ex, "Failed to save the marked speed cameras");
		}
	}

	private void toast(int msg) {
		handler.post(() -> {
			try {
				Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
			} catch (Throwable ignore) {
			}
		});
	}

	private boolean radarbot(long now) {
		if ((now - radarbotChecked) < RADARBOT_CHECK) return radarbot;
		radarbotChecked = now;
		boolean found = false;
		PackageManager pm = ctx.getPackageManager();
		for (String pkg : RADARBOT) {
			try {
				if (pm.getApplicationInfo(pkg, 0).enabled) found = true;
			} catch (PackageManager.NameNotFoundException ignore) {
			}
		}
		if (found != radarbot) CarState.put("radar_alerts_by", found ? "radarbot" : "app");
		radarbot = found;
		return found;
	}

	private static final class Radar {
		final double lat;
		final double lon;
		final int limit;
		final boolean mine;
		// 0 for a camera, the expiry time for a temporary checkpoint
		final long expires;

		Radar(double lat, double lon, int limit, boolean mine) {
			this(lat, lon, limit, mine, 0);
		}

		Radar(double lat, double lon, int limit, boolean mine, long expires) {
			this.lat = lat;
			this.lon = lon;
			this.limit = limit;
			this.mine = mine;
			this.expires = expires;
		}
	}
}
