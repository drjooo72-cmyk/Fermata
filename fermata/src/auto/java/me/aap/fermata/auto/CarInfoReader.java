package me.aap.fermata.auto;

import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarInfo;
import androidx.car.app.hardware.info.EnergyLevel;
import androidx.car.app.hardware.info.Mileage;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

import me.aap.utils.log.Log;

/**
 * Car link (added in the "Youssef Auto" fork of Fermata).
 * <p>
 * Reads the energy data (battery and fuel level, remaining range), the odometer and the car model
 * with the Android for Cars App Library, when the car screen session is open. Read-only.
 */
final class CarInfoReader {
	private CarInfoReader() {
	}

	/**
	 * @return a callback that stops reading, or null if the car does not provide the data.
	 */
	@Nullable
	static Runnable start(CarContext ctx) {
		try {
			CarInfo info = ctx.getCarService(CarHardwareManager.class).getCarInfo();
			Executor exec = ContextCompat.getMainExecutor(ctx);

			OnCarDataAvailableListener<EnergyLevel> energy = e -> {
				put("battery_percent", e.getBatteryPercent());
				put("fuel_percent_2", e.getFuelPercent());
				put("energy_low", e.getEnergyIsLow());
				CarValue<Float> range = e.getRangeRemainingMeters();
				if (ok(range)) CarState.put("range_km", range.getValue() / 1000f);
			};
			OnCarDataAvailableListener<Mileage> mileage = m -> {
				CarValue<Float> odo = m.getOdometerMeters();
				if (ok(odo)) CarState.put("odometer_km_2", odo.getValue() / 1000f);
			};

			tryRun(() -> info.addEnergyLevelListener(exec, energy), "energy level");
			tryRun(() -> info.addMileageListener(exec, mileage), "mileage");
			tryRun(() -> info.fetchModel(exec, m -> {
				put("make", m.getManufacturer());
				put("model", m.getName());
				put("year", m.getYear());
			}), "model");
			tryRun(() -> info.fetchEnergyProfile(exec, p -> {
				CarValue<java.util.List<Integer>> fuel = p.getFuelTypes();
				if (ok(fuel)) CarState.put("fuel_types", fuel.getValue().toString());
				CarValue<java.util.List<Integer>> ev = p.getEvConnectorTypes();
				if (ok(ev)) CarState.put("ev_connectors", ev.getValue().toString());
			}), "energy profile");

			Log.i("Car info reader started");
			return () -> {
				tryRun(() -> info.removeEnergyLevelListener(energy), "energy level");
				tryRun(() -> info.removeMileageListener(mileage), "mileage");
			};
		} catch (Throwable ex) {
			Log.d(ex, "Car info is not available");
			return null;
		}
	}

	private static void put(String key, CarValue<?> v) {
		if (ok(v)) CarState.put(key, v.getValue());
	}

	private static boolean ok(@Nullable CarValue<?> v) {
		return (v != null) && (v.getStatus() == CarValue.STATUS_SUCCESS) && (v.getValue() != null);
	}

	private static void tryRun(Runnable r, String what) {
		try {
			r.run();
		} catch (Throwable ex) {
			// E.g. SecurityException or the host does not support it
			Log.d(ex, "Car info is not available: ", what);
		}
	}
}
