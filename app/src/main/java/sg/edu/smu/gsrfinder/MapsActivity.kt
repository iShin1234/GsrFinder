package sg.edu.smu.gsrfinder

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import sg.edu.smu.gsrfinder.databinding.ActivityMapsBinding


class MapsActivity : AppCompatActivity(), OnMapReadyCallback
{

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding
    private var currentLat = 0.0
    private var currentLong = 0.0

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap)
    {
        mMap = googleMap

        var smu: LatLng? = null;

        // Add a marker in SMU and move the camera
        if(intent.getStringExtra("location") == "SCIS 1")
        {
            smu = LatLng(1.297465, 103.8495169)
            mMap.addMarker(MarkerOptions().position(smu).title("SCIS 1"))
        }
        else
        {
            smu = LatLng(1.2977584, 103.8486792)
            mMap.addMarker(MarkerOptions().position(smu).title("SCIS 2/SOE"))
        }

//        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(smu, 18f))

//        mMap.addCircle(
//            CircleOptions()
//                .center(smu)
//                .radius(30.0)
//                .strokeColor(Color.argb(128, 0, 0, 255))
//                .fillColor(Color.argb(32, 0, 0, 255))
//        )

        val lat = intent.getDoubleExtra("lat", 0.0)
        val lon = intent.getDoubleExtra("lon", 0.0)

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat,lon), 18f))
        mMap.addMarker(MarkerOptions().position(LatLng(lat,lon)).title("Your Current Location"))

        val points = mutableListOf(LatLng(lat, lon), smu)

        // PolylineOptions that defines the characteristics of line
        // PolylineOptions that defines the characteristics of line
        val mPolylineOptions = PolylineOptions()
        // points of line
        // points of line
        mPolylineOptions.addAll(points)
        // width of line that will be drawn
        // width of line that will be drawn
        mPolylineOptions.width(16f)
        // and add the color of your line and other properties
        // and add the color of your line and other properties
        mPolylineOptions.color(Color.parseColor("#1976D2")).geodesic(true).zIndex(8f)
        // finally draw the lines on the map
        // finally draw the lines on the map
        val line1 = mMap.addPolyline(mPolylineOptions)
        // change the width and color
        // change the width and color
        mPolylineOptions.width(14f)
        mPolylineOptions.color(Color.parseColor("#2196F3")).geodesic(true).zIndex(8f)
        val line2 = mMap.addPolyline(mPolylineOptions)

        mMap.addPolyline(
            line1.points.map { LatLng(it.latitude, it.longitude) }.let {
                PolylineOptions()
                    .addAll(it)
                    .width(16f)
                    .color(Color.parseColor("#1976D2"))
                    .geodesic(true)
                    .zIndex(8f)
            }
        )

        mMap.addPolyline(
            line2.points.map { LatLng(it.latitude, it.longitude) }.let {
                PolylineOptions()
                    .addAll(it)
                    .width(14f)
                    .color(Color.parseColor("#2196F3"))
                    .geodesic(true)
                    .zIndex(8f)
            }
        )
    }

}