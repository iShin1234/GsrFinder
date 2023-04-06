package sg.edu.smu.gsrfinder

import android.graphics.Color
import android.os.Bundle
import android.util.Log
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

    companion object
    {
        private val TAG = MapsActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.d(TAG, "onCreate()")

        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap)
    {
        Log.d(TAG, "onMapReady()")

        mMap = googleMap

        var smu: LatLng? = null

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

        val lat = intent.getDoubleExtra("lat", 0.0)
        val lon = intent.getDoubleExtra("lon", 0.0)

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat,lon), 18f))
        mMap.addMarker(MarkerOptions().position(LatLng(lat,lon)).title("Your Current Location"))

        val points = mutableListOf(LatLng(lat, lon), smu)

        val mPolylineOptions = PolylineOptions()
        mPolylineOptions.addAll(points)
        mPolylineOptions.width(16f)
        mPolylineOptions.color(Color.parseColor("#1976D2")).geodesic(true).zIndex(8f)
        val line1 = mMap.addPolyline(mPolylineOptions)
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