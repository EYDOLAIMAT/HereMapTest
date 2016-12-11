package com.here.gistec.firsthereapp;
/*
 * Copyright © 2011-2016 HERE Global B.V. and its affiliate(s).
 * All rights reserved.
 * The use of this software is conditional upon having a separate agreement
 * with a HERE company for the use or utilization of this software. In the
 * absence of such agreement, the use of the software is not allowed.
 */


import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.here.android.mpa.ar.ARController;
import com.here.android.mpa.ar.ARController.Error;
import com.here.android.mpa.ar.ARIconObject;
import com.here.android.mpa.ar.ARObject;

import com.here.android.mpa.ar.ARRadarProperties;
import com.here.android.mpa.ar.CompositeFragment;
import com.here.android.mpa.common.GeoCoordinate;
import com.here.android.mpa.common.GeoPosition;
import com.here.android.mpa.common.Image;
import com.here.android.mpa.common.OnEngineInitListener;
import com.here.android.mpa.common.PositioningManager;
import com.here.android.mpa.mapping.Map;
import com.here.android.mpa.mapping.MapMarker;
import com.here.android.mpa.mapping.MapState;
import com.here.android.mpa.search.DiscoveryRequest;

import com.here.android.mpa.search.DiscoveryResultPage;
import com.here.android.mpa.search.ErrorCode;

import com.here.android.mpa.search.PlaceLink;
import com.here.android.mpa.search.ResultListener;
import com.here.android.mpa.search.SearchRequest;



public class LiveSightGuidanceActivity extends Activity implements PositioningManager.OnPositionChangedListener, Map.OnTransformListener {

    // permissions request code
    private final static int REQUEST_CODE_ASK_PERMISSIONS = 1;
    private static final double RADAR_RATIO = 4d;
    /**
     * Permissions that need to be explicitly requested from end user.
     */
    private static final String[] REQUIRED_SDK_PERMISSIONS = new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA};

    // map embedded in the composite fragment
    private Map map;
    // Sample radar implementation
    private ARRadar m_radar;
    // composite fragment embedded in this activity
    private CompositeFragment compositeFragment;
    private double angle = 0;
    // Semaphore used for pausing the rendering thread while waiting for radar updates
    private Semaphore radarSemaphore;

    // ARController is a facade for controlling LiveSight behavior
    private ARController arController;
    private PositioningManager mPositioningManager;
    private boolean mTransforming;
    // buttons which will allow the user to start LiveSight and add objects
    private Button startButton;
    private Button stopButton;
    private Button showPetrol;
    private Button toggleObjectButton;
    private List <GeoCoordinate> ListOfSearchLocations = new ArrayList<GeoCoordinate>();
    private List<ARObject> arObjects = new ArrayList<ARObject>();
    private List<MapMarker> markerObjects = new ArrayList<MapMarker>();
    private Runnable mPendingUpdate;
    // the image we will display in LiveSight
    private Image image,image2;

    // ARIconObject represents the image model which LiveSight accepts for display
    private ARIconObject arIconObject;
    private boolean objectAdded;
    private Handler m_handler = new Handler();


    @Override
    public void onPositionUpdated(final PositioningManager.LocationMethod locationMethod, final GeoPosition geoPosition, final boolean mapMatched) {
        final GeoCoordinate coordinate = geoPosition.getCoordinate();
        if (mTransforming) {
            mPendingUpdate = new Runnable() {
                @Override
                public void run() {
                    onPositionUpdated(locationMethod, geoPosition, mapMatched);
                }
            };
        } else {
            map.setCenter(coordinate, Map.Animation.BOW);
          //  updateLocationInfo(locationMethod, geoPosition);
        }
    }

    @Override
    public void onPositionFixChanged(PositioningManager.LocationMethod locationMethod, PositioningManager.LocationStatus locationStatus) {
        // ignored
    }

    @Override
    public void onMapTransformStart() {
        mTransforming = true;
    }

    @Override
    public void onMapTransformEnd(MapState mapState) {
        mTransforming = false;
        if (mPendingUpdate != null) {
            mPendingUpdate.run();
            mPendingUpdate = null;
        }
    }




    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();
    }

    private void initialize() {
        setContentView(R.layout.activity_main);

        // Search for the composite fragment to finish setup by calling init().
        compositeFragment = (CompositeFragment) getFragmentManager()
                .findFragmentById(R.id.compositefragment);
        compositeFragment.init(new OnEngineInitListener() {
            @Override
            public void onEngineInitializationCompleted(OnEngineInitListener.Error error) {
                if (error == OnEngineInitListener.Error.NONE) {
                    // retrieve a reference of the map from the composite fragment
                    map = compositeFragment.getMap();
                    // Set the map center coordinate to the Vancouver Downtown region (no animation)
                    map.setCenter(new GeoCoordinate(31.967848, 35.877766, 0.0),
                            Map.Animation.NONE);
                    map.setZoomLevel(map.getMaxZoomLevel() - 1);
                    map.addTransformListener(LiveSightGuidanceActivity.this);
                    mPositioningManager = PositioningManager.getInstance();
                    mPositioningManager.addListener(new WeakReference<PositioningManager.OnPositionChangedListener>(LiveSightGuidanceActivity.this));
                    // start position updates, accepting GPS, network or indoor positions
                    if (mPositioningManager.start(PositioningManager.LocationMethod.GPS_NETWORK_INDOOR)) {
                        map.getPositionIndicator().setVisible(true);
                    } else {
                        Toast.makeText(LiveSightGuidanceActivity.this, "PositioningManager.start: failed, exiting", Toast.LENGTH_LONG).show();
                        finish();
                    }
                    setupLiveSight();
                } else {
                    System.out.println("ERROR: Cannot initialize Composite Fragment");
                }
            }
        });

        // hold references to the buttons for future use
        startButton = (Button) findViewById(R.id.startLiveSight);
        stopButton = (Button) findViewById(R.id.stopLiveSight);
        showPetrol = (Button) findViewById(R.id.bShowPetrol);
        toggleObjectButton = (Button) findViewById(R.id.toggleObject);
    }

    /**
     * Checks the dynamically controlled permissions and requests missing permissions from end user.
     */
    protected void checkPermissions() {
        final List<String> missingPermissions = new ArrayList<String>();
        // check all required dynamic permissions
        for (final String permission : REQUIRED_SDK_PERMISSIONS) {
            final int result = ContextCompat.checkSelfPermission(this, permission);
            if (result != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (!missingPermissions.isEmpty()) {
            // request all missing permissions
            final String[] permissions = missingPermissions
                    .toArray(new String[missingPermissions.size()]);
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_ASK_PERMISSIONS);
        } else {
            final int[] grantResults = new int[REQUIRED_SDK_PERMISSIONS.length];
            Arrays.fill(grantResults, PackageManager.PERMISSION_GRANTED);
            onRequestPermissionsResult(REQUEST_CODE_ASK_PERMISSIONS, REQUIRED_SDK_PERMISSIONS,
                    grantResults);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_PERMISSIONS:
                for (int index = permissions.length - 1; index >= 0; --index) {
                    if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                        // exit the app if one permission is not granted
                        Toast.makeText(this, "Required permission '" + permissions[index]
                                + "' not granted, exiting", Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                }
                // all permissions were granted
                initialize();
                break;
        }
    }

    private void setupLiveSight() {
        // ARController should not be used until fragment init has completed
        arController = compositeFragment.getARController();
        // tells LiveSight to display icons while viewing the map (pitch down)
        arController.setUseDownIconsOnMap(true);
        // tells LiveSight to use a static mock location instead of the devices GPS fix
       // arController.setAlternativeCenter(new GeoCoordinate(31.967848, 35.877766, 0.0));
        //31.967899, 35.877751

        // Application will listen for these event to show/hide radar
        arController.addOnCameraEnteredListener(onARStarted);
        arController.addOnCameraExitedListener(onARStopped);

        // Application will process radar updates
        arController.addOnRadarUpdateListener(onRadarUpdate);
    }

    public void startLiveSight(View view) {
        if (arController != null) {
            // triggers the transition from Map mode to LiveSight mode
            Error error = arController.start();

            if (error == Error.NONE) {
                startButton.setVisibility(View.GONE);
                stopButton.setVisibility(View.VISIBLE);
                showPetrol.setVisibility(View.VISIBLE);
            } else {
                Toast.makeText(getApplicationContext(),
                        "Error starting LiveSight: " + error.toString(), Toast.LENGTH_LONG);
            }
        }
    }

    public void stopLiveSight(View view) {
        if (arController != null) {
            // exits LiveSight mode and returns to Map mode with exit animation
            Error error = arController.stop(true);

            if (error == Error.NONE) {
                startButton.setVisibility(View.VISIBLE);
                stopButton.setVisibility(View.GONE);
                showPetrol.setVisibility(View.GONE);
            } else {
                Toast.makeText(getApplicationContext(),
                        "Error stopping LiveSight: " + error.toString(), Toast.LENGTH_LONG);
            }
        }
    }


    public void ShowPetrolStation(View view) {
        startButton.setVisibility(View.GONE);
        try {
            GeoCoordinate seattle
                    = new GeoCoordinate(31.967886, 35.877766);

            DiscoveryRequest request =
                    new SearchRequest("petrol station").setSearchCenter(seattle);
            //petrol station // restaurant

            // limit number of items in each result page to 10
            request.setCollectionSize(10);

            ErrorCode error = request.execute(new SearchRequestListener());
            if( error != ErrorCode.NONE ) {
                // Handle request error
                Toast.makeText(this, "error " + error.toString(), Toast.LENGTH_LONG).show();
            }
        } catch (IllegalArgumentException ex) {
            // Handle invalid create search request parameters
            Toast.makeText(this, "Exception " + ex.toString(), Toast.LENGTH_LONG).show();
        }

    }




    public void toggleObject(View view) {
        if (arController != null) {
            if (!objectAdded) {
                if (arIconObject == null) {

                    image2 = new Image();
                    try {
                        image2.setImageResource(R.drawable.test);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                                    ///31.968434, 35.877691
                    // creates a new icon object which uses the same image in up and down views
                    arIconObject = new ARIconObject(new GeoCoordinate(31.968434, 35.877691, 2.0),
                            (View) null, image2);
                }

                // adds the icon object to LiveSight to be rendered
                arController.addARObject(arIconObject);
                objectAdded = true;
                toggleObjectButton.setText("Remove Object");
            } else {

                // removes the icon object from LiveSight, it will no longer be rendered
                arController.removeARObject(arIconObject);
                objectAdded = false;
                toggleObjectButton.setText("Add Object");
            }
        }
    }


    // Example Search request listener
    class SearchRequestListener implements ResultListener<DiscoveryResultPage> {

        @Override
        public void onCompleted(DiscoveryResultPage data, ErrorCode error) {

            if (error != ErrorCode.NONE) {
                // Handle error
                Toast.makeText(LiveSightGuidanceActivity.this, "error: " + error.toString(), Toast.LENGTH_LONG).show();
            } else {
                // Process result data
               /* List<DiscoveryResult> results = data.getItems();

                for(DiscoveryResult dv : results){
                    //GeoPosition geoCoordinate = ( GeoPosition) dv;
                    String s = dv.getTitle();
                    String ss = dv.getId();
                    String sss = dv.getResultType().toString();
                    String a = s;
                    //Place place = (Place)dv;
                }*/
                List<PlaceLink> placeLinks =data.getPlaceLinks();

                for(PlaceLink pl : placeLinks){
                    GeoCoordinate location = pl.getPosition();
                    String s = pl.getTitle();
                    String ss = pl.getCategory().toString();
                    String DD = pl.getDistance() +"";
                    String sss = s;

                    ListOfSearchLocations.add(location);
                }
                Toast.makeText(LiveSightGuidanceActivity.this, data.toString(), Toast.LENGTH_LONG).show();

                SetMarkerOfSearchResults();
            }
        }
    }

    private void SetMarkerOfSearchResults() {

        if (image == null) {
            image = new Image();
            try {
                image.setImageResource(R.drawable.petrolgas);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        if(ListOfSearchLocations.size() >0 && arController != null){

            for (GeoCoordinate coordinate : ListOfSearchLocations){

                ARIconObject object = new ARIconObject(coordinate, (View) null,
                        image);

                MapMarker marker = new MapMarker();
                marker.setIcon(image);
                marker.setCoordinate(coordinate);

                map.addMapObject(marker);
                arController.addARObject(object);
                arObjects.add(object);
                markerObjects.add(marker);

            }

        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPositioningManager != null) {
            mPositioningManager.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPositioningManager != null) {
            mPositioningManager.start(PositioningManager.LocationMethod.GPS_NETWORK_INDOOR);
        }
    }



    // Radar logic
    private ARController.OnRadarUpdateListener onRadarUpdate = new ARController.OnRadarUpdateListener() {

        @Override
        public void onRadarUpdate(ARRadarProperties radar) {
            if (m_radar != null && radar != null) {
                m_radar.Update(radar);

            }
        }
    };

    // Start radar when AR is going to camera mode
    private ARController.OnCameraEnteredListener onARStarted = new ARController.OnCameraEnteredListener() {

        @Override
        public void onCameraEntered() {
            startRadar();
        }
    };

    // Start radar when AR is going to map mode
    private ARController.OnCameraExitedListener onARStopped = new ARController.OnCameraExitedListener() {

        @Override
        public void onCameraExited() {
            stopRadar();
        }
    };

    private void startRadar() {

        if (arController == null || m_radar != null) {
            return;
        }

        final RelativeLayout layout = (RelativeLayout) LiveSightGuidanceActivity.this
                .findViewById(R.id.mainlayout);

        if (layout == null) {
            return;
        }

        m_handler.post(new Runnable() {
            public void run() {

                if (m_radar != null) {
                    // Animation conflict - fade out is still pending
                    m_radar.clearAnimation();
                }
                // Create radar
                m_radar = new ARRadar(getApplicationContext(),
                        arController.CameraParams.getHorizontalFov());

                final int width = compositeFragment.getWidth();
                final int height = compositeFragment.getHeight();

                // Calulate radar size
                final int size = (int) (Math.min(width, height) / RADAR_RATIO);

                // Add radar to top right corner of the main layout
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(size, size);
                params.setMargins(0, 5, 5, 0);
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                layout.addView(m_radar, params);

                // Animate radar fade in
                Animation animation;
                animation = new AlphaAnimation(0.0f, 1.0f);
                animation.setFillAfter(true);
                animation.setDuration(1000);
                m_radar.startAnimation(animation);
            }
        });
    }

    private void stopRadar() {

        final RelativeLayout layout = (RelativeLayout) LiveSightGuidanceActivity.this
                .findViewById(R.id.mainlayout);

        if (m_radar == null || layout == null) {
            return;
        }

        m_handler.post(new Runnable() {
            public void run() {

                m_radar.clearAnimation();

                // Animate radar fade out
                Animation animation;
                animation = new AlphaAnimation(1.0f, 0.0f);
                animation.setFillAfter(true);
                animation.setDuration(1000);

                animation.setAnimationListener(new Animation.AnimationListener() {

                    @Override
                    public void onAnimationEnd(Animation arg0) {
                        if (m_radar == null || layout == null) {
                            return;
                        }
                        layout.removeView(m_radar);
                        m_radar.clear();
                        m_radar = null;
                    }

                    @Override
                    public void onAnimationRepeat(Animation arg0) {
                    }

                    @Override
                    public void onAnimationStart(Animation arg0) {
                    }

                });
                m_radar.startAnimation(animation);
            }
        });
    }


    @Override
    public void onDestroy() {
        if (arController != null) {
            arController.removeOnRadarUpdateListener(onRadarUpdate);
            arController.removeOnCameraEnteredListener(onARStarted);
            arController.removeOnCameraExitedListener(onARStopped);
        }
        super.onDestroy();
    }


}
