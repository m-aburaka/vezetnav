package com.vezet.vezetnav;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

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

public class MapActivity extends AppCompatActivity implements LocationListener, Marker.OnMarkerClickListener, MapListener, View.OnTouchListener, View.OnDragListener {

    private LocationManager mLocationManager;
    private DirectedLocationOverlay myLocationOverlay;
    private IMapController mapController;
    private MapView map;

    private GeoPoint currentPoint;
    private GeoPoint destinationPoint;

    private boolean mTrackingMode = true;
    private long mTrackingModeTimeOut;
    private float mAzimuthAngleSpeed;
    private ArrayList<Marker> destinationMarkers = new ArrayList<Marker>();
    private Polyline roadOverlay;
    private Marker prevClickedMarker;
    private int SelectedType;
    private Road currentRoad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        Intent intent = getIntent();
        SelectedType = intent.getIntExtra("Type",1);

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
        map.getOverlays().add(myLocationOverlay);

        map.setMapListener(this);
        map.setOnDragListener(this);

        setLocationService();

        if (savedInstanceState != null) {
            currentPoint = savedInstanceState.getParcelable("currentPoint");
            destinationPoint = savedInstanceState.getParcelable("currentDestination");
            currentRoad = savedInstanceState.getParcelable("currentRoad");
            displayRoad(currentRoad);
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
            //location known:
            onLocationChanged(location);
        } else {
            //no location known: hide myLocationOverlay
            myLocationOverlay.setEnabled(false);
        }
    }

    /**
     * callback to store activity status before a restart (orientation change for instance)
     */
    @Override protected void onSaveInstanceState (Bundle outState){
        outState.putParcelable("currentPoint", currentPoint);
        outState.putParcelable("destinationPoint", destinationPoint);
        outState.putParcelable("currentRoad",currentRoad);
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

                marker.setIcon(getResources().getDrawable(R.mipmap.ic_launcher));
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

    protected void setDestination(GeoPoint startPoint, GeoPoint endPoint) {
        ArrayList<GeoPoint> waypoints = new ArrayList<GeoPoint>();
        waypoints.add(startPoint);
        waypoints.add(endPoint);
        TextView textView = (TextView) findViewById(R.id.textView);
        textView.setText("поиск маршрута");

        new UpdateRoadTask(this).execute(waypoints);
    }

    private void displayRoad(Road road) {
        currentRoad = road;
        if (roadOverlay != null)
            map.getOverlays().remove(roadOverlay);

        if (road == null) return;
        roadOverlay = RoadManager.buildRoadOverlay(road);
        map.getOverlays().add(roadOverlay);
        map.invalidate();

        if (road.mNodes.size() >= 1) {
            RoadNode node = road.mNodes.get(1);
            displayNextStep(node.mManeuverType, node.mLength, road.mLength, road.mDuration);
        }
    }

    private void displayNextStep(int type, double length, double overallLength, double duration) {
        TextView textView = (TextView) findViewById(R.id.textView);
        textView.setText(getLocalizedDirections(type) + " через " + String.format("%.2f", length) + "км" + "       " + String.format("%.2f", overallLength) + "км" + " " + duration + "сек. ");
    }

    @Override
    public boolean onMarkerClick(Marker marker, MapView mapView) {
        if (prevClickedMarker == null || marker != prevClickedMarker) {
            prevClickedMarker = marker;
            marker.showInfoWindow();
            InfoWindow infoWindow = marker.getInfoWindow();
            infoWindow.getView().setOnTouchListener(this);


        } else if (prevClickedMarker == marker) {
            clearDestinationMarkers();
            destinationPoint = marker.getPosition();
            setDestination(currentPoint, destinationPoint);
            prevClickedMarker.closeInfoWindow();
            return true;
        }

        return false;
    }

    public void newLocation(View view) {
        Location loc = new Location("Test");
        loc.setLatitude(currentPoint.getLatitude());
        loc.setLongitude(currentPoint.getLongitude() + 0.01);
        loc.setAltitude(0);
        loc.setAccuracy(10f);
        loc.setProvider(LocationManager.GPS_PROVIDER);
        //loc.setElapsedRealtimeNanos(System.nanoTime());
        loc.setTime(System.currentTimeMillis());
        loc.setSpeed(5);
        //loc.setTestProviderLocation("Test", loc);
        onLocationChanged(loc);
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

        GeoPoint newLocation = new GeoPoint(pLoc);
        currentPoint = newLocation;
        if (!myLocationOverlay.isEnabled()) {
            //we get the location for the first time:
            myLocationOverlay.setEnabled(true);
            map.getController().animateTo(newLocation);
            setMarkers();
        }

        GeoPoint prevLocation = myLocationOverlay.getLocation();
        myLocationOverlay.setLocation(newLocation);
        myLocationOverlay.setAccuracy((int) pLoc.getAccuracy());

        if (prevLocation != null && pLoc.getProvider().equals(LocationManager.GPS_PROVIDER)) {
            mSpeed = pLoc.getSpeed() * 3.6;
            long speedInt = Math.round(mSpeed);
            TextView speedTxt = (TextView) findViewById(R.id.speed);
            speedTxt.setText(speedInt + " km/h");

            //TODO: check if speed is not too small
            if (mSpeed >= 0.1) {
                mAzimuthAngleSpeed = pLoc.getBearing();
                myLocationOverlay.setBearing(mAzimuthAngleSpeed);
            }
        }

        long timeout = currentTime - mTrackingModeTimeOut;
        if (!mTrackingMode && timeout > 10000) {
            mTrackingMode = true;
            TextView speedTxt = (TextView) findViewById(R.id.state);
            speedTxt.setText("tracking:" + mTrackingMode);
        }

        if (mTrackingMode) {

            if (destinationPoint != null) {
                if (mSpeed < 10)
                    mapController.setZoom(20);
                else if (mSpeed < 30)
                    mapController.setZoom(18);
                else if (mSpeed < 50)
                    mapController.setZoom(15);
                else
                    mapController.setZoom(12);
            }

            //keep the map view centered on current location:
            map.getController().animateTo(newLocation);
            map.setMapOrientation(-mAzimuthAngleSpeed);

        } else {
            //just redraw the location overlay:
            map.invalidate();
        }

        if (destinationPoint != null) {
            setDestination(currentPoint, destinationPoint);
        }
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
        //TODO: not used currently
        //mSensorManager.registerListener(this, mOrientation, SensorManager.SENSOR_DELAY_NORMAL);
        //sensor listener is causing a high CPU consumption...
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mLocationManager.removeUpdates(this);
        }
        //TODO: mSensorManager.unregisterListener(this);
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
    public boolean onScroll(ScrollEvent event) {
//        mTrackingMode = false;
//        mTrackingModeTimeOut = System.currentTimeMillis();
//        TextView speedTxt = (TextView) findViewById(R.id.state);
//        speedTxt.setText("tracking:" + mTrackingMode + " scroll on " + mTrackingModeTimeOut);

        return false;
    }

    @Override
    public boolean onZoom(ZoomEvent event) {
        mTrackingMode = false;
        mTrackingModeTimeOut = System.currentTimeMillis();
        TextView speedTxt = (TextView) findViewById(R.id.state);
        speedTxt.setText("tracking:" + mTrackingMode + " zoom on " + mTrackingModeTimeOut);
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        onMarkerClick(prevClickedMarker, map);
        return false;
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        mTrackingMode = false;
        mTrackingModeTimeOut = System.currentTimeMillis();
        TextView speedTxt = (TextView) findViewById(R.id.state);
        speedTxt.setText("tracking:" + mTrackingMode + " drag on " + mTrackingModeTimeOut);
        return false;
    }

    private class UpdateRoadTask extends AsyncTask<Object, Void, Road> {

        private final Context mContext;

        public UpdateRoadTask(Context context) {
            this.mContext = context;
        }

        protected Road doInBackground(Object... params) {
            @SuppressWarnings("unchecked")
            ArrayList<GeoPoint> waypoints = (ArrayList<GeoPoint>)params[0];
            RoadManager roadManager;
            Locale locale = Locale.getDefault();
            roadManager = new OSRMRoadManager(mContext);
            return roadManager.getRoad(waypoints);
        }

        protected void onPostExecute(Road result) {
            displayRoad(result);
        }
    }
}
