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
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import org.osmdroid.api.IMapController;
import org.osmdroid.bonuspack.routing.OSRMRoadManager;
import org.osmdroid.bonuspack.routing.Road;
import org.osmdroid.bonuspack.routing.RoadManager;
import org.osmdroid.bonuspack.routing.RoadNode;
import org.osmdroid.config.Configuration;
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

public class MapActivity extends AppCompatActivity implements LocationListener, Marker.OnMarkerClickListener, View.OnTouchListener, InterceptedTouchListener {
    public static final String TRACKING_MODE = "TRACKING_MODE";
    private static final String UPDATE_ROAD = "UPDATE_ROAD";
    public static final String UPDATE_MAP_OVERLAY = "UPDATE_MAP_OVERLAY";
    private LocationManager mLocationManager;
    private DirectedLocationOverlay myLocationOverlay;
    private IMapController mapController;
    private MapView map;

    private GeoPoint currentPoint;
    private GeoPoint destinationPoint;

    private boolean mTrackingMode = false;
    private long mTrackingModeTimeOut;
    private ArrayList<Marker> destinationMarkers = new ArrayList<Marker>();
    private Polyline roadOverlay;
    private Marker prevClickedMarker;
    private int SelectedType;
    private Road currentRoad;
    private UpdateMapOverlayThread backgroundThread;
    private ArrayList<Marker> roadMarkers = new ArrayList<Marker>();
    private TextView instructionsTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        instructionsTextView = (TextView) findViewById(R.id.textView);

        setLocale();

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

        //that suspends updates for 1000+1000 millis
        mTrackingModeTimeOut = System.currentTimeMillis() + 1000;

        CustomConstraintLayout layout = (CustomConstraintLayout) findViewById(R.id.layout);
        layout.setOnInterceptedTouchListener(this);

        Handler handler = new Handler();
        backgroundThread = new UpdateMapOverlayThread(handler, this);

        setLocationService();

        if (savedInstanceState != null) {
            currentPoint = savedInstanceState.getParcelable("currentPoint");
            destinationPoint = savedInstanceState.getParcelable("destinationPoint");
            currentRoad = savedInstanceState.getParcelable("originalRoad");
            backgroundThread.setDestination(destinationPoint);
            backgroundThread.originalRoad = currentRoad;
        }
        if (destinationPoint == null)
            setMarkers();

        backgroundThread.start();
    }

    private void setLocale() {
        Locale locale = new Locale("ru");
        Locale.setDefault(locale);
        android.content.res.Configuration config = getBaseContext().getResources().getConfiguration();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale                    );
        } else {
            config.locale = locale;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getApplicationContext().createConfigurationContext(config);
        } else {
            getApplicationContext().getResources().updateConfiguration(config, null);
        }
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
        outState.putParcelable("originalRoad", backgroundThread.originalRoad);
    }

    private void setMarkers() {
        if (currentPoint == null) return;

        if (BuildConfig.ENABLE_RANDOM_MARKERS) {

            //add points every two kilometers on 0, 90, 180, 270 degrees;
            for (int i = 1; i < 50; i++){
                GeoPoint point0 = currentPoint.destinationPoint(2000*i, 0);
                GeoPoint point90 = currentPoint.destinationPoint(2000*i, 90);
                GeoPoint point180 = currentPoint.destinationPoint(2000*i, 180);
                GeoPoint point270 = currentPoint.destinationPoint(2000*i, 270);

                MainActivity.locations.add(new CustomLocation("Random", SelectedType, "desc", "subdesc", (float)point0.getLongitude(), (float)point0.getLatitude(), "mipmap/ic_launcher"));
                MainActivity.locations.add(new CustomLocation("Random", SelectedType, "desc", "subdesc", (float)point90.getLongitude(), (float)point90.getLatitude(), "mipmap/ic_launcher"));
                MainActivity.locations.add(new CustomLocation("Random", SelectedType, "desc", "subdesc", (float)point180.getLongitude(), (float)point180.getLatitude(), "mipmap/ic_launcher"));
                MainActivity.locations.add(new CustomLocation("Random", SelectedType, "desc", "subdesc", (float)point270.getLongitude(), (float)point270.getLatitude(), "mipmap/ic_launcher"));
            }
        }

        for (CustomLocation location : MainActivity.locations) {
            double distance = currentPoint.distanceTo(location.getGeoPoint());

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
        if (marker == null) {
            prevClickedMarker = null;
            return false;
        }
        if (prevClickedMarker == null || marker != prevClickedMarker) {
            prevClickedMarker = marker;
            mapController.animateTo(marker.getPosition());
            marker.showInfoWindow();
            InfoWindow infoWindow = marker.getInfoWindow();
            infoWindow.getView().setOnTouchListener(this);

        } else if (prevClickedMarker == marker) {
            clearDestinationMarkers();
            destinationPoint = marker.getPosition();
            instructionsTextView.setText("поиск маршрута");
            backgroundThread.setDestination(destinationPoint);
            mTrackingMode = true;
            prevClickedMarker.closeInfoWindow();
            return true;
        }

        return false;
    }

    public void zoomInButtonClick(View view) {
        mapController.zoomIn();
        mTrackingMode = true;
    }

    public void zoomOutButtonClick(View view) {
        mapController.zoomOut();
        mTrackingMode = true;
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
            update(pLoc, null);
            trackMap(pLoc);
            setMarkers();
        }
        //next time, update from background thread
        else {
            backgroundThread.setLocation(pLoc);
        }
    }

    private void update(Location location, Road road) {
        updateRoad(road);
        updateTrackingMode();
        updateLocationOverlay(location);

        if (mTrackingMode)
            trackMap(location);

        map.invalidate();
    }

    private void updateLocationOverlay(Location location) {
        //GeoPoint prevLocation = myLocationOverlay.getLocation();
        myLocationOverlay.setLocation(new GeoPoint(location));
        myLocationOverlay.setEnabled(true);

        double speed = location.getSpeed() * 3.6;
        float azimuthAngleSpeed = location.getBearing();

        //i don't know why original code checked for prevLocation
        //if (prevLocation != null && speed >= 0.1) {
        if (speed >= 0.1) {
            myLocationOverlay.setBearing(azimuthAngleSpeed);
        }
    }

    private void updateTrackingMode() {
        long currentTime = System.currentTimeMillis();
        long timeout = currentTime - mTrackingModeTimeOut;
        if (!mTrackingMode && timeout > 5000) {
            mTrackingMode = true;
            Log.d(TRACKING_MODE, "tracking:" + mTrackingMode);
        }
    }

    private boolean trackMap(Location location) {
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
        if (deltaDistance > 5) {
            //map.getController().animateTo(newPoint);
            mapController.setCenter(newPoint);
        }
        float deltaOrientation = Math.abs(map.getMapOrientation() - -azimuthAngleSpeed);
        //update orientation only if path is set and markers not needed. otherwise marker's hittest would not work
        if (deltaOrientation > 10 && currentRoad != null)
            map.setMapOrientation(-azimuthAngleSpeed);

        return deltaDistance <= 100 || deltaOrientation <= 10;
    }

    private void updateRoad(Road road) {
        currentRoad = road;
        if (roadOverlay != null)
            map.getOverlays().remove(roadOverlay);

        if (road == null) {
            if (destinationPoint != null) {
                instructionsTextView.setText("поиск маршрута");
            }
            return;
        }

        if (road.mNodes.size() >= 1) {
            RoadNode node = road.mNodes.get(0);

            updateNextStep(node, road);
        }

        for (Marker marker:
                roadMarkers) {
            map.getOverlays().remove(marker);
        }

        if (BuildConfig.ENABLE_ROAD_NODES) {
            for (RoadNode node :
                    road.mNodes) {
                Marker marker = new Marker(map);
                marker.setPosition(node.mLocation);
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                map.getOverlays().add(marker);
                roadMarkers.add(marker);

                marker.setIcon(getResources().getDrawable(R.mipmap.ic_launcher));
            }
        }

        roadOverlay = RoadManager.buildRoadOverlay(road);
        map.getOverlays().add(roadOverlay);
    }

    private void updateNextStep(RoadNode node, Road road) {
        instructionsTextView.setText((node.mInstructions != null ? node.mInstructions : "Продолжайте движение" + " через") + " "
                + String.format("%.2f", node.mLength) + "км" + "       "
                + String.format("%.2f", road.mLength) + "км" + " " + String.format("%.2f", road.mDuration) + "сек. ");
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

    @Override
    protected void onResume() {
        super.onResume();
        startLocationUpdates();
        backgroundThread.isPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mLocationManager.removeUpdates(this);
        }
        backgroundThread.isPaused = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundThread.isStopped = true;
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
        Log.d(TRACKING_MODE, "tracking:" + mTrackingMode + " intercepted touch on " + mTrackingModeTimeOut);
    }

    class UpdateMapOverlayThread extends Thread {
        private final Handler mHandler;
        private final Context context;
        private Location extrapolatedLocation;
        private Location originalLocation;
        private GeoPoint destinationPoint;
        private Road originalRoad;
        private Road extrapolatedRoad;
        private UpdateRoadTask updateRoadTask;
        private float distanceToNextNode;
        private RoadNode nextNode;
        private long lastRoadCreationTime;
        private boolean resetRoad = false;
        private boolean isStopped = false;
        private boolean isPaused = false;

        UpdateMapOverlayThread(Handler handler, Context cont) {
            mHandler = handler;
            context = cont;
        }

        public void setLocation(Location loc) {
            synchronized (this) {
                if (originalLocation != null && originalLocation.getLatitude() == loc.getLatitude() && originalLocation.getLongitude() == loc.getLongitude()) {
                    Log.d(UPDATE_MAP_OVERLAY, "ignore " + loc.toString());
                    return;
                }

                extrapolatedLocation = new Location(loc);
                originalLocation = new Location(loc);

                Log.d(UPDATE_MAP_OVERLAY, "set " + loc.toString());
            }
        }

        public void setDestination(GeoPoint destination) {
            if (destination == destinationPoint) return;
            originalRoad = null;
            if (updateRoadTask != null)
                updateRoadTask.cancel(true);
            destinationPoint = destination;
        }

        public void run() {
            while (true) {
                try {
                    Thread.sleep(300);
                    if (isPaused) {
                        Thread.sleep(2000);
                        continue;
                    }
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (isStopped)
                    return;

                synchronized (this) {
                    if (extrapolatedLocation == null) {
                        continue;
                    }

                    //if user has touched map in last second, skip update to speed up map rendering
                    if (System.currentTimeMillis() - mTrackingModeTimeOut < 1000) continue;

                    GeoPoint point = new GeoPoint(extrapolatedLocation);
                    double timeSpanBetweenExtrapolatedLocationsInSeconds = (System.currentTimeMillis() - extrapolatedLocation.getTime()) / 1000f;
                    long timeSpanBetweenOriginalLocationsInSecond = (System.currentTimeMillis() - originalLocation.getTime()) / 1000;

                    //if location is recent, extrapolate
                    if (timeSpanBetweenOriginalLocationsInSecond < 10) {
                        double distance = timeSpanBetweenExtrapolatedLocationsInSeconds != 0 ? extrapolatedLocation.getSpeed() * timeSpanBetweenExtrapolatedLocationsInSeconds : 0;
                        point = point.destinationPoint(distance, extrapolatedLocation.getBearing());
                        extrapolatedLocation.setTime(System.currentTimeMillis());
                        extrapolatedLocation.setLatitude(point.getLatitude());
                        extrapolatedLocation.setLongitude(point.getLongitude());
                    }
                    //if location is old, reset to original
                    else {
                        extrapolatedLocation = new Location(originalLocation);
                    }
                    Log.d(UPDATE_MAP_OVERLAY, "send " + extrapolatedLocation.toString());

                    extrapolateRoad();

                    //send COPY to avoid threading issues
                    final Road extrapolatedRoadCopy = extrapolatedRoad != null ? copyRoad(extrapolatedRoad) : null;
                    calcDistance(extrapolatedRoadCopy);

                    //see if user getting closer to next node, with said precision
                    if (extrapolatedRoad != null && extrapolatedRoad.mRouteHigh.size() > 1 && extrapolatedRoad.mNodes.size() > 0) {
                        GeoPoint point1 = new GeoPoint(extrapolatedLocation);
                        GeoPoint point2 = extrapolatedRoad.mRouteHigh.get(1);
                        double bearing = point1.bearingTo(point2);
                        double deltaBearing = Math.abs(bearing - extrapolatedLocation.getBearing());
                        Log.d(UPDATE_ROAD, "deltaBearing " + deltaBearing);
                        if (deltaBearing > 10 && point1.distanceTo(point2) > 50) {
                            resetRoad = true;
                            Log.d(UPDATE_ROAD, "reset road cause bearing");
                        }

                        float newDistanceToNextNode = extrapolatedRoad.mNodes.get(0).mLocation.distanceTo(new GeoPoint(originalLocation));

                        //if measuring relative to same node, of course

                        //nextNode.mLocation.bearingTo()

                        //if (nextNode == extrapolatedRoad.mNodes.get(0) && newDistanceToNextNode > distanceToNextNode + 5) {
                            //originalRoad = null;
                            //Log.d(UPDATE_ROAD, "reset road cause distance");
                        //}

                        //reset nextNode if user already drove past it
                        nextNode = extrapolatedRoad.mNodes.get(0);

                        distanceToNextNode = newDistanceToNextNode;
                    }

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            update(extrapolatedLocation, extrapolatedRoadCopy);
                        }
                    });
                }
            }
        }

        private void extrapolateRoad() {
            //if no destination
            if (destinationPoint == null) return;


            long dT = System.currentTimeMillis() - lastRoadCreationTime;
            //road reset, but not > than every 10 seconds
            if ((originalRoad == null || resetRoad) && dT > 10000) {
                //if task not set or not reset for new destination
                if (updateRoadTask == null || updateRoadTask.getStatus() == AsyncTask.Status.FINISHED) {
                    ArrayList<GeoPoint> waypoints = new ArrayList<GeoPoint>();
                    waypoints.add(new GeoPoint(originalLocation));
                    waypoints.add(new GeoPoint(destinationPoint));
                    updateRoadTask = new UpdateRoadTask(context);
                    updateRoadTask.execute(waypoints);
                    resetRoad = false;
                    Log.d(UPDATE_ROAD, "new road task set");
                }
            }
            //if task is set but not finished, wait
            if (originalRoad == null) return;

            //if path not found, reset and return
            if (originalRoad.mNodes.size() <= 1) {
                originalRoad = null;
                return;
            }

            extrapolatedRoad = copyRoad(originalRoad);
            removeOldNodesInOverlay(extrapolatedRoad);
            extrapolatedRoad.mRouteHigh.set(0, new GeoPoint(extrapolatedLocation));

            //Log.d(UPDATE_ROAD, "extrapolatedRoad nodes " + extrapolatedRoad.mNodes.size());
            //Log.d(UPDATE_ROAD, "extrapolatedRoad start " + extrapolatedRoad.mNodes.get(0).mLocation);
        }

        private void removeOldNodesInOverlay(Road road) {
            //next node will be closest node forward (less than 90 degrees)
            //find it and remove all previous

            GeoPoint nextPoint = null;
            float minDistance = Float.MAX_VALUE;
            for (GeoPoint point : road.mRouteHigh) {
                int deltaDistance = point.distanceTo(new GeoPoint(extrapolatedLocation));
                double bearing = point.bearingTo(new GeoPoint(extrapolatedLocation));
                double deltaBearing = Math.abs(extrapolatedLocation.getBearing() - bearing);

                if (deltaDistance < minDistance && deltaBearing < 90) {
                    nextPoint = point;
                    minDistance = deltaDistance;
                }
            }

            if (nextPoint == null) return;
            int index = road.mRouteHigh.indexOf(nextPoint);

            //also, find closest point for next node
            //if we removing that point, remove node too

            GeoPoint closestPointToNextNode = getClosestPointToNextNode(road);

            while (index > 0) {
                if (road.mRouteHigh.get(0) == closestPointToNextNode) {
                    road.mNodes.remove(0);
                    closestPointToNextNode = getClosestPointToNextNode(road);
                }
                road.mRouteHigh.remove(0);
                index--;
            }
        }

        private Road copyRoad(Road road) {
            Road newRoad = new Road();

            for (RoadNode node : road.mNodes) {
                newRoad.mNodes.add(node);
            }

            for (GeoPoint point :
                    road.mRouteHigh) {
                newRoad.mRouteHigh.add(point);
            }

            return newRoad;
        }

        private void calcDistance(Road road) {

            if (road == null || road.mNodes.size() < 1) return;

            for (RoadNode node : road.mNodes) {
                road.mDuration += node.mDuration;
            }

            //length to next node = length of all points till next node
            RoadNode nextNode = road.mNodes.get(0);
            GeoPoint lastPointTillNextNode = getClosestPointToNextNode(road);
            int i = 0;
            nextNode.mLength = 0;
            while(road.mRouteHigh.size() > i + 1 && lastPointTillNextNode != road.mRouteHigh.get(i)) {
                GeoPoint nextPoint = road.mRouteHigh.get(i + 1);
                GeoPoint thisPoint = road.mRouteHigh.get(i);
                double distanceTo = thisPoint.distanceTo(nextPoint);
                nextNode.mLength += distanceTo / 1000f;
                i++;
            }

            road.mLength = 0;
            for (RoadNode node :
                    road.mNodes) {
                road.mLength += node.mLength;
            }
        }

        private GeoPoint getClosestPointToNextNode(Road road)
        {
            RoadNode node = road.mNodes.get(0);
            GeoPoint nextPoint = null;
            float minDistance = Float.MAX_VALUE;
            for (GeoPoint point : road.mRouteHigh) {
                int distance = point.distanceTo(node.mLocation);
                if (distance < minDistance) {
                    nextPoint = point;
                    minDistance = distance;
                }
            }
            return nextPoint;
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

                roadManager = new OSRMRoadManager(mContext);
                return roadManager.getRoad(waypoints);
            }

            protected void onPostExecute(Road result) {
                if (result != null && result.mNodes.size() > 0) {
                    result.mNodes.get(0).mInstructions = "Начало маршрута";
                    result.mNodes.get(result.mNodes.size() - 1).mInstructions = "Место назначения";
                }

                //change "distance to next node" to "distance to this node"
                for (int i = 0; i < result.mNodes.size() - 1; i++) {
                    RoadNode node = result.mNodes.get(i);
                    if (i == 0)
                        node.mLength = 0;
                    else
                        node.mLength = result.mNodes.get(i + 1).mLength;
                }

                lastRoadCreationTime = System.currentTimeMillis();
                Log.d(UPDATE_ROAD, "new road task done");

                //if road the same as previous (except start location), ignore
                boolean sameRoadAsPrevious = true;
                if (originalRoad == null || originalRoad.mNodes.size() != result.mNodes.size())
                {
                    sameRoadAsPrevious = false;
                }
                else
                {
                    for (int i = 1; i < originalRoad.mNodes.size(); i++) {
                        RoadNode node = originalRoad.mNodes.get(i);
                        if (node.mLocation.distanceTo(result.mNodes.get(i).mLocation) != 0) {
                            sameRoadAsPrevious = false;
                            break;
                        }
                    }
                }

//                Log.d(UPDATE_ROAD, String.valueOf(result.mLength));
//                for (RoadNode node :
//                        result.mNodes) {
//                    Log.d(UPDATE_ROAD, node.mInstructions + " " + String.valueOf(node.mLength));
//                }

                if (!sameRoadAsPrevious){
                    Log.d(UPDATE_ROAD, "new road set");
                    originalRoad = result;}
                else
                    Log.d(UPDATE_ROAD, "new road same as previous");
            }
        }
    }
}