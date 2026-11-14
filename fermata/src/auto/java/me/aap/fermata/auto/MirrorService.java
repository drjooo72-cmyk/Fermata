package me.aap.fermata.auto;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * @author Andrey Pavlenko
 */
public class MirrorService extends CarActivityService {
	private MirrorDisplay md;

	@Override
	public void onCreate() {
		super.onCreate();
		md = MirrorDisplay.get();
		DrivingMonitor.acquire(this);
	}

	@Override
	public void onDestroy() {
		DrivingMonitor.release();
		md.release();
		md = null;
		super.onDestroy();
	}

	@Override
	public Class<? extends CarActivity> getCarActivity() {
		return MirrorActivity.class;
	}
}
