# GsrFinder
SMU Gsr Finder  App

# Pre-requsite
Enable ARCore API and ARCore Cloud Anchor API

# API Keys
You will need to create your APIs and store the API keys in your ```local.properties``` file. 

MAPS_API_KEY=<Replace with your api key>
ARCORE_API_KEY=<Replace with your api key>

# Firebase Configuration
https://firebase.google.com/

1. Login with your google account
2. Create a new project
3. Enter your project name
4. Select a default account for firebase
5. Select Android App to get started
6. Under Register App, add your android package name e.g. sg.edu.smu.gsrfinder
7. Download the google-services.json and add it to your module (app-level) root directory
8. Follow the instructions provided by firebase
9. Click on Realtime Database and select Create Database
10. Select Singapore as the Realtime Database location
11. Edit the rules to True for both read and write
12. Note the link in data and edit the google-services.json
```"project_info": {
    "firebase_url": <Replace with your link found in Realtime Database data here>,
  },```


