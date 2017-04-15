package com.vezet.vezetnav;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.support.constraint.ConstraintLayout;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.bonuspack.routing.OSRMRoadManager;
import org.osmdroid.bonuspack.routing.Road;
import org.osmdroid.bonuspack.routing.RoadManager;
import org.osmdroid.bonuspack.routing.RoadNode;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.NetworkLocationIgnorer;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.infowindow.InfoWindow;
import org.osmdroid.views.overlay.mylocation.DirectedLocationOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class MapActivity extends AppCompatActivity implements LocationListener, Marker.OnMarkerClickListener,  View.OnTouchListener, InterceptedTouchListener {
    private LocationManager mLocationManager;
    private DirectedLocationOverlay myLocationOverlay;
    private DirectedLocationOverlay roadStartLocationOverlay;
    private IMapController mapController;
    private MapView map;

    private GeoPoint currentPoint;
    private GeoPoint destinationPoint;

    private boolean mTrackingMode = true;
    private long mTrackingModeTimeOut;
    private ArrayList<Marker> destinationMarkers = new ArrayList<Marker>();
    private Polyline roadOverlay;
    private Marker prevClickedMarker;
    private int SelectedType;
    private Road currentRoad;
    private UpdateMapOverlayThread socketThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        Intent intent = getIntent();
        SelectedType = intent.getIntExtra("Type", 1);

        Configuration.getInstance().setOsmdroidBasePath(new File(Environment.getExternalStorageDirectory(), "osmdroid"));
        Configuration.getInstance().setOsmdroidTileCache(new File(Environment.getExternalStorageDirectory(), "osmdroid/tiles"));

        map = (MapView) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setBuiltInZoomControls(true);
        map.setMultiTouchControls(true);

        mapController = map.getController();
        mapController.setZoom(10);

        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        myLocationOverlay = new DirectedLocationOverlay(this);
        myLocationOverlay.setEnabled(false);
        map.getOverlays().add(myLocationOverlay);

        roadStartLocationOverlay = new DirectedLocationOverlay(this);
        roadStartLocationOverlay.setEnabled(false);
        map.getOverlays().add(roadStartLocationOverlay);

        map.setOnTouchListener(this);

        CustomConstraintLayout layout = (CustomConstraintLayout) findViewById(R.id.layout);
        layout.setOnInterceptedTouchListener(this);

        Handler handler = new Handler();
        socketThread = new UpdateMapOverlayThread(handler, this);
        socketThread.start();

        setLocationService();

        if (savedInstanceState != null) {
            currentPoint = savedInstanceState.getParcelable("currentPoint");
            destinationPoint = savedInstanceState.getParcelable("currentDestination");
            currentRoad = savedInstanceState.getParcelable("currentRoad");
            UpdateRoad(currentRoad);
        }
        if (destinationPoint == null)
            setMarkers();
    }

    private void setLocationService() {
        Location location = null;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null)
                location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        if (location != null) {
            onLocationChanged(location);
        }
    }

    /**
     * callback to store activity status before a restart (orientation change for instance)
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putParcelable("currentPoint", currentPoint);
        outState.putParcelable("destinationPoint", destinationPoint);
        outState.putParcelable("currentRoad", currentRoad);
    }

    private void setMarkers() {
        if (currentPoint == null) return;
        for (CustomLocation location : MainActivity.locations) {
            double distance = getDistance(location.Lat, currentPoint.getLatitude(), location.Lon, currentPoint.getLongitude(), 0.0, 0.0);
            if (distance < 50000 && location.Type == SelectedType) {
                Marker marker = new Marker(map);
                marker.setPosition(location.getGeoPoint());
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                map.getOverlays().add(marker);

                int imageResource = getResources().getIdentifier(location.Label, null, getPackageName());
                Drawable image = getResources().getDrawable(imageResource);

                marker.setIcon(image);
                marker.setTitle(location.Name);
                marker.setSnippet(location.Description);
                marker.setSubDescription(location.Description);
                marker.setOnMarkerClickListener(this);
                destinationMarkers.add(marker);
                map.invalidate();
            }
        }
    }

    private void clearDestinationMarkers() {
        for (Marker marker : destinationMarkers) {
            map.getOverlays().remove(marker);
        }
    }

    @Override
    public boolean onMarkerClick(Marker marker, MapView mapView) {
        if (marker == null){
            prevClickedMarker = null;
            return false;
        }
        if (prevClickedMarker == null || marker != prevClickedMarker) {
            prevClickedMarker = marker;
            marker.showInfoWindow();
            InfoWindow infoWindow = marker.getInfoWindow();
            infoWindow.getView().setOnTouchListener(this);

        } else if (prevClickedMarker == marker) {
            clearDestinationMarkers();
            destinationPoint = marker.getPosition();
            socketThread.setDestination(destinationPoint);
            prevClickedMarker.closeInfoWindow();
            return true;
        }

        return false;
    }

    public void newLocation(View view) {
        Location loc = new Location("Test");
        if (currentPoint == null) {
            currentPoint = new GeoPoint(60, 30);
        }
        loc.setLatitude(currentPoint.getLatitude());
        loc.setLongitude(currentPoint.getLongitude() + 0.01);
        loc.setAltitude(0);
        loc.setAccuracy(10f);
        loc.setProvider(LocationManager.GPS_PROVIDER);
        loc.setTime(System.currentTimeMillis());
        loc.setSpeed(5);
        onLocationChanged(loc);
        mTrackingMode = true;
    }

    public static double getDistance(double lat1, double lat2, double lon1,
                                     double lon2, double el1, double el2) {

        final int R = 6371; // Radius of the earth

        Double latDistance = Math.toRadians(lat2 - lat1);
        Double lonDistance = Math.toRadians(lon2 - lon1);
        Double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        Double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = R * c * 1000; // convert to meters

        double height = el1 - el2;

        distance = Math.pow(distance, 2) + Math.pow(height, 2);

        return Math.sqrt(distance);
    }

    //------------ LocationListener implementation
    private final NetworkLocationIgnorer mIgnorer = new NetworkLocationIgnorer();
    long mLastTime = 0; // milliseconds
    double mSpeed = 0.0; // km/h

    int mAccuracy = 0;

    @Override
    public void onLocationChanged(final Location pLoc) {
        long currentTime = System.currentTimeMillis();
        if (mIgnorer.shouldIgnore(pLoc.getProvider(), currentTime))
            return;
        double dT = currentTime - mLastTime;
        if (dT < 100.0) {
            return;
        }
        mLastTime = currentTime;
        mSpeed = pLoc.getSpeed() * 3.6;
        mAccuracy = (int) pLoc.getAccuracy();

        GeoPoint newLocation = new GeoPoint(pLoc);
        currentPoint = newLocation;

        //first time, update from UI thread and set markers
        if (!myLocationOverlay.isEnabled()) {
            myLocationOverlay.setEnabled(true);
            UpdateLocation(pLoc);
            setMarkers();
        }
        //next time, update from background thread
        else {
            socketThread.setLocation(pLoc);
        }
    }

    private void UpdateLocation(Location location) {
        UpdateTrackingMode(location);
        //TODO: disable location overlay if road is rendered
        if (destinationPoint == null) {
            UpdateLocationOverlay(location);
        } else {
            myLocationOverlay.setEnabled(false);
        }

        if (mTrackingMode) {
            TrackMap(location);
        } else {
            map.invalidate();
        }
    }

    public void UpdateLocationOverlay(Location location) {
        GeoPoint prevLocation = myLocationOverlay.getLocation();
        myLocationOverlay.setLocation(new GeoPoint(location));

        double speed = location.getSpeed() * 3.6;
        float azimuthAngleSpeed = location.getBearing();

        if (prevLocation != null && speed >= 0.1) {
            myLocationOverlay.setBearing(azimuthAngleSpeed);
        }
    }

    public void UpdateTrackingMode(Location location) {
        long currentTime = System.currentTimeMillis();
        long timeout = currentTime - mTrackingModeTimeOut;
        if (!mTrackingMode && timeout > 10000) {
            mTrackingMode = true;
            Log.d("trackingMode", "tracking:" + mTrackingMode);
        }
    }

    public void TrackMap(Location location) {
        double speed = location.getSpeed();
        float azimuthAngleSpeed = location.getBearing();

        if (speed < 10)
            mapController.setZoom(18);
        else if (speed < 30)
            mapController.setZoom(15);
        else if (speed < 50)
            mapController.setZoom(12);
        else
            mapController.setZoom(10);
        //keep the map view centered on current location:

        GeoPoint newPoint = new GeoPoint(location);
        int deltaDistance = newPoint.distanceTo(map.getMapCenter());
        if (deltaDistance > 100)
            map.getController().animateTo(newPoint);
        float deltaOrientation = map.getMapOrientation() - -azimuthAngleSpeed;
        if (deltaOrientation > 10)
            map.setMapOrientation(-azimuthAngleSpeed);

        if (deltaDistance <= 100 || deltaOrientation <= 10)
            map.invalidate();
    }

    private void UpdateRoad(Road road) {
        currentRoad = road;
        if (roadOverlay != null)
            map.getOverlays().remove(roadOverlay);

        if (road == null) return;

        if (road.mNodes.size() >= 1) {

            RoadNode node = road.mNodes.get(1);
            roadStartLocationOverlay.setEnabled(true);
            roadStartLocationOverlay.setLocation(node.mLocation);
            UpdateNextStep(node.mManeuverType, node.mLength, road.mLength, road.mDuration);
        }

        roadOverlay = RoadManager.buildRoadOverlay(road);
        map.getOverlays().add(roadOverlay);
        map.invalidate();
    }

    private void UpdateNextStep(int type, double length, double overallLength, double duration) {
        TextView textView = (TextView) findViewById(R.id.textView);
        textView.setText(getLocalizedDirections(type) + " через " + String.format("%.2f", length) + "км" + "       " + String.format("%.2f", overallLength) + "км" + " " + duration + "сек. ");
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private String getLocalizedDirections(int maneuverType) {
        String directions;
        switch (maneuverType) {
            //              NONE	0	No maneuver occurs here.
            case 0:
                directions = null;
                break;

            //              STRAIGHT	1	Continue straight.
            case 1:
                directions = "Продолжайте прямо";
                break;

            //                BECOMES	2	No maneuver occurs here; road name changes.
            case 2:
                directions = null;
                break;

            //                SLIGHT_LEFT	3	Make a slight left.
            case 3:
                directions = "Левее";
                break;

            //                LEFT	4	Turn left.
            case 4:
                directions = "Налево";
                break;

            //                SHARP_LEFT	5	Make a sharp left.
            case 5:
                directions = "Правее";
                break;

            //                SLIGHT_RIGHT	6	Make a slight right.
            case 6:
                directions = "Направо";
                break;

            //                RIGHT	7	Turn right.
            case 7:
                directions = "Направо";
                break;

            //                SHARP_RIGHT	8	Make a sharp right.
            case 8:
                directions = "Направо";
                break;

            //                STAY_LEFT	9	Stay left.
            case 9:
                directions = "Держитесь левее";
                break;

            //                STAY_RIGHT	10	Stay right.
            case 10:
                directions = "Держитесь правее";
                break;

            //                STAY_STRAIGHT	11	Stay straight.
            case 11:
                directions = "Держитесь прямо";
                break;

            //                UTURN	12	Make a U-turn.
            case 12:
                directions = "Разворот";
                break;

            //                UTURN_LEFT	13	Make a left U-turn.
            case 13:
                directions = "Разворот налево";
                break;

            //                UTURN_RIGHT	14	Make a right U-turn.
            case 14:
                directions = "Разворот направо";
                break;

            //                EXIT_LEFT	15	Exit left.
            case 15:
                directions = "Выезд налево";
                break;

            //                EXIT_RIGHT	16	Exit right.
            case 16:
                directions = "Выезд направо";
                break;

            //                RAMP_LEFT	17	Take the ramp on the left.
            case 17:
                directions = "На автомагистраль налево";
                break;

            //                RAMP_RIGHT	18	Take the ramp on the right.
            case 18:
                directions = "На автомагистрать направо";
                break;

            //                RAMP_STRAIGHT	19	Take the ramp straight ahead.
            case 19:
                directions = "На автомагистрать прямо";
                break;

            //                MERGE_LEFT	20	Merge left.
            case 20:
                directions = "Перестройтесь налево";
                break;

            //                MERGE_RIGHT	21	Merge right.
            case 21:
                directions = "Перестройтесь направо";
                break;

            //                MERGE_STRAIGHT	22	Merge.
            case 22:
                directions = "Перестройтесь";
                break;

            //                ENTERING	23	Enter state/province.
            case 23:
                directions = "Продолжайте прямо";
                break;

            //                DESTINATION	24	Arrive at your destination.
            case 24:
                directions = "Прибытие";
                break;

            //                DESTINATION_LEFT	25	Arrive at your destination on the left.
            case 25:
                directions = "Прибытие слева";
                break;

            //                DESTINATION_RIGHT	26	Arrive at your destination on the right.
            case 26:
                directions = "Прибытие справа";
                break;

            //                ROUNDABOUT1	27	Enter the roundabout and take the 1st exit.
            case 27:
                directions = "Круговое движение, первый выезд";
                break;

            //                ROUNDABOUT2	28	Enter the roundabout and take the 2nd exit.
            case 28:
                directions = "Круговое движение, второй выезд";
                break;

            //                ROUNDABOUT3	29	Enter the roundabout and take the 3rd exit.
            case 29:
                directions = "Круговое движение, третий выезд";
                break;

            //                ROUNDABOUT4	30	Enter the roundabout and take the 4th exit.
            case 30:
                directions = "Круговое движение, четвертый выезд";
                break;

            //                ROUNDABOUT5	31	Enter the roundabout and take the 5th exit.
            case 31:
                directions = "Круговое движение, пятый выезд";
                break;

            //                ROUNDABOUT6	32	Enter the roundabout and take the 6th exit.
            case 32:
                directions = "Круговое движение, шестой выезд";
                break;

            //                ROUNDABOUT7	33	Enter the roundabout and take the 7th exit.
            case 33:
                directions = "Круговое движение, седьмой выезд";
                break;

            //                ROUNDABOUT8	34	Enter the roundabout and take the 8th exit.
            case 34:
                directions = "Круговое движение, восьмой выезд";
                break;
            default:
                directions = null;
                break;
        }
        return directions;
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isOneProviderEnabled = startLocationUpdates();
        myLocationOverlay.setEnabled(isOneProviderEnabled);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mLocationManager.removeUpdates(this);
        }
    }

    boolean startLocationUpdates() {
        boolean result = false;
        for (final String provider : mLocationManager.getProviders(true)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mLocationManager.requestLocationUpdates(provider, 2 * 1000, 0.0f, this);
                result = true;
            }
        }
        return result;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
            onMarkerClick(prevClickedMarker, map);
        return false;
    }

    @Override
    public void onInterceptedTouch() {
        mTrackingMode = false;
        mTrackingModeTimeOut = System.currentTimeMillis();
        Log.d("trackingMode", "tracking:" + mTrackingMode + " intercepted touch on " + mTrackingModeTimeOut);
    }

    class UpdateMapOverlayThread extends Thread {
        private final Handler mHandler;
        private final Context context;
        private Location extrapolatedLocation;
        private Location originalLocation;
        private GeoPoint destinationPoint;
        private Road currentRoad;
        private final float refreshRatePerSecond = 1;
        private UpdateRoadTask updateRoadTask;

        UpdateMapOverlayThread(Handler handler, Context cont) {
            mHandler = handler;
            context = cont;
        }

        public void setLocation(Location loc) {
            synchronized (this) {
                if (originalLocation != null && originalLocation.getLatitude() == loc.getLatitude() && originalLocation.getLongitude() == loc.getLongitude()) {
                    Log.d("updateMapOverlayThread", "ignore " + loc.toString());
                    return;
                }

                extrapolatedLocation = new Location(loc);
                originalLocation = new Location(loc);

                Log.d("updateMapOverlayThread", "set " + loc.toString());
            }
        }


        public void setDestination(GeoPoint destination) {
            if (destination == destinationPoint) return;
            currentRoad = null;
            if (updateRoadTask != null)
                updateRoadTask.cancel(true);
            destinationPoint = destination;
        }

        public void run() {
            while (true) {
                try {
                    Thread.sleep(300);
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }

                synchronized (this) {
                    if (extrapolatedLocation == null) {
                        continue;
                    }

                    GeoPoint point = new GeoPoint(extrapolatedLocation);
                    double timeSpanBetweenExtrapolatedLocationsInSeconds = (System.currentTimeMillis() - extrapolatedLocation.getTime()) / 1000f;
                    long timeSpanBetweenOriginalLocationsInSecond = (System.currentTimeMillis() - originalLocation.getTime()) / 1000;
                    if (timeSpanBetweenOriginalLocationsInSecond > 10) continue;
                    double distance = timeSpanBetweenExtrapolatedLocationsInSeconds != 0 ? extrapolatedLocation.getSpeed() * timeSpanBetweenExtrapolatedLocationsInSeconds : 0;
                    point = point.destinationPoint(distance, extrapolatedLocation.getBearing());
                    extrapolatedLocation.setTime(System.currentTimeMillis());
                    extrapolatedLocation.setLatitude(point.getLatitude());
                    extrapolatedLocation.setLongitude(point.getLongitude());
                    Log.d("updateMapOverlayThread", "send " + extrapolatedLocation.toString());
                    //ExtrapolateRoad();

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            UpdateLocation(extrapolatedLocation);
                            UpdateRoad(currentRoad);
                        }
                    });
                }
            }
        }

        private void ExtrapolateRoad()
        {
            if (destinationPoint != null && currentRoad == null &&
                    (updateRoadTask == null || updateRoadTask.getStatus() == AsyncTask.Status.FINISHED))
            {
                ArrayList<GeoPoint> waypoints = new ArrayList<GeoPoint>();
                waypoints.add(new GeoPoint(originalLocation));
                waypoints.add(new GeoPoint(destinationPoint));
                updateRoadTask = new UpdateRoadTask(context);
                return;
            }
            //TODO: reset road if too far, or just update road every 10 second
            //TODO: set start of current road to extrapolated location
        }

        private class UpdateRoadTask extends AsyncTask<Object, Void, Road> {
            private final Context mContext;

            public UpdateRoadTask(Context context) {
                this.mContext = context;
            }

            protected Road doInBackground(Object... params) {
                @SuppressWarnings("unchecked")
                ArrayList<GeoPoint> waypoints = (ArrayList<GeoPoint>) params[0];
                RoadManager roadManager;
                //TODO: use built in localization
                Locale locale = Locale.getDefault();
                roadManager = new OSRMRoadManager(mContext);
                return roadManager.getRoad(waypoints);
            }

            protected void onPostExecute(Road result) {
                currentRoad = result;
            }
        }
    }
}