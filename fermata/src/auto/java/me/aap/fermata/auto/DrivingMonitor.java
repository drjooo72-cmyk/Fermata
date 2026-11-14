package me.aap.fermata.auto;

import static android.support.car.hardware.CarSensorEvent.DRIVE_STATUS_UNRESTRICTED;
import static android.support.car.hardware.CarSensorEvent.GEAR_PARK;
import static android.support.car.hardware.CarSensorManager.SENSOR_RATE_NORMAL;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_CAR_SPEED;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_DRIVING_STATUS;
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

import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.utils.log.Log;

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
 *
 * @author Youssef Auto fork
 */
final class DrivingMonitor extends CarConnectionCallback
		implements CarSensorManager.OnSensorChangedListener {
	// ~5 km/h, tolerates sensor noise when standing still
	private static final float MOVING_SPEED = 1.4f;
	// Time given to the car to report its state before the video gets disabled
	private static final long UNKNOWN_TIMEOUT = 5000;
	private static final int[] SENSORS =
			{SENSOR_TYPE_DRIVING_STATUS, SENSOR_TYPE_CAR_SPEED, SENSOR_TYPE_GEAR,
					SENSOR_TYPE_PARKING_BRAKE};
	private static DrivingMonitor instance;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable unknownTimeout = this::onUnknownTimeout;
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
			String msg = "Parked-only video: " +
					((supported.length() == 0) ? "no driving sensors" : "sensors = " + supported);
			try {
				Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
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
			case SENSOR_TYPE_DRIVING_STATUS -> status = e.getDrivingStatusData().status;
			case SENSOR_TYPE_CAR_SPEED -> speed = e.getCarSpeedData().carSpeed;
			case SENSOR_TYPE_GEAR -> gear = e.getGearData().gear;
			case SENSOR_TYPE_PARKING_BRAKE -> brake = e.getParkingBrakeData().isEngaged;
			default -> {
			}
		}
	}

	private void onUnknownTimeout() {
		timedOut = true;
		update();
	}

	private void update() {
		if (!isActive()) return;
		boolean known = (status != null) || (speed != null) || (gear != null) || (brake != null);

		if (known) {
			MediaSessionCallback.setVideoRestriction(
					isParked() ? VIDEO_ALLOWED : VIDEO_RESTRICTED_MOVING);
		} else if (timedOut) {
			// Fail-safe: no way to tell if the car is parked
			MediaSessionCallback.setVideoRestriction(VIDEO_RESTRICTED_UNKNOWN);
		}
	}

	private boolean isParked() {
		// The head unit says that video (or anything else) is restricted
		if ((status != null) && (status != DRIVE_STATUS_UNRESTRICTED)) return false;
		if ((speed != null) && (Math.abs(speed) > MOVING_SPEED)) return false;
		if ((status != null) || (speed != null)) return true;
		// Neither the driving status nor the speed is reported - rely on the gear or parking brake
		if (gear != null) return gear == GEAR_PARK;
		return brake;
	}

	private static String name(int type) {
		return switch (type) {
			case SENSOR_TYPE_DRIVING_STATUS -> "status";
			case SENSOR_TYPE_CAR_SPEED -> "speed";
			case SENSOR_TYPE_GEAR -> "gear";
			case SENSOR_TYPE_PARKING_BRAKE -> "brake";
			default -> String.valueOf(type);
		};
	}
}
