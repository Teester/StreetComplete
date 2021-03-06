package de.westnordost.streetcomplete.tangram;

import android.app.Activity;
import android.app.Fragment;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v13.app.FragmentCompat;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.mapzen.android.lost.api.LocationListener;
import com.mapzen.android.lost.api.LocationRequest;
import com.mapzen.android.lost.api.LocationServices;
import com.mapzen.android.lost.api.LostApiClient;
import com.mapzen.tangram.HttpHandler;
import com.mapzen.tangram.LngLat;
import com.mapzen.tangram.MapController;
import com.mapzen.tangram.MapView;
import com.mapzen.tangram.Marker;
import com.mapzen.tangram.TouchInput;

import java.io.File;

import de.westnordost.osmapi.map.data.LatLon;
import de.westnordost.streetcomplete.Prefs;
import de.westnordost.streetcomplete.R;
import de.westnordost.streetcomplete.util.SphericalEarthMath;

import static android.content.Context.SENSOR_SERVICE;

public class MapFragment extends Fragment implements
		FragmentCompat.OnRequestPermissionsResultCallback, LocationListener,
		LostApiClient.ConnectionCallbacks, TouchInput.ScaleResponder,
		TouchInput.ShoveResponder, TouchInput.RotateResponder,
		TouchInput.PanResponder, TouchInput.DoubleTapResponder, CompassComponent.Listener
{
	private CompassComponent compass = new CompassComponent();

	private Marker locationMarker;
	private Marker accuracyMarker;
	private Marker directionMarker;
	private String[] directionMarkerSize;

	private MapView mapView;

	private HttpHandler httpHandler;

	/** controller to the asynchronously loaded map. Since it is loaded asynchronously, could be
	 *  null still at any point! */
	protected MapController controller;

	private LostApiClient lostApiClient;

	private boolean isFollowingPosition;
	private Location lastLocation;
	private boolean zoomedYet;

	private Listener listener;

	private String apiKey;

	public interface Listener
	{
		void onMapReady();
		void onUnglueViewFromPosition();
	}

	@Override public View onCreateView(LayoutInflater inflater, ViewGroup container,
									   Bundle savedInstanceState)
	{
		View view = inflater.inflate(R.layout.fragment_map, container, false);

		mapView = (MapView) view.findViewById(R.id.map);
		TextView mapzenLink = (TextView) view.findViewById(R.id.mapzenLink);

		mapzenLink.setText(Html.fromHtml(
				String.format(getResources().getString(R.string.map_attribution_mapzen),
				"<a href=\"https://mapzen.com/\">Mapzen</a>"))
		);
		mapzenLink.setMovementMethod(LinkMovementMethod.getInstance());

		return view;
	}

	/* --------------------------------- Map and Location --------------------------------------- */

	public void getMapAsync(String apiKey)
	{
		getMapAsync(apiKey, "scene.yaml");
	}

	public void getMapAsync(String apiKey, @NonNull final String sceneFilePath)
	{
		this.apiKey = apiKey;
		mapView.getMapAsync(new MapView.OnMapReadyCallback()
		{
			@Override public void onMapReady(MapController ctrl)
			{
				controller = ctrl;
				initMap();
			}
		}, sceneFilePath);
	}

	protected void initMap()
	{
		updateMapTileCacheSize();
		controller.setHttpHandler(httpHandler);
		restoreCameraState();

		controller.setRotateResponder(this);
		controller.setShoveResponder(this);
		controller.setScaleResponder(this);
		controller.setPanResponder(this);
		controller.setDoubleTapResponder(this);

		locationMarker = controller.addMarker();
		BitmapDrawable dot = createBitmapDrawableFrom(R.drawable.location_dot);
		locationMarker.setStylingFromString("{ style: 'points', color: 'white', size: ["+TextUtils.join(",",sizeInDp(dot))+"], order: 2000, flat: true, collide: false }");
		locationMarker.setDrawable(dot);
		locationMarker.setDrawOrder(3);

		directionMarker = controller.addMarker();
		BitmapDrawable directionImg = createBitmapDrawableFrom(R.drawable.location_direction);
		directionMarkerSize = sizeInDp(directionImg);
		directionMarker.setDrawable(directionImg);
		directionMarker.setDrawOrder(2);

		accuracyMarker = controller.addMarker();
		accuracyMarker.setDrawable(createBitmapDrawableFrom(R.drawable.accuracy_circle));
		accuracyMarker.setDrawOrder(1);

		compass.setListener(this);

		showLocation();
		followPosition();

		updateView();

		listener.onMapReady();
	}

	private String[] sizeInDp(Drawable drawable)
	{
		DisplayMetrics metrics = new DisplayMetrics();
		getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
		float d = metrics.density;
		return new String[]{
				drawable.getIntrinsicWidth() / d + "px",
				drawable.getIntrinsicHeight() / d + "px"};
	}

	private BitmapDrawable createBitmapDrawableFrom(int resId)
	{
		Drawable drawable = getResources().getDrawable(resId);
		Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
				drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
		drawable.draw(canvas);
		return new BitmapDrawable(getResources(), bitmap);
	}

	private void updateMapTileCacheSize()
	{
		httpHandler = createHttpHandler();
	}

	private HttpHandler createHttpHandler()
	{
		int cacheSize = PreferenceManager.getDefaultSharedPreferences(getActivity()).getInt(Prefs.MAP_TILECACHE, 50);

		File cacheDir = getActivity().getExternalCacheDir();
		if (cacheDir != null && cacheDir.exists())
		{
			return new TileHttpHandler(apiKey, new File(cacheDir, "tile_cache"), cacheSize * 1024 * 1024);
		}
		return new TileHttpHandler(apiKey);
	}

	public void startPositionTracking()
	{
		if(!lostApiClient.isConnected()) lostApiClient.connect();
	}

	public void stopPositionTracking()
	{
		if(locationMarker != null)
		{
			locationMarker.setVisible(false);
			accuracyMarker.setVisible(false);
			directionMarker.setVisible(false);
		}
		lastLocation = null;
		zoomedYet = false;

		if(lostApiClient.isConnected())
		{
			LocationServices.FusedLocationApi.removeLocationUpdates(lostApiClient, this);
			lostApiClient.disconnect();
		}
	}

	public void setIsFollowingPosition(boolean value)
	{
		isFollowingPosition = value;
		if(!isFollowingPosition) {
			zoomedYet = false;
		}
		followPosition();
	}

	public boolean isFollowingPosition()
	{
		return isFollowingPosition;
	}

	private void followPosition()
	{
		if(isFollowingPosition && controller != null && lastLocation != null)
		{
			controller.setPositionEased(new LngLat(lastLocation.getLongitude(), lastLocation.getLatitude()),1000);
			if(!zoomedYet)
			{
				zoomedYet = true;
				controller.setZoomEased(19, 1000);
			}
			updateView();
		}
	}

	/* -------------------------------- Touch responders --------------------------------------- */

	@Override public boolean onDoubleTap(float x, float y)
	{
		unglueViewFromPosition();
		LngLat zoomTo = controller.screenPositionToLngLat(new PointF(x, y));
		controller.setPositionEased(zoomTo, 500);
		controller.setZoomEased(controller.getZoom() + 1.5f, 500);
		updateView();
		return true;
	}

	@Override public boolean onScale(float x, float y, float scale, float velocity)
	{
		unglueViewFromPosition();
		updateView();
		return false;
	}

	@Override public boolean onPan(float startX, float startY, float endX, float endY)
	{
		unglueViewFromPosition();
		updateView();
		return false;
	}

	@Override public boolean onFling(float posX, float posY, float velocityX, float velocityY)
	{
		unglueViewFromPosition();
		updateView();
		return false;
	}

	@Override public boolean onShove(float distance)
	{
		updateView();
		return false;
	}

	@Override public boolean onRotate(float x, float y, float rotation)
	{
		updateView();
		return false;
	}

	protected void updateView()
	{
		updateAccuracy();
	}

	private void unglueViewFromPosition()
	{
		if(isFollowingPosition())
		{
			setIsFollowingPosition(false);
			listener.onUnglueViewFromPosition();
		}
	}

	/* ------------------------------------ LOST ------------------------------------------- */

	@Override public void onLocationChanged(Location location)
	{
		lastLocation = location;
		showLocation();
		followPosition();
	}

	private void showLocation()
	{
		if(accuracyMarker != null && locationMarker != null && directionMarker != null && lastLocation != null)
		{
			LngLat pos = new LngLat(lastLocation.getLongitude(), lastLocation.getLatitude());
			locationMarker.setVisible(true);
			accuracyMarker.setVisible(true);
			directionMarker.setVisible(true);
			locationMarker.setPointEased(pos, 1000, MapController.EaseType.CUBIC);
			accuracyMarker.setPointEased(pos, 1000, MapController.EaseType.CUBIC);
			directionMarker.setPointEased(pos, 1000, MapController.EaseType.CUBIC);

			updateAccuracy();
		}
	}

	private void updateAccuracy()
	{
		if(accuracyMarker != null && lastLocation != null && accuracyMarker.isVisible())
		{
			LngLat pos = new LngLat(lastLocation.getLongitude(), lastLocation.getLatitude());
			float size = meters2Pixels(pos, lastLocation.getAccuracy());
			accuracyMarker.setStylingFromString("{ style: 'points', color: 'white', size: ["+size+"px, "+size+"px], order: 2000, flat: true, collide: false }");
		}
	}

	@Override public void onRotationChanged(float rotation)
	{
		if(directionMarker != null && directionMarker.isVisible())
		{
			double r = rotation * 180 / Math.PI;
			directionMarker.setStylingFromString(
					"{ style: 'points', color: '#cc536dfe', size: [" +
							TextUtils.join(",",directionMarkerSize) +
							"], order: 2000, collide: false, flat: true, angle: " + r + " }");
		}
	}

	private float meters2Pixels(LngLat at, float meters) {
		LatLon pos0 = TangramConst.toLatLon(at);
		LatLon pos1 = SphericalEarthMath.translate(pos0, meters, 0);
		PointF screenPos0 = controller.lngLatToScreenPosition(at);
		PointF screenPos1 = controller.lngLatToScreenPosition(TangramConst.toLngLat(pos1));
		return Math.abs(screenPos1.y - screenPos0.y);
	}

	private static final String PREF_ROTATION = "map_rotation";
	private static final String PREF_TILT = "map_tilt";
	private static final String PREF_ZOOM = "map_zoom";
	private static final String PREF_LAT = "map_lat";
	private static final String PREF_LON = "map_lon";

	private void restoreCameraState()
	{
		SharedPreferences prefs = getActivity().getPreferences(Activity.MODE_PRIVATE);

		if(prefs.contains(PREF_ROTATION)) controller.setRotation(prefs.getFloat(PREF_ROTATION,0));
		if(prefs.contains(PREF_TILT)) controller.setTilt(prefs.getFloat(PREF_TILT,0));
		if(prefs.contains(PREF_ZOOM)) controller.setZoom(prefs.getFloat(PREF_ZOOM,0));

		if(prefs.contains(PREF_LAT) && prefs.contains(PREF_LON))
		{
			LngLat pos = new LngLat(
					Double.longBitsToDouble(prefs.getLong(PREF_LON,0)),
					Double.longBitsToDouble(prefs.getLong(PREF_LAT,0))
			);
			controller.setPosition(pos);
		}
	}

	private void saveCameraState()
	{
		if(controller == null) return;

		SharedPreferences.Editor editor = getActivity().getPreferences(Activity.MODE_PRIVATE).edit();
		editor.putFloat(PREF_ROTATION, controller.getRotation());
		editor.putFloat(PREF_TILT, controller.getTilt());
		editor.putFloat(PREF_ZOOM, controller.getZoom());
		LngLat pos = controller.getPosition();
		editor.putLong(PREF_LAT, Double.doubleToRawLongBits(pos.latitude));
		editor.putLong(PREF_LON, Double.doubleToRawLongBits(pos.longitude));
		editor.apply();
	}

	/* ------------------------------------ Lifecycle ------------------------------------------- */

	@Override public void onCreate(@Nullable Bundle bundle)
	{
		super.onCreate(bundle);
		compass.onCreate((SensorManager) getActivity().getSystemService(SENSOR_SERVICE));
		if(mapView != null) mapView.onCreate(bundle);
	}

	@Override public void onAttach(Activity activity)
	{
		super.onAttach(activity);
		listener = (Listener) activity;
		lostApiClient = new LostApiClient.Builder(activity).addConnectionCallbacks(this).build();
	}

	@Override public void onStart()
	{
		super.onStart();
		updateMapTileCacheSize();
	}

	@Override public void onResume()
	{
		super.onResume();
		compass.onResume();
		if(mapView != null) mapView.onResume();
	}

	@Override public void onPause()
	{
		super.onPause();
		compass.onPause();
		if(mapView != null) mapView.onPause();
		saveCameraState();
	}

	@Override public void onStop()
	{
		super.onStop();
		stopPositionTracking();
	}

	@Override public void onConnected() throws SecurityException
	{
		zoomedYet = false;
		lastLocation = null;

		LocationRequest request = LocationRequest.create()
				.setInterval(2000)
				.setSmallestDisplacement(5)
				.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

		LocationServices.FusedLocationApi.requestLocationUpdates(lostApiClient, request, this);
	}

	@Override public void onConnectionSuspended()
	{

	}

	@Override public void onDestroy()
	{
		super.onDestroy();
		compass.setListener(null);
		if(mapView != null) mapView.onDestroy();
		controller = null;
		directionMarker = null;
		accuracyMarker = null;
		locationMarker = null;
	}

	@Override public void onLowMemory()
	{
		super.onLowMemory();
		if(mapView != null) mapView.onLowMemory();
	}

	public void zoomIn()
	{
		if(controller == null) return;
		controller.setZoomEased(controller.getZoom() + 1, 500);
		updateView();
	}

	public void zoomOut()
	{
		if(controller == null) return;
		controller.setZoomEased(controller.getZoom() - 1, 500);
		updateView();
	}

	public Location getDisplayedLocation()
	{
		return lastLocation;
	}
}
