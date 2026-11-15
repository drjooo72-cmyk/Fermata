package me.aap.fermata.auto;

import static android.support.car.hardware.CarSensorEvent.DRIVE_STATUS_UNRESTRICTED;
import static android.support.car.hardware.CarSensorEvent.GEAR_DRIVE;
import static android.support.car.hardware.CarSensorEvent.GEAR_NEUTRAL;
import static android.support.car.hardware.CarSensorEvent.GEAR_PARK;
import static android.support.car.hardware.CarSensorEvent.GEAR_REVERSE;
import static android.support.car.hardware.CarSensorManager.SENSOR_RATE_NORMAL;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_CAR_SPEED;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_DRIVING_STATUS;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_ENVIRONMENT;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_FUEL_LEVEL;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_NIGHT;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_ODOMETER;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_RPM;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_GEAR;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_PARKING_BRAKE;
import static me.aap.fermata.media.service.MediaSessionCallback.VIDEO_ALLOWED;
import static me.aap.fermata.media.service.MediaSessionCallback.VIDEO_RESTRICTED_MOVING;
import static me.aap.fermata.media.service.MediaSessionCallback.VIDEO_RESTRICTED_UNKNOWN;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.car.Car;
import android.support.car.CarConnectionCallback;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.widget.Toast;

import java.util.List;

import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * Parked-only video (added in the "Youssef Auto" fork of Fermata).
 * <p>
 * Watches the driving state reported by the head unit over Android Auto and tells the player
 * whether video may be shown. Video is allowed only when the car reports that it is parked:
 * <ul>
 * <li>driving status - the same signal Android Auto uses to lock the keyboard and video,</li>
 * <li>car speed, gear and parking brake - used when the head unit provides them.</li>
 * </ul>
 * If the car reports nothing at all, video stays disabled (fail-safe). Audio, the screen
 * mirroring and navigation are never affected.
 * <p>
 * All of this can be changed in the app settings (Settings - Car): video while moving, the
 * moving speed, the wait before the video is shown after stopping, and what to do when the car
 * reports nothing.
 *
 * @author Youssef Auto fork
 */
final class DrivingMonitor extends CarConnectionCallback
		implements CarSensorManager.OnSensorChangedListener {
	// Time given to the car to report its state before the video gets disabled
	private static final long UNKNOWN_TIMEOUT = 5000;
	private static final int[] SENSORS =
			{SENSOR_TYPE_DRIVING_STATUS, SENSOR_TYPE_CAR_SPEED, SENSOR_TYPE_GEAR,
					SENSOR_TYPE_PARKING_BRAKE,
					// Car link only - not used for the parked-only video
					SENSOR_TYPE_FUEL_LEVEL, SENSOR_TYPE_ODOMETER, SENSOR_TYPE_ENVIRONMENT,
					SENSOR_TYPE_RPM, SENSOR_TYPE_NIGHT};
	private static DrivingMonitor instance;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable unknownTimeout = this::onUnknownTimeout;
	private final Runnable allowVideo = this::onAllowVideo;
	// Kept here - the preference store holds weak references to the listeners
	private final PreferenceStore.Listener prefListener = this::onPreferenceChanged;
	private boolean allowPending;
	private final Context ctx;
	private int refs;
	private Car car;
	private CarSensorManager sensors;
	private boolean timedOut;
	private Integer status;
	private Float speed;
	private Integer gear;
	private Boolean brake;

	private DrivingMonitor(Context ctx) {
		this.ctx = ctx.getApplicationContext();
	}

	/**
	 * Called when a car screen service is created, i.e. the phone is connected to a car.
	 */
	static synchronized void acquire(Context ctx) {
		if (instance == null) instance = new DrivingMonitor(ctx);
		if (instance.refs++ == 0) instance.handler.post(instance::start);
	}

	/**
	 * Called when a car screen service is destroyed.
	 */
	static synchronized void release() {
		DrivingMonitor m = instance;
		if ((m == null) || (m.refs == 0)) return;
		if (--m.refs == 0) m.handler.post(m::stop);
	}

	private synchronized boolean isActive() {
		return refs > 0;
	}

	private void start() {
		if (!isActive() || (car != null)) return;
		Log.i("Starting the driving state monitor");
		CarState.init(ctx);
		MainActivityPrefs.get().addBroadcastListener(prefListener);
		timedOut = false;
		handler.removeCallbacks(unknownTimeout);
		handler.postDelayed(unknownTimeout, UNKNOWN_TIMEOUT);

		try {
			car = Car.createCar(ctx, this, handler);
			car.connect();
		} catch (Throwable ex) {
			Log.e(ex, "Failed to connect to the car service");
			car = null;
		}
	}

	private void stop() {
		if (isActive()) return;
		Log.i("Stopping the driving state monitor");
		handler.removeCallbacks(unknownTimeout);
		handler.removeCallbacks(allowVideo);
		allowPending = false;
		MainActivityPrefs.get().removeBroadcastListener(prefListener);
		removeListeners();

		if (car != null) {
			try {
				car.disconnect();
			} catch (Throwable ex) {
				Log.d(ex, "Failed to disconnect from the car service");
			}
			car = null;
		}

		reset();
		CarState.setConnected(false, null);
		// Not connected to a car - nothing to restrict
		MediaSessionCallback.setVideoRestriction(VIDEO_ALLOWED);
	}

	private void reset() {
		status = null;
		speed = null;
		gear = null;
		brake = null;
	}

	private void removeListeners() {
		if (sensors == null) return;
		try {
			sensors.removeListener(this);
		} catch (Throwable ex) {
			Log.d(ex, "Failed to remove the car sensor listener");
		}
		sensors = null;
	}

	@Override
	public void onConnected(Car c) {
		handler.post(() -> {
			if (!isActive() || (c != car)) return;
			StringBuilder supported = new StringBuilder();

			try {
				sensors = c.getCarManager(CarSensorManager.class);
			} catch (Throwable ex) {
				Log.e(ex, "Car sensors are not available");
				sensors = null;
			}

			if (sensors != null) {
				for (int type : SENSORS) {
					if (!listen(type)) continue;
					if (supported.length() != 0) supported.append(", ");
					supported.append(name(type));
				}
			}

			Log.i("Car connected. Driving state sensors: ", supported);
			CarState.setConnected(true, supported.toString());
			String msg = "Parked-only video: " +
					((supported.length() == 0) ? "no driving sensors" : "sensors = " + supported);
			try {
				if (prefs().getBooleanPref(MainActivityPrefs.CAR_SHOW_STATUS)) {
					Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
				}
			} catch (Throwable ignore) {
			}
			update();
		});
	}

	@Override
	public void onDisconnected(Car c) {
		handler.post(() -> {
			if (c != car) return;
			Log.i("Car disconnected");
			CarState.setConnected(false, null);
			removeListeners();
			reset();
			if (!isActive()) return;
			// The car screen is still active, but the driving state is not known anymore
			timedOut = false;
			handler.removeCallbacks(unknownTimeout);
			handler.postDelayed(unknownTimeout, UNKNOWN_TIMEOUT);
		});
	}

	private boolean listen(int type) {
		try {
			if (!sensors.isSensorSupported(type)) return false;
			if (!sensors.addListener(this, type, SENSOR_RATE_NORMAL)) return false;
			CarSensorEvent e = sensors.getLatestSensorEvent(type);
			if (e != null) read(e);
			return true;
		} catch (Throwable ex) {
			// E.g. SecurityException - the car speed permission is not granted
			Log.d(ex, "Car sensor is not available: ", name(type));
			return false;
		}
	}

	@Override
	public void onSensorChanged(CarSensorManager m, CarSensorEvent e) {
		handler.post(() -> {
			if (m != sensors) return;
			try {
				read(e);
			} catch (Throwable ex) {
				Log.d(ex, "Failed to read the car sensor event");
				return;
			}
			update();
		});
	}

	private void read(CarSensorEvent e) {
		switch (e.sensorType) {
			case SENSOR_TYPE_DRIVING_STATUS -> {
				status = e.getDrivingStatusData().status;
				CarState.put("driving_restricted", status != DRIVE_STATUS_UNRESTRICTED);
			}
			case SENSOR_TYPE_CAR_SPEED -> {
				speed = e.getCarSpeedData().carSpeed;
				CarState.put("speed_kmh", Math.abs(speed) * 3.6f);
			}
			case SENSOR_TYPE_GEAR -> {
				gear = e.getGearData().gear;
				CarState.put("gear", gearName(gear));
			}
			case SENSOR_TYPE_PARKING_BRAKE -> {
				brake = e.getParkingBrakeData().isEngaged;
				CarState.put("parking_brake", brake);
			}
			case SENSOR_TYPE_FUEL_LEVEL -> {
				CarSensorEvent.FuelLevelData d = e.getFuelLevelData();
				if (d.level >= 0) CarState.put("fuel_percent", d.level);
				if (d.range >= 0) CarState.put("fuel_range_km", d.range);
				CarState.put("fuel_low", d.lowFuelWarning);
			}
			case SENSOR_TYPE_ODOMETER -> CarState.put("odometer_km", e.getOdometerData().kms);
			case SENSOR_TYPE_ENVIRONMENT ->
					CarState.put("outside_temp_c", e.getEnvironmentData().temperature);
			case SENSOR_TYPE_RPM -> CarState.put("rpm", e.getRpmData().rpm);
			case SENSOR_TYPE_NIGHT -> CarState.put("night", e.getNightData().isNightMode);
			default -> {
			}
		}
	}

	private void onUnknownTimeout() {
		timedOut = true;
		update();
	}

	private static MainActivityPrefs prefs() {
		return MainActivityPrefs.get();
	}

	private void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (prefs.contains(MainActivityPrefs.CAR_VIDEO_MODE) ||
				prefs.contains(MainActivityPrefs.CAR_MOVING_SPEED) ||
				prefs.contains(MainActivityPrefs.CAR_RESUME_DELAY) ||
				prefs.contains(MainActivityPrefs.CAR_NO_SENSORS)) {
			handler.post(this::update);
		}
	}

	private void update() {
		if (!isActive()) return;

		if (prefs().getIntPref(MainActivityPrefs.CAR_VIDEO_MODE) == MainActivityPrefs.CAR_VIDEO_ALWAYS) {
			// Chosen by the user in the settings
			setRestriction(VIDEO_ALLOWED, false);
			return;
		}

		boolean known = (status != null) || (speed != null) || (gear != null) || (brake != null);

		if (known) {
			if (isParked()) setRestriction(VIDEO_ALLOWED, true);
			else setRestriction(VIDEO_RESTRICTED_MOVING, false);
		} else if (timedOut) {
			// No way to tell if the car is parked - disabled unless the user allowed it
			if (prefs().getIntPref(MainActivityPrefs.CAR_NO_SENSORS) ==
					MainActivityPrefs.CAR_NO_SENSORS_ALLOW) {
				setRestriction(VIDEO_ALLOWED, false);
			} else {
				setRestriction(VIDEO_RESTRICTED_UNKNOWN, false);
			}
		}
	}

	/**
	 * @param delayed wait the configured time before allowing the video, so that a short stop
	 *                (e.g. at traffic lights) does not start it.
	 */
	private void setRestriction(int restriction, boolean delayed) {
		if (restriction != VIDEO_ALLOWED) {
			handler.removeCallbacks(allowVideo);
			allowPending = false;
			MediaSessionCallback.setVideoRestriction(restriction);
			return;
		}

		int delay = delayed ? Math.max(0, prefs().getIntPref(MainActivityPrefs.CAR_RESUME_DELAY)) : 0;
		if ((delay == 0) || !MediaSessionCallback.isVideoRestricted()) {
			handler.removeCallbacks(allowVideo);
			allowPending = false;
			MediaSessionCallback.setVideoRestriction(VIDEO_ALLOWED);
		} else if (!allowPending) {
			allowPending = true;
			handler.postDelayed(allowVideo, delay * 1000L);
		}
	}

	private void onAllowVideo() {
		if (!allowPending) return;
		allowPending = false;
		if (isActive()) MediaSessionCallback.setVideoRestriction(VIDEO_ALLOWED);
	}

	private boolean isParked() {
		// The head unit says that video (or anything else) is restricted
		if ((status != null) && (status != DRIVE_STATUS_UNRESTRICTED)) return false;
		float movingSpeed = Math.max(1, prefs().getIntPref(MainActivityPrefs.CAR_MOVING_SPEED)) / 3.6f;
		if ((speed != null) && (Math.abs(speed) > movingSpeed)) return false;
		if ((status != null) || (speed != null)) return true;
		// Neither the driving status nor the speed is reported - rely on the gear or parking brake
		if (gear != null) return gear == GEAR_PARK;
		return brake;
	}

	private static String gearName(int g) {
		return switch (g) {
			case GEAR_PARK -> "P";
			case GEAR_REVERSE -> "R";
			case GEAR_NEUTRAL -> "N";
			case GEAR_DRIVE -> "D";
			default -> String.valueOf(g);
		};
	}

	private static String name(int type) {
		return switch (type) {
			case SENSOR_TYPE_DRIVING_STATUS -> "status";
			case SENSOR_TYPE_CAR_SPEED -> "speed";
			case SENSOR_TYPE_GEAR -> "gear";
			case SENSOR_TYPE_PARKING_BRAKE -> "brake";
			case SENSOR_TYPE_FUEL_LEVEL -> "fuel";
			case SENSOR_TYPE_ODOMETER -> "odometer";
			case SENSOR_TYPE_ENVIRONMENT -> "temp";
			case SENSOR_TYPE_RPM -> "rpm";
			case SENSOR_TYPE_NIGHT -> "night";
			default -> String.valueOf(type);
		};
	}
}
