package me.aap.fermata.auto;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
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
			instance.listen();
		});
	}

	/**
	 * Called when the phone is disconnected from the car.
	 */
	static void stop() {
		new Handler(Looper.getMainLooper()).post(() -> {
			MediaSessionCallback.radarMarker = null;
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
		double lat = l.getLatitude();
		double lon = l.getLongitude();
		double cos = Math.cos(Math.toRadians(lat));

		Radar next = null;
		float nextDist = Float.MAX_VALUE;

		for (Radar r : radars) {
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
			if (!r.mine) continue;
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
		try (Reader in = new InputStreamReader(ctx.getAssets().open(ASSET),
				StandardCharsets.US_ASCII)) {
			read(in, false);
		} catch (Throwable ex) {
			Log.e(ex, "Failed to load the speed cameras");
		}

		File f = new File(ctx.getFilesDir(), MY_FILE);
		if (f.isFile()) {
			try (Reader in = new FileReader(f)) {
				read(in, true);
			} catch (Throwable ex) {
				Log.e(ex, "Failed to load the marked speed cameras");
			}
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
				int limit = (s.length > 2) ? Integer.parseInt(s[2].trim()) : 0;
				radars.add(new Radar(Double.parseDouble(s[0].trim()), Double.parseDouble(s[1].trim()),
						limit, mine));
			} catch (NumberFormatException ignore) {
			}
		}
	}

	private void saveMine() {
		File f = new File(ctx.getFilesDir(), MY_FILE);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.US_ASCII)) {
			w.write("# Speed cameras marked with the Radar here button. Columns: lat,lon,max speed\n");
			for (Radar r : radars) {
				if (!r.mine) continue;
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

	private static final class Radar {
		final double lat;
		final double lon;
		final int limit;
		final boolean mine;

		Radar(double lat, double lon, int limit, boolean mine) {
			this.lat = lat;
			this.lon = lon;
			this.limit = limit;
			this.mine = mine;
		}
	}
}
