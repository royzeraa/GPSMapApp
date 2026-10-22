package com.example.simplegpsmapapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.HandlerThread;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla principal: muestra un mapa con la ubicación actual del usuario,
 * permite añadir marcadores (pulsación larga) y capturar/mostrar una imagen.
 *
 * Threads utilizados:
 *  - HandlerThread "gps-location-thread": recibe las actualizaciones del GPS fuera del hilo principal.
 *  - ExecutorService (un hilo): Geocoder (dirección de un marcador) y guardado de la foto en disco.
 *  - runOnUiThread(): devuelve los resultados al hilo de la interfaz.
 */
public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int LOCATION_REQUEST_CODE = 101;
    private static final String MAPVIEW_BUNDLE_KEY = "MapViewBundleKey";

    private MapView mapView;
    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;

    private TextView tvStatus;
    private TextView tvMarkers;
    private ImageView ivPhoto;

    // Threads
    private HandlerThread locationThread;
    private LocationCallback locationCallback;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    // Estado
    private Location lastLocation;
    private Marker currentLocationMarker;
    private boolean firstFixHandled = false;
    private boolean updatesActive = false;
    private final List<Marker> userMarkers = new ArrayList<>();
    private int markerCounter = 0;

    // Captura de imagen (miniatura) con la cámara del dispositivo
    private final ActivityResultLauncher<Void> takePicture =
            registerForActivityResult(new ActivityResultContracts.TakePicturePreview(), this::onPictureTaken);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvMarkers = findViewById(R.id.tvMarkers);
        ivPhoto = findViewById(R.id.ivPhoto);
        Button btnCapture = findViewById(R.id.btnCapture);
        Button btnCenter = findViewById(R.id.btnCenter);
        Button btnClear = findViewById(R.id.btnClear);

        // MapView: requiere reenviar el ciclo de vida de la Activity
        Bundle mapViewBundle = null;
        if (savedInstanceState != null) {
            mapViewBundle = savedInstanceState.getBundle(MAPVIEW_BUNDLE_KEY);
        }
        mapView = findViewById(R.id.mapView);
        mapView.onCreate(mapViewBundle);
        mapView.getMapAsync(this);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Hilo dedicado para recibir las lecturas del GPS
        locationThread = new HandlerThread("gps-location-thread");
        locationThread.start();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) {
                    // Estamos en gps-location-thread: pasamos a la UI para tocar las vistas
                    runOnUiThread(() -> onNewLocation(location));
                }
            }
        };

        btnCapture.setOnClickListener(v -> {
            try {
                takePicture.launch(null);
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_camera, Toast.LENGTH_SHORT).show();
            }
        });
        btnCenter.setOnClickListener(v -> {
            if (lastLocation != null && mMap != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()), 16));
            } else {
                Toast.makeText(this, R.string.no_location_yet, Toast.LENGTH_SHORT).show();
            }
        });
        btnClear.setOnClickListener(v -> clearUserMarkers());

        checkLocationPermission();
    }

    // ---------------------------------------------------------------------------------------
    // Permisos en tiempo de ejecución (pauta, paso 9)
    // ---------------------------------------------------------------------------------------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_REQUEST_CODE);
        } else {
            enableMyLocation();
            getCurrentLocation();
            startLocationUpdates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST_CODE) {
            if (hasLocationPermission()) {
                enableMyLocation();
                getCurrentLocation();
                startLocationUpdates();
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Mapa
    // ---------------------------------------------------------------------------------------

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);

        // Pulsación larga = añadir marcador
        mMap.setOnMapLongClickListener(this::addUserMarker);

        if (hasLocationPermission()) {
            enableMyLocation();
            getCurrentLocation();
        }
    }

    @SuppressLint("MissingPermission")
    private void enableMyLocation() {
        if (mMap != null && hasLocationPermission()) {
            mMap.setMyLocationEnabled(true);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ubicación (GPS)
    // ---------------------------------------------------------------------------------------

    /** Última ubicación conocida, para centrar el mapa rápidamente. */
    @SuppressLint("MissingPermission")
    private void getCurrentLocation() {
        if (!hasLocationPermission()) {
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                onNewLocation(location);
            }
        });
    }

    /** Actualizaciones continuas en el hilo "gps-location-thread". */
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (updatesActive || !hasLocationPermission()) {
            return;
        }
        LocationRequest request = LocationRequest.create()
                .setInterval(5000)
                .setFastestInterval(2000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        fusedLocationClient.requestLocationUpdates(request, locationCallback, locationThread.getLooper());
        updatesActive = true;
    }

    private void stopLocationUpdates() {
        if (updatesActive) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            updatesActive = false;
        }
    }

    /** Se ejecuta siempre en el hilo principal. */
    private void onNewLocation(@NonNull Location location) {
        lastLocation = location;
        tvStatus.setText(getString(R.string.status_format,
                location.getLatitude(), location.getLongitude(), location.getAccuracy()));

        if (mMap != null && !firstFixHandled) {
            firstFixHandled = true;
            LatLng current = new LatLng(location.getLatitude(), location.getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(current, 15));
            currentLocationMarker = mMap.addMarker(new MarkerOptions()
                    .position(current)
                    .title(getString(R.string.current_location)));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Marcadores
    // ---------------------------------------------------------------------------------------

    private void addUserMarker(@NonNull LatLng position) {
        markerCounter++;
        Marker marker = mMap.addMarker(new MarkerOptions()
                .position(position)
                .title(getString(R.string.marker_title, markerCounter)));
        if (marker == null) {
            return;
        }
        userMarkers.add(marker);
        updateMarkersLabel();

        // El Geocoder hace red: se consulta fuera del hilo principal
        backgroundExecutor.execute(() -> {
            String address = resolveAddress(position);
            if (address != null) {
                runOnUiThread(() -> {
                    if (!isFinishing() && userMarkers.contains(marker)) {
                        marker.setSnippet(address);
                        if (marker.isInfoWindowShown()) {
                            marker.showInfoWindow(); // refresca la ventana
                        }
                    }
                });
            }
        });
    }

    private String resolveAddress(LatLng position) {
        try {
            Geocoder geocoder = new Geocoder(this, new Locale("es", "CL"));
            List<Address> results = geocoder.getFromLocation(position.latitude, position.longitude, 1);
            if (results != null && !results.isEmpty()) {
                return results.get(0).getAddressLine(0);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Sin red o sin servicio de geocodificación: el marcador queda sin dirección
        }
        return null;
    }

    private void clearUserMarkers() {
        for (Marker m : userMarkers) {
            m.remove();
        }
        userMarkers.clear();
        markerCounter = 0;
        updateMarkersLabel();
    }

    private void updateMarkersLabel() {
        if (userMarkers.isEmpty()) {
            tvMarkers.setText(R.string.marker_hint);
        } else {
            tvMarkers.setText(getString(R.string.markers_count, userMarkers.size()));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Captura y visualización de imagen
    // ---------------------------------------------------------------------------------------

    private void onPictureTaken(Bitmap bitmap) {
        if (bitmap == null) {
            return; // el usuario canceló
        }
        // Se muestra de inmediato y se guarda en disco en un hilo aparte
        ivPhoto.setImageBitmap(bitmap);
        ivPhoto.setVisibility(ImageView.VISIBLE);

        backgroundExecutor.execute(() -> {
            boolean ok = savePhoto(bitmap);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? R.string.photo_saved : R.string.photo_error, Toast.LENGTH_SHORT).show());
        });
    }

    private boolean savePhoto(Bitmap bitmap) {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            return bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida (MapView + GPS)
    // ---------------------------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle mapViewBundle = outState.getBundle(MAPVIEW_BUNDLE_KEY);
        if (mapViewBundle == null) {
            mapViewBundle = new Bundle();
            outState.putBundle(MAPVIEW_BUNDLE_KEY, mapViewBundle);
        }
        mapView.onSaveInstanceState(mapViewBundle);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (hasLocationPermission()) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        stopLocationUpdates(); // ahorra batería cuando la app no está visible
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        locationThread.quitSafely();
        backgroundExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}
