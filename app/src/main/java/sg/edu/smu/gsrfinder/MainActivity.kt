package sg.edu.smu.gsrfinder

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity()
{
    private lateinit var spinFrom: String;
    private lateinit var spinToSchool: String;
    private lateinit var spinToRoom: String;

    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.d("MainActivity", "onCreate()");
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSpinFrom(true);
        initSpinToSchool();
        initSpinToRoom("SCIS 1");
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

        //Get current location
        //Check if user is in SCIS
        //if Yes call -> initSpinFrom(true)
        //else -> initSpinFrom(false)
        if (ContextCompat.checkSelfPermission(this@MainActivity,
                ACCESS_FINE_LOCATION) !==
            PackageManager.PERMISSION_GRANTED)
        {
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

            if (checkSelfPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            {
                //Location request granted
                Toast.makeText(this, "Granted", Toast.LENGTH_SHORT).show()
            }
        }
        else
        {
            //Location request already granted for the app
            Toast.makeText(this, "already granted", Toast.LENGTH_SHORT).show()

        }


        initSpinFrom(false);
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
    fun btnGetStartedClicked(view: View)
    {
        Log.d("MainActivity", "btnGetStartedClicked()");

        Log.d("MainActivity", "btnGetStartedClicked() - spinFrom: $spinFrom");
        Log.d("MainActivity", "btnGetStartedClicked() - spinToSchool: $spinToSchool");
        Log.d("MainActivity", "btnGetStartedClicked() - spinToRoom: $spinToRoom");


        /* TODO */
        //If user is in SCIS, dependent on floor -> Use Anchor cloud, launch camera and show steps
        //If user is not in SCIS -> Use geospatial, direct user to SCIS first -> then use anchor cloud
        //get user from spinToSchool
        //get user from spinToRoom



    }

}