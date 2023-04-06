package sg.edu.smu.gsrfinder

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.material.navigation.NavigationView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import sg.edu.smu.gsrfinder.common.helpers.CameraPermissionHelper
import java.util.*

class MainActivity : AppCompatActivity()
{
    private lateinit var spinFrom: String
    private lateinit var spinToSchool: String
    private lateinit var spinToRoom: String
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var actionBarDrawerToggle: ActionBarDrawerToggle
    private val speechRec = 102
    private var mUserRequestedInstall = true

    companion object
    {
        private val TAG = MainActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.d(TAG, "onCreate()")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.my_drawer_layout)
        actionBarDrawerToggle = ActionBarDrawerToggle(this, drawerLayout, R.string.nav_open, R.string.nav_close)
        drawerLayout.addDrawerListener(actionBarDrawerToggle)
        actionBarDrawerToggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navigationView = findViewById(R.id.naviationView)
        navigationView.setNavigationItemSelectedListener {
            when (it.itemId)
            {
                R.id.admin ->
                {
                    AdminClick()
                    true
                }
                else ->
                    false
            }
        }
        initSpinFrom(true)
        initSpinToSchool()
        initSpinToRoom("SCIS 1")
        maybeEnableArButton()
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        Log.d(TAG, "onOptionsItemSelected()")

        return if (actionBarDrawerToggle.onOptionsItemSelected(item))
        {
            true
        }
        else
        {
            super.onOptionsItemSelected(item)
            }
        }

    private fun initSpinFrom(inSCIS:Boolean)
    {
        Log.d(TAG, "initSpinFrom()")

        val list: Array<String> = if(inSCIS)
        {
            resources.getStringArray(R.array.scisList)
        }
        else
        {
            resources.getStringArray(R.array.currentLocationList)
        }

        val spinAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
        val spinFrom = findViewById<Spinner>(R.id.spinFrom)
            spinFrom.adapter = spinAdapter

        spinFrom.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                val selectedString = parent!!.getItemAtPosition(index).toString()
                this@MainActivity.spinFrom = selectedString
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d(TAG, "initSpinFrom() - onNothingSelected()")
            }
        }
    }

    fun clickAllowLocation(view: View)
    {
        Log.d(TAG, "clickAllowLocation()")

        requestLocationPermission()
    }

    private fun requestLocationPermission()
    {
        Log.d(TAG, "requestLocationPermission()")

        if (ContextCompat.checkSelfPermission(this@MainActivity,ACCESS_FINE_LOCATION) !==PackageManager.PERMISSION_GRANTED)
        {
            if(ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity,ACCESS_FINE_LOCATION))
            {
                ActivityCompat.requestPermissions(this@MainActivity,arrayOf(ACCESS_FINE_LOCATION), 1)
            }
            else
            {
                ActivityCompat.requestPermissions(this@MainActivity,arrayOf(ACCESS_FINE_LOCATION), 1)
            }
        }
        else
        {
            Toast.makeText(this, "Permission already granted", Toast.LENGTH_SHORT).show()
        }
        initSpinFrom(true)
    }

    private fun initSpinToSchool()
    {
        Log.d(TAG, "initSpinToSchool()")

        val schoolList = resources.getStringArray(R.array.schoolList)
        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, schoolList)
        val spinToSchool = findViewById<Spinner>(R.id.spinToSchool)
        spinToSchool.adapter = spinAdapter

        spinToSchool.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d(TAG, "initSpinToSchool() - onItemSelected()")
                val selectedString = parent!!.getItemAtPosition(index).toString()

                this@MainActivity.spinToSchool = selectedString

                initSpinToRoom(selectedString)
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("MainActivity", "initSpinToSchool() - onNothingSelected()")
            }
        }
    }

    private fun initSpinToRoom(school: String)
    {
        Log.d(TAG, "initSpinToRoom()")

        when(school)
        {
            "SCIS 1" ->
            {
                showRoom(resources.getStringArray(R.array.scis1GsrList))
            }
            "SCIS 2/SOE" ->
            {
                showRoom(resources.getStringArray(R.array.scis2soeGsrList))
            }
        }
    }

    private fun showRoom(gsrList: Array<String>)
    {
        Log.d(TAG, "showRoom()")

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, gsrList)
        val spinToRoom = findViewById<Spinner>(R.id.spinToRoom)
        spinToRoom.adapter = spinAdapter

        spinToRoom.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                val selectedString = parent!!.getItemAtPosition(index).toString()

                this@MainActivity.spinToRoom = selectedString
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("MainActivity", "showRoom() - onNothingSelected()")
            }
        }
    }

    fun AdminClick()
    {
        Log.d(TAG, "AdminClick()")

        val arIntent = Intent(this, CloudAnchorActivity::class.java)
        startActivity(arIntent)
    }

    fun startMapActivity(destination:String)
    {
        Log.d(TAG, "startMapActivity()")

        if (checkSelfPermission(ACCESS_FINE_LOCATION)== PackageManager.PERMISSION_GRANTED)
        {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            fusedLocationClient.lastLocation.addOnSuccessListener(this, OnSuccessListener { location: Location? ->
                if (location != null)
                {
                    val lat = location.latitude
                    val lon = location.longitude
                    getLocationByLatLong(lat, lon, destination)
                }
            })
        }
        else
        {
            Toast.makeText(this, "Please request for location", Toast.LENGTH_SHORT).show()
            requestLocationPermission()
        }
    }

    private fun getLocationByLatLong(lat: Double, lon: Double, destination: String)
    {
        Log.d(TAG, "getLocationByLatLong()")

        var currentLocation = Location("")
        currentLocation.latitude = lat
        currentLocation.longitude = lon

        var distanceFromCurrentLocationToLandMark: Float? = null
        val mapIntent = Intent(this, MapsActivity::class.java)

        if(spinToSchool == "SCIS 1" && destination == "" || destination.contains("SCIS 1"))
        {
            var scisLat = 1.297465
            var scisLong = 103.8495169

            var scisLandMark = Location("")
            scisLandMark.latitude = scisLat
            scisLandMark.longitude = scisLong

            distanceFromCurrentLocationToLandMark = currentLocation.distanceTo(scisLandMark)
            mapIntent.putExtra("location", "SCIS 1")
        }
        else if(spinToSchool == "SCIS 2/SOE" && destination == "" || destination.contains("SCIS 2/SOE"))
        {
            var scisLat = 1.2977584
            var scisLong = 103.8486792

            var scisLandMark = Location("")
            scisLandMark.latitude = scisLat
            scisLandMark.longitude = scisLong

            distanceFromCurrentLocationToLandMark = currentLocation.distanceTo(scisLandMark)
            mapIntent.putExtra("location", "SCIS 2")
        }

        if(distanceFromCurrentLocationToLandMark!! < 50)
        {
            val myIntent = Intent(this, UserCloudAnchorActivity::class.java)

            if(destination != "")
            {
                myIntent.putExtra("location", destination)
                startActivity(myIntent)
            }
            else {
                myIntent.putExtra("location", "$spinToSchool $spinToRoom")
                startActivity(myIntent)
            }
        }
        else
        {
            mapIntent.putExtra("lat", lat)
            mapIntent.putExtra("lon", lon)
            startActivity(mapIntent)
        }
    }

    private fun getAddress(lat: Double, lon: Double): String
    {
        Log.d(TAG, "getAddress()")

        val geocoder = Geocoder(this, Locale.getDefault())
        val addresses: List<Address> = geocoder.getFromLocation(lat, lon, 1) as List<Address>
        val address: String = addresses[0].getAddressLine(0)

        return address
    }

    private fun maybeEnableArButton()
    {
        Log.d(TAG, "maybeEnableArButton()")

        val availability = ArCoreApk.getInstance().checkAvailability(this@MainActivity)
        if (availability.isTransient)
        {
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "maybeEnableArButton() - AR Transient")
                maybeEnableArButton()
            }, 200)
        }
        val myArButton = findViewById<Button>(R.id.btnGetStarted)
        if (availability.isSupported)
        {
            myArButton.isEnabled = true
        }
        else
        {
            Log.d("MainActivity", "maybeEnableArButton() - AR Not Supported")
            findViewById<TextView>(R.id.tvdisclaimer).text = "Please install Google Play Service for AR"
            myArButton.isEnabled = false
            setupAR()
        }
    }

    private fun setupAR()
    {
        Log.d(TAG, "setupAR()")

        if (!CameraPermissionHelper.hasCameraPermission(this))
        {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        try
        {
            when (ArCoreApk.getInstance().requestInstall(this, mUserRequestedInstall))
            {
                ArCoreApk.InstallStatus.INSTALLED ->
                {
                    Log.d(TAG, "setupAR() - AR Installed")
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED ->
                {
                    Log.d(TAG, "setupAR() - AR Install Requested")
                    mUserRequestedInstall = false
                    return
                }
            }
        }
        catch (e: UnavailableUserDeclinedInstallationException)
        {
            Toast.makeText(this, "TODO: handle exception $e", Toast.LENGTH_LONG).show()
            return
        }
    }

    override fun onResume()
    {
        Log.d(TAG, "onResume()")

        super.onResume()

        maybeEnableArButton()
    }

    fun btnGetStartedClicked(view: View)
    {
        Log.d(TAG, "btnGetStartedClicked()")

        startMapActivity("")
    }

    fun textToSpeechClick(view: View)
    {
        Log.d(TAG, "textToSpeechClick()")

        if (ContextCompat.checkSelfPermission(this@MainActivity,RECORD_AUDIO) !==PackageManager.PERMISSION_GRANTED)
        {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity,RECORD_AUDIO))
            {
                ActivityCompat.requestPermissions(this@MainActivity,arrayOf(RECORD_AUDIO), 1)
            }
            else
            {
                ActivityCompat.requestPermissions(this@MainActivity,arrayOf(RECORD_AUDIO), 1)
            }
        }
        else
        {
            Toast.makeText(this, "Permission already granted", Toast.LENGTH_SHORT).show()
            askSpeechInput()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        Log.d(TAG, "onActivityResult()")

        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == speechRec && resultCode == Activity.RESULT_OK)
        {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

            manipulateText(result?.get(0).toString())
        }
    }

    private fun manipulateText(showText:String)
    {
        Log.d(TAG, "manipulateText()")

        val schoolList = resources.getStringArray(R.array.schoolList)
        val scis2soeGsrList = resources.getStringArray(R.array.scis2soeGsrList)
        val scis1GsrList = resources.getStringArray(R.array.scis1GsrList)

        var editedSpokenText = showText.replace(" ", "")
        var capsSpokenEditedText = editedSpokenText.uppercase()
        var schStatus = false
        var gsrStatus = false
        var venue = ""

        for (sch in schoolList)
        {
            if (sch == "SCIS 2/SOE")
            {
                var newSchList = sch.split("/")
                for (newSch in newSchList)
                {
                    var editedSch = newSch.replace(" ", "")

                    if (capsSpokenEditedText.contains(editedSch))
                    {
                        schStatus = true

                        for (gsr in scis2soeGsrList)
                        {
                            var newGsr = gsr.replace(" ", "")
                            var newGsr2 = newGsr.replace("-", "")

                            if (capsSpokenEditedText.contains(newGsr2))
                            {
                                venue = "$sch $gsr"
                                gsrStatus = true
                            }
                        }
                    }
                }
            }
            else if (sch == "SCIS 1")
            {
                var editedSch = sch.replace(" ", "")
                if (capsSpokenEditedText.contains(editedSch))
                {
                    schStatus = true

                    for (gsr in scis1GsrList)
                    {
                        var newGsr = gsr.replace(" ", "")
                        var newGsr2 = newGsr.replace("-", "")

                        if (capsSpokenEditedText.contains(newGsr2))
                        {
                            venue = "$sch $gsr"
                            gsrStatus = true
                        }
                    }
                }
            }
        }

        if (!schStatus or !gsrStatus)
        {
            Toast.makeText(this, "No such school/gsr, please try again.", Toast.LENGTH_SHORT).show()
        }

        if (schStatus && gsrStatus)
        {
            Toast.makeText(this, "School exists! Opening AR", Toast.LENGTH_SHORT).show()
            startMapActivity(venue)
        }
    }

    private fun askSpeechInput()
    {
        Log.d(TAG, "askSpeechInput()")

        if (!SpeechRecognizer.isRecognitionAvailable(this))
        {
            Toast.makeText(this, "Speech recognition is not available", Toast.LENGTH_SHORT).show()
        }
        else
        {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Something!")
            startActivityForResult(i, speechRec)
        }
    }
}