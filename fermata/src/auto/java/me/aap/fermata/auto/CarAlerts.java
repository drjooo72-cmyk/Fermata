package me.aap.fermata.auto;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;

/**
 * Voice alerts (added in the "Youssef Auto" fork of Fermata): speed above the limit, low fuel or
 * battery. Spoken through the car speakers like navigation prompts. Read-only: nothing here
 * changes anything in the car.
 */
final class CarAlerts {
	// Do not repeat the speed alert more often than this
	private static final long SPEED_REPEAT = 60_000;
	// The speed must drop this much below the limit before the alert is armed again
	private static final float SPEED_HYSTERESIS = 5f;
	private static final Handler handler = new Handler(Looper.getMainLooper());
	private static Context ctx;
	private static TextToSpeech tts;
	private static boolean ttsReady;
	private static String pending;
	private static long lastSpeedAlert;
	private static boolean speedArmed = true;
	private static boolean lowEnergyDone;

	private CarAlerts() {
	}

	static void init(Context c) {
		if (ctx == null) ctx = c.getApplicationContext();
	}

	/**
	 * Called when the car is connected or disconnected - every trip starts with fresh alerts.
	 */
	static void reset() {
		handler.post(() -> {
			speedArmed = true;
			lastSpeedAlert = 0;
			lowEnergyDone = false;
		});
	}

	/**
	 * Called with every new car value.
	 */
	static void onValue(String key, Object value) {
		if (!(value instanceof Number) && !(value instanceof Boolean)) return;
		handler.post(() -> {
			try {
				check(key, value);
			} catch (Throwable ex) {
				Log.d(ex, "Car alert failed");
			}
		});
	}

	private static void check(String key, Object value) {
		if (ctx == null) return;
		MainActivityPrefs p = MainActivityPrefs.get();

		switch (key) {
			case "speed_kmh" -> {
				if (!p.getBooleanPref(MainActivityPrefs.CAR_SPEED_ALERT)) return;
				float speed = ((Number) value).floatValue();
				int limit = p.getIntPref(MainActivityPrefs.CAR_SPEED_LIMIT);
				long now = System.currentTimeMillis();
				if (speed < (limit - SPEED_HYSTERESIS)) {
					speedArmed = true;
				} else if ((speed > limit) && speedArmed && ((now - lastSpeedAlert) > SPEED_REPEAT)) {
					speedArmed = false;
					lastSpeedAlert = now;
					say(ctx.getString(R.string.car_say_speed, Math.round(speed)));
				}
			}
			case "fuel_percent", "fuel_percent_2", "battery_percent" -> {
				if (lowEnergyDone || !p.getBooleanPref(MainActivityPrefs.CAR_LOW_ENERGY_ALERT)) return;
				float level = ((Number) value).floatValue();
				// Some cars report 0..1 instead of 0..100
				if ((level > 0f) && (level <= 1f)) level *= 100f;
				if ((level <= 0f) || (level > p.getIntPref(MainActivityPrefs.CAR_LOW_ENERGY_PERCENT))) {
					return;
				}
				lowEnergyDone = true;
				int msg = key.equals("battery_percent") ? R.string.car_say_battery : R.string.car_say_fuel;
				say(ctx.getString(msg, Math.round(level)));
			}
			case "fuel_low", "energy_low" -> {
				if (lowEnergyDone || !Boolean.TRUE.equals(value) ||
						!p.getBooleanPref(MainActivityPrefs.CAR_LOW_ENERGY_ALERT)) {
					return;
				}
				lowEnergyDone = true;
				say(ctx.getString(R.string.car_say_low));
			}
			default -> {
			}
		}
	}

	static void say(String text) {
		Log.i("Car alert: ", text);
		if (tts == null) {
			pending = text;
			tts = new TextToSpeech(ctx, status -> handler.post(() -> {
				if (status != TextToSpeech.SUCCESS) {
					Log.w("Text to speech is not available");
					return;
				}
				ttsReady = true;
				Locale l = Locale.getDefault();
				if (tts.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(l);
				tts.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
				if (pending != null) speak(pending);
				pending = null;
			}));
		} else if (ttsReady) {
			speak(text);
		} else {
			pending = text;
		}
	}

	private static void speak(String text) {
		try {
			tts.speak(text, TextToSpeech.QUEUE_ADD, null, "car_alert");
		} catch (Throwable ex) {
			Log.d(ex, "Failed to speak the car alert");
		}
	}
}
