package me.aap.fermata.auto;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import me.aap.fermata.BuildConfig;
import me.aap.utils.log.Log;

/**
 * Car link (added in the "Youssef Auto" fork of Fermata).
 * <p>
 * The latest car data reported over Android Auto, served as JSON to the owner's computer by
 * {@link CarLinkServer}. Read-only: nothing here can change anything in the car.
 */
final class CarState {
	private static final Map<String, Object> values = new TreeMap<>();
	private static final Map<String, Long> times = new TreeMap<>();
	private static Context ctx;
	private static CarLinkServer server;
	private static boolean connected;
	private static long connectedTime;
	private static String sensors = "";

	private CarState() {
	}

	/**
	 * Starts the car link server once. It keeps running while the app is alive, so the last known
	 * data can still be read after the phone is disconnected from the car.
	 */
	static synchronized void init(Context c) {
		if (ctx == null) ctx = c.getApplicationContext();
		if (server != null) return;
		String token = BuildConfig.CAR_TOKEN;
		if ((token == null) || (token.length() < 16)) return;
		CarLinkServer s = new CarLinkServer(CarLinkServer.PORT, token, CarState::toJson);
		try {
			s.start();
			server = s;
			Log.i("Car link server started on port ", CarLinkServer.PORT);
		} catch (Throwable ex) {
			Log.e(ex, "Failed to start the car link server");
		}
	}

	static synchronized void setConnected(boolean c, String supportedSensors) {
		connected = c;
		if (c) {
			connectedTime = System.currentTimeMillis();
			sensors = (supportedSensors == null) ? "" : supportedSensors;
		}
	}

	static synchronized void put(String key, Object value) {
		if (value == null) return;
		values.put(key, value);
		times.put(key, System.currentTimeMillis());
	}

	static synchronized String toJson() {
		long now = System.currentTimeMillis();
		StringBuilder b = new StringBuilder(1024);
		b.append('{');
		field(b, "app", "Youssef Auto").append(',');
		field(b, "time", now).append(',');
		field(b, "connected", connected).append(',');
		field(b, "connected_time", (connectedTime == 0) ? null : connectedTime).append(',');
		field(b, "sensors", sensors).append(',');

		b.append("\"car\":{");
		boolean first = true;
		for (Map.Entry<String, Object> e : values.entrySet()) {
			if (!first) b.append(',');
			first = false;
			Long t = times.get(e.getKey());
			b.append('"').append(esc(e.getKey())).append("\":{");
			field(b, "value", e.getValue()).append(',');
			field(b, "age_sec", (t == null) ? null : (now - t) / 1000);
			b.append('}');
		}
		b.append("},");

		phone(b);
		b.append('}');
		return b.toString();
	}

	private static void phone(StringBuilder b) {
		b.append("\"phone\":{");
		Integer battery = null;
		Boolean charging = null;

		try {
			Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
			if (i != null) {
				int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
				int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
				if ((level >= 0) && (scale > 0)) battery = Math.round(level * 100f / scale);
				int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
				charging = plugged != 0;
			}
		} catch (Throwable ex) {
			Log.d(ex, "Failed to read the phone battery");
		}

		field(b, "battery_percent", battery).append(',');
		field(b, "charging", charging).append(',');
		b.append("\"location\":");
		location(b);
		b.append('}');
	}

	private static void location(StringBuilder b) {
		Location best = null;

		try {
			if ((ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PERMISSION_GRANTED)
					|| (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
					PERMISSION_GRANTED)) {
				LocationManager lm = ctx.getSystemService(LocationManager.class);
				if (lm != null) {
					for (String p : lm.getProviders(true)) {
						Location l = lm.getLastKnownLocation(p);
						if ((l != null) && ((best == null) || (l.getTime() > best.getTime()))) best = l;
					}
				}
			}
		} catch (Throwable ex) {
			Log.d(ex, "Failed to read the location");
		}

		if (best == null) {
			b.append("null");
			return;
		}

		b.append('{');
		field(b, "lat", best.getLatitude()).append(',');
		field(b, "lon", best.getLongitude()).append(',');
		field(b, "accuracy_m", Math.round(best.getAccuracy())).append(',');
		field(b, "age_sec", (System.currentTimeMillis() - best.getTime()) / 1000).append(',');
		field(b, "map", String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f",
				best.getLatitude(), best.getLongitude()));
		b.append('}');
	}

	private static StringBuilder field(StringBuilder b, String name, Object value) {
		b.append('"').append(esc(name)).append("\":");
		if (value == null) b.append("null");
		else if (value instanceof Boolean) b.append(value);
		else if ((value instanceof Float) || (value instanceof Double)) {
			double d = ((Number) value).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
			else b.append(String.format(Locale.US, "%.2f", d));
		} else if (value instanceof Number) b.append(value);
		else b.append('"').append(esc(value.toString())).append('"');
		return b;
	}

	private static String esc(String s) {
		StringBuilder b = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
					else b.append(c);
				}
			}
		}
		return b.toString();
	}
}
