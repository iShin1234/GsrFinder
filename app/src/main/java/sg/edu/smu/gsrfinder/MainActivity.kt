package sg.edu.smu.gsrfinder

import android.Manifest.permission.ACCESS_FINE_LOCATION
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
    private lateinit var spinFrom: String;
    private lateinit var spinToSchool: String;
    private lateinit var spinToRoom: String;
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView;
    private lateinit var actionBarDrawerToggle: ActionBarDrawerToggle

    // speech to text
    private val RQ_SPEECH_REC = 102

    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.d("MainActivity", "onCreate()");
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // drawer layout instance to toggle the menu icon to open
        // drawer and back button to close drawer
        drawerLayout = findViewById<DrawerLayout>(R.id.my_drawer_layout)
        actionBarDrawerToggle = ActionBarDrawerToggle(this, drawerLayout, R.string.nav_open, R.string.nav_close)

        // pass the Open and Close toggle for the drawer layout listener
        // to toggle the button
        drawerLayout.addDrawerListener(actionBarDrawerToggle)
        actionBarDrawerToggle.syncState()

        // to make the Navigation drawer icon always appear on the action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navigationView = findViewById<NavigationView>(R.id.naviationView)
        navigationView.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.admin -> {
                    // handle click
                    Log.d("CHECK", "IN")
                    AdminClick()
                    true
                }
                else -> false
            }
        }

        initSpinFrom(true);
        initSpinToSchool();
        initSpinToRoom("SCIS 1");
        maybeEnableArButton();
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (actionBarDrawerToggle.onOptionsItemSelected(item)) {
            true
        } else {
            super.onOptionsItemSelected(item)
            }
        }

    /*
     *  1. <if> User is in SCIS, Get the gsr list from values/spinner_lists.xml
     *  2. Create a spinner adapter with the gsr list data
     *  3. <else> User is not in SCIS, Get the gsr list from values/spinner_lists.xml
     *  4. Bind front end spinner with id spinFrom to spinAdapter
     *  5. Initialise onItemSelectedListener
     */
    private fun initSpinFrom(inSCIS:Boolean)
    {
        Log.d("MainActivity", "initSpinFrom()");

        val list: Array<String>;

        //If user is in Scis, user has to select which level he is in
        if(inSCIS)
        {
            list = resources.getStringArray(R.array.scisList);
        }
        //Else just show Current Location and allow google map to from user to SCIS (Assume he will reach B1)
        else
        {
            list = resources.getStringArray(R.array.currentLocationList);
        }

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, list);
        val spinFrom = findViewById<Spinner>(R.id.spinFrom)
            spinFrom.adapter = spinAdapter

        spinFrom.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d("MainActivity", "initSpinFrom() - onItemSelected()");
                val selectedString = parent!!.getItemAtPosition(index).toString();
                this@MainActivity.spinFrom = selectedString;

                /* TODO */
                //If user is not in SCIS
                //Prepare to use GeoSpatial API to direct user to school
                //Else prepare to use Cloud Anchor API to direct user to GSR

            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("MainActivity", "initSpinFrom() - onNothingSelected()");
            }
        }
    }

    /*
     *  1. Request for Location Permission
     *  2. Get Current Location
     *  3. Update spinFrom Adapter
     */
    fun clickAllowLocation(view: View)
    {
        Log.d("MainActivity", "clickAllowLocation()");

        requestLocationPermission();
    }

    private fun requestLocationPermission()
    {
        //Get current location
        //Check if user is in SCIS
        //if Yes call -> initSpinFrom(true)
        //else -> initSpinFrom(false)
        if (ContextCompat.checkSelfPermission(this@MainActivity,
                ACCESS_FINE_LOCATION) !==
            PackageManager.PERMISSION_GRANTED)
        {
            if (checkSelfPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            {
                //Location request granted
                Toast.makeText(this, "Requesting Permission", Toast.LENGTH_SHORT).show()
            }

            if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity,
                    ACCESS_FINE_LOCATION))
            {
                ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(ACCESS_FINE_LOCATION), 1)
            }
            else
            {
                ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(ACCESS_FINE_LOCATION), 1)
            }
        }
        else
        {
            //Location request already granted for the app
            Toast.makeText(this, "Permission already granted", Toast.LENGTH_SHORT).show()

        }


        initSpinFrom(true);
    }

    /*
     *  1. Show all schools in smu
     */
    private fun initSpinToSchool()
    {
        Log.d("MainActivity", "initSpinToSchool()");

        val list: Array<String>;

        val schoolList = resources.getStringArray(R.array.schoolList);

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, schoolList);
        val spinToSchool = findViewById<Spinner>(R.id.spinToSchool)
        spinToSchool.adapter = spinAdapter

        spinToSchool.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d("MainActivity", "initSpinToSchool() - onItemSelected()");
                val selectedString = parent!!.getItemAtPosition(index).toString();

                this@MainActivity.spinToSchool = selectedString;

                initSpinToRoom(selectedString);
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("MainActivity", "initSpinToSchool() - onNothingSelected()");
            }
        }
    }

    /*
     *  1. Show all gsr that belongs to the particular school
     */
    private fun initSpinToRoom(school: String)
    {
        Log.d("MainActivity", "initSpinToRoom()");

        when(school)
        {
            "SCIS 1" ->
            {
                Log.d("MainActivity", "initSpinToRoom() - User Selected SCIS 1");
                showRoom(resources.getStringArray(R.array.scis1GsrList));
            }
            "SCIS 2/SOE" ->
            {
                Log.d("MainActivity", "initSpinToRoom() - User Selected SCIS 2/SOE");
                showRoom(resources.getStringArray(R.array.scis2soeGsrList));
            }
        }
    }

    private fun showRoom(gsrList: Array<String>)
    {
        Log.d("MainActivity", "showRoom()");

        val spinAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, gsrList);
        val spinToRoom = findViewById<Spinner>(R.id.spinToRoom)
        spinToRoom.adapter = spinAdapter

        spinToRoom.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
        {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long)
            {
                Log.d("MainActivity", "showRoom() - onItemSelected()");
                val selectedString = parent!!.getItemAtPosition(index).toString();

                this@MainActivity.spinToRoom = selectedString;
            }
            override fun onNothingSelected(p0: AdapterView<*>?)
            {
                Log.d("MainActivity", "showRoom() - onNothingSelected()");
            }
        }
    }

    /*
     * 1. Begin navigation
     */
    fun AdminClick()
    {
        Log.d("MainActivity", "btnGetStartedClicked()");

        Log.d("MainActivity", "btnGetStartedClicked() - spinFrom: $spinFrom");
        Log.d("MainActivity", "btnGetStartedClicked() - spinToSchool: $spinToSchool");
        Log.d("MainActivity", "btnGetStartedClicked() - spinToRoom: $spinToRoom");

        //Start ArActivity Intent
        val arIntent = Intent(this, CloudAnchorActivity::class.java)
        startActivity(arIntent)

    }

    fun startMapActivity()
    {
        if (checkSelfPermission(ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED)
        {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            fusedLocationClient.lastLocation.addOnSuccessListener(this, OnSuccessListener { location: Location? ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude

                    Log.d("MainActivity", "btnGetStartedClicked() - lat: $lat");
                    Log.d("MainActivity", "btnGetStartedClicked() - lon: $lon");

                    getLocationByLatLong(lat, lon);
                }
            })

        }
        else
        {
            //Location request already granted for the app
            Toast.makeText(this, "Please request for location", Toast.LENGTH_SHORT).show()
            requestLocationPermission();
        }
    }

    private fun getLocationByLatLong(lat: Double, lon: Double)
    {
        val geocoder = Geocoder(this, Locale.getDefault())
        val addresses: List<Address> = geocoder.getFromLocation(lat, lon, 1) as List<Address>
        val address: String = addresses[0].getAddressLine(0)
        Log.d("MainActivity", "getLocationByLatLong() - address: $address");

        var currentLocation = Location("")
        currentLocation.latitude = lat
        currentLocation.longitude = lon

        var distanceFromCurrentLocationToLandMark: Float? = null;
//        var url = "";

        if(spinToSchool == "SCIS 1")
        {
            //SCIS 1
            var scisLat = 1.297465
            var scisLong = 103.8495169

            var scisLandMark = Location("")
            scisLandMark.latitude = scisLat
            scisLandMark.longitude = scisLong

            //This distance is in meter
            distanceFromCurrentLocationToLandMark = currentLocation.distanceTo(scisLandMark)

//            url = "http://maps.googleapis.com/maps/api/directions/xml?origin=$lat,$lon&destination=$scisLat,$scisLong&sensor=false&units=metric&mode=walking";

            Log.d("MainActivity", "getLocationByLatLong() - distance: $distanceFromCurrentLocationToLandMark");
        }
        else if(spinToSchool == "SCIS 2/SOE")
        {
            //SCIS 2/SOE
            var scisLat = 1.2977584
            var scisLong = 103.8486792

            var scisLandMark = Location("")
            scisLandMark.latitude = scisLat
            scisLandMark.longitude = scisLong

            //This distance is in meter
            distanceFromCurrentLocationToLandMark = currentLocation.distanceTo(scisLandMark)

//            url = "http://maps.googleapis.com/maps/api/directions/xml?origin=$lat,$lon&destination=$scisLat,$scisLong&sensor=false&units=metric&mode=walking";

            Log.d("MainActivity", "getLocationByLatLong() - distance: $distanceFromCurrentLocationToLandMark");
        }

        Log.d("MainActivity", "getLocationByLatLong() - distance: $distanceFromCurrentLocationToLandMark");
//        val mapIntent = Intent(this, MapsActivity::class.java)
//        startActivity(mapIntent)
        if(distanceFromCurrentLocationToLandMark!! < 50)
        {
            //You are in the building, launch AR
            //Start ArActivity Intent
            val myIntent = Intent(this, UserCloudAnchorActivity::class.java)
            myIntent.putExtra("location", "$spinToSchool $spinToRoom")
            startActivity(myIntent)
        }
        else
        {
            //else
            //You are not in the building, direct user to the building first
            //Start MapActivity Intent
            val mapIntent = Intent(this, MapsActivity::class.java)
            mapIntent.putExtra("location", "$spinToSchool");
            mapIntent.putExtra("lat", lat);
            mapIntent.putExtra("lon", lon);
            startActivity(mapIntent);

//            val newMapIntent = Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
//            startActivity(newMapIntent)
        }
    }

    fun maybeEnableArButton()
    {
        Log.d("MainActivity", "maybeEnableArButton()");
        val availability = ArCoreApk.getInstance().checkAvailability(this@MainActivity)
        if (availability.isTransient)
        {
            // Continue to query availability at 5Hz while compatibility is checked in the background.
            Handler(Looper.getMainLooper()).postDelayed({
                // Your Code
                Log.d("MainActivity", "maybeEnableArButton() - AR Transient");
                maybeEnableArButton()
            }, 200)
        }
        val myArButton = findViewById<Button>(R.id.btnGetStarted)
        if (availability.isSupported)
        {
            Log.d("MainActivity", "maybeEnableArButton() - AR Supported");
//            myArButton.visibility = View.VISIBLE
            myArButton.isEnabled = true
        }
        else
        {
            Log.d("MainActivity", "maybeEnableArButton() - AR Not Supported");
            // The device is unsupported or unknown.
//            myArButton.visibility = View.INVISIBLE
            findViewById<TextView>(R.id.tvdisclaimer).text = "Please install Google Play Service for AR";
            myArButton.isEnabled = false
            setupAR();
        }
    }

    // requestInstall(Activity, true) will triggers installation of
    // Google Play Services for AR if necessary.
    var mUserRequestedInstall = true

    fun setupAR()
    {
        // Check camera permission.
        if (!CameraPermissionHelper.hasCameraPermission(this))
        {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                .show()
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        // Ensure that Google Play Services for AR and ARCore device profile data are
        // installed and up to date.
        try {
//            var mSession: Session? = null;
//            if (mSession == null) {
                when (ArCoreApk.getInstance().requestInstall(this, mUserRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        Log.d("MainActivity", "setupAR() - AR Installed");
//                        // Success: Safe to create the AR session.
//
//                        mSession = Session(this);
//
//                        val config = Config(mSession);
//
//                        // Do feature-specific operations here, such as enabling depth or turning on
//                        // support for Augmented Faces.
//                        //https://developers.google.com/ar/develop/cloud-anchors
//                        // Enable Cloud Anchors.
//                        //config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
//
//                        config.geospatialMode = Config.GeospatialMode.ENABLED
//
//                        // Configure the session.
//                        mSession.configure(config);
//
//                        // Release native heap memory used by an ARCore session.
//                        mSession.close()

                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        Log.d("MainActivity", "setupAR() - AR Install Requested");
                        // When this method returns `INSTALL_REQUESTED`:
                        // 1. ARCore pauses this activity.
                        // 2. ARCore prompts the user to install or update Google Play
                        //    Services for AR (market://details?id=com.google.ar.core).
                        // 3. ARCore downloads the latest device profile data.
                        // 4. ARCore resumes this activity. The next invocation of
                        //    requestInstall() will either return `INSTALLED` or throw an
                        //    exception if the installation or update did not succeed.
                        mUserRequestedInstall = false

                        return
                    }
                }
//            }
        }
        catch (e: UnavailableUserDeclinedInstallationException)
        {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "TODO: handle exception " + e, Toast.LENGTH_LONG)
                .show()
            return
        }
    }

    override fun onResume()
    {
        Log.d("MainActivity", "onResume()");
        super.onResume()

        maybeEnableArButton();
    }

    fun btnGetStartedClicked(view: View) {
        Log.d("BTNCLICKSPINTO","IN")
        Log.d("BTNCLICKSPINTO",spinToSchool)
        Log.d("BTNCLICKSPINTO",spinToRoom)


        startMapActivity()
    }

    fun textToSpeechClick(view: View) {
        askSpeechInput()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RQ_SPEECH_REC && resultCode == Activity.RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            manipulateText(result?.get(0).toString())
        }
    }

    private fun manipulateText(showText:String) {
        val schoolList = resources.getStringArray(R.array.schoolList)
        val scis2soeGsrList = resources.getStringArray(R.array.scis2soeGsrList)
        val scis1GsrList = resources.getStringArray(R.array.scis1GsrList)

        // spoken text
        var editedSpokenText = showText.replace(" ", "")
        var capsSpokenEditedText = editedSpokenText.uppercase()

        // status
        var schStatus = false
        var gsrStatus = false

        // where to go
        var venue = ""

        for (sch in schoolList) {

            if (sch == "SCIS 2/SOE") {
                var newSchList = sch.split("/")
                for (newSch in newSchList) {
                    var editedSch = newSch.replace(" ", "")
                    if (capsSpokenEditedText.contains(editedSch)) {
                        schStatus = true
                        // check gsr
                        for (gsr in scis2soeGsrList) {
                            var newGsr = gsr.replace(" ", "")
                            var newGsr2 = newGsr.replace("-", "")

                            Log.d("editedGSR", newGsr2)

                            if (capsSpokenEditedText.contains(newGsr2)) {
                                venue = sch.toString() + " " + gsr.toString()
                                Log.d("VENUE", venue)
                                gsrStatus = true
                            }
                        }
                    }
                }
            }
            else if (sch == "SCIS 1") {
                var editedSch = sch.replace(" ", "")
                if (capsSpokenEditedText.contains(editedSch)) {
                    schStatus = true
                    // check gsr
                    for (gsr in scis1GsrList) {
                        var newGsr = gsr.replace(" ", "")
                        var newGsr2 = newGsr.replace("-", "")

                        Log.d("editedGSR", newGsr2)

                        if (capsSpokenEditedText.contains(newGsr2)) {
                            venue = sch.toString() + " " + gsr.toString()
                            Log.d("VENUE", venue)
                            gsrStatus = true
                        }
                    }
                }
            }
        }

        if (!schStatus or !gsrStatus) {
            Toast.makeText(this, "No such school/gsr, please try again.", Toast.LENGTH_SHORT).show()
        }

        if (schStatus && gsrStatus) {
            Toast.makeText(this, "School exists!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askSpeechInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available", Toast.LENGTH_SHORT).show()
        }
        else {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Something!")
            startActivityForResult(i, RQ_SPEECH_REC)
        }
    }
}