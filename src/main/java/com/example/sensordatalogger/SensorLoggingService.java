package com.example.sensordatalogger;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Calendar;


public class SensorLoggingService extends Service implements SensorEventListener {
    private static final String TAG = "MONITORING_SEVICE";
    private SensorManager sensorManager;
    private Timer timer;
    private Handler handler;
    private static final String CHANNEL_ID = "SensorLoggingServiceChannel";
    private String packageName = "";
    private int microphoneCount = 0;
    private HandlerThread handlerThread;
    StringBuilder sb = new StringBuilder();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
    private volatile boolean isRunning = true;
    private final Map<String, String> pidToAppNameMap = new HashMap<>();
    private Process logcatProcess;
    private int locationAccessCount = 0;
    private int cameraAccessCount = 0;
    private int networkUsageCount = 0;
    private int galleryAccessCount = 0;
    private int fileAccessCount = 0;
    private int bluetoothCount = 0;
    private int contactsAccessCount=0;
    private int wifiCount = 0;
    private int biometricAccessCount = 0;
    private int dnsCount = 0;
    private static boolean touchCount = false;
    private static boolean swipeCount = false;
    private static boolean sideButtonCount = false;
    private boolean isTouching = false;
    private int eventLineCount = 0;
    private static final int SWIPE_THRESHOLD = 15;
    private int xCoordi, yCoordi;
    private float[] lastAccelerometer = new float[3];
    private float[] lastGyroscope = new float[3];
    private long totalSentPackets = 0;
    private long totalReceivedPackets = 0;
    private float lastLightValue = 0;
    private float lastProximityValue = 0;
    private long prevTxBytes = 0;
    private long prevRxBytes = 0;
    private volatile boolean isMonitoring = true;
    private int screenStatus = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        Toast.makeText(this, "Sensor Logging Started", Toast.LENGTH_SHORT).show();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        handlerThread = new HandlerThread("LogCheckHandlerThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        initializeSensors();
        startSensorNetworkMonitoring();
        startRepeatingTaskInThread();
        startForegroundService();
        startMonitoringInputEvents();
        startNetworkLogsMonitoring();
        startScreenMonitoring();
    }

    private Runnable runnableCode = new Runnable() {
        @Override
        public void run() {
            try {
                packageName = checkCameraLog();
                if (!packageName.equals("")) {
                    sb.append(" Camera: ").append(packageName);
                    cameraAccessCount++;
                }
                packageName = checkAudioLog();
                if (!packageName.equals("")) {
                    sb.append(" Microphone: " + packageName);
                    microphoneCount++;
                }
                packageName = checkBluetoothLog();
                if (!packageName.equals("")) {
                    sb.append(" Bluetooth: " + packageName);
                    bluetoothCount++;
                }
                packageName = checkContactsAccess();
                if (!packageName.equals("")) {
                    sb.append(" Contacts: " + packageName);
                    contactsAccessCount++;
                }
                wifiCount = checkWifiLog();
                if (wifiCount == 0) {
                }
                packageName = checkLocationLog();
                if (!packageName.equals("")) {
                    sb.append(" Location: " + packageName);
                    locationAccessCount++;
                }
                handler.postDelayed(this, 5000);
            } catch (Exception e) {
                Log.e(TAG, "Error in runnableCode", e);
            }
        }
    };

    private void startSensorNetworkMonitoring() {
        startPeriodicLogging();
        startPeriodicProcessStatus();
        startLogcatMonitoring();
    }

    public void startRepeatingTaskInThread() {
        handlerThread = new HandlerThread("LogCheckHandlerThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        // Start the runnable task
        handler.post(runnableCode);
    }
    private String extractPIDFromLogLine(String logLine) {
        String[] parts = logLine.trim().split("\\s+");
        if (parts.length >= 3) {
            return parts[2];
        } else {
            Log.e("ExtractPID", "Failed to extract PID from log line: " + logLine);
            return "";
        }
    }
    private void startScreenMonitoring() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenStatus = 1;
                } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenStatus = 0;
                }
            }
        }, filter);
    }
    private void startPeriodicLogging() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                handler.post(() -> {
                    logStatus();
                });
            }
        }, 0, 1000);  // 1-second interval
    }
    private void startPeriodicProcessStatus() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                handler.post(() -> {
                    getAppNameByPid();
                });
            }
        }, 0, 5000);     //  5 seconds
    }

    private void startNetworkLogsMonitoring() {
        // Initialize previous traffic stats
        prevTxBytes = TrafficStats.getTotalTxBytes();
        prevRxBytes = TrafficStats.getTotalRxBytes();

        if (prevTxBytes == TrafficStats.UNSUPPORTED || prevRxBytes == TrafficStats.UNSUPPORTED) {
            Log.e(TAG, "TrafficStats is unsupported on this device.");
            return;
        }
        new Thread(() -> {
            while (isMonitoring) {
                try {
                    Thread.sleep(1000); // Monitor every 1 second

                    long currentTxBytes = TrafficStats.getTotalTxBytes();
                    long currentRxBytes = TrafficStats.getTotalRxBytes();

                    if (currentTxBytes != TrafficStats.UNSUPPORTED && currentRxBytes != TrafficStats.UNSUPPORTED) {
                        long sentBytes = currentTxBytes - prevTxBytes;
                        long receivedBytes = currentRxBytes - prevRxBytes;

                        totalSentPackets += sentBytes;
                        totalReceivedPackets += receivedBytes;

                        prevTxBytes = currentTxBytes;
                        prevRxBytes = currentRxBytes;

                        Log.d(TAG, "Network Traffic - Sent: " + sentBytes + " bytes, Received: " + receivedBytes + " bytes");
                    } else {
                        Log.e(TAG, "TrafficStats values are unsupported.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Network monitoring thread interrupted", e);
                }
            }
        }).start();
    }
        private void logStatus() {
        @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK);
        String timestamp = sdf.format(new Date());
        @SuppressLint("HardwareIds") String deviceID = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

            int batteryLevel = getBatteryLevel();
            int usedMemory = getMemoryUsagePercentage();

        @SuppressLint("DefaultLocale") String status =  String.format("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d",
                timestamp, deviceID,touchCount ? 1 : 0, swipeCount ? 1 : 0, xCoordi, yCoordi, sideButtonCount ? 1 : 0, wifiCount, networkUsageCount,
                bluetoothCount, screenStatus, locationAccessCount, cameraAccessCount, microphoneCount,fileAccessCount,  galleryAccessCount,
                contactsAccessCount, dnsCount, biometricAccessCount, sb, lastGyroscope[0], lastGyroscope[1], lastGyroscope[2],
                lastAccelerometer[0], lastAccelerometer[1], lastAccelerometer[2], lastProximityValue, lastLightValue,
                totalSentPackets, totalReceivedPackets, batteryLevel, usedMemory);
        File logFile = new File(getExternalFilesDir(null), "Sensor_logs.csv");
        boolean isNewFile = !logFile.exists();
        try {
            FileWriter writer = new FileWriter(logFile, true);
            if (isNewFile) {
                writer.append("Timestamp, Device ID, TouchCount, SwipeCount, xCoordi, yCoordi, sideButtonCount, WifiCount, InternetCount, BluetoothCount, " +
                        "ScreenOn, LocationCount, CameraCount, MicrophoneCount, FileCount, GalleryCount, ContactsCount, DNSCount, BiometricCount," +
                        " permission usage order, GyroX, GyroY, GyroZ, AccX, AccY, AccZ, Proximity, Light, TotalSentPackets, TotalReceivedPackets, BatteryUsage, MemoryUsage\n");
            }
            writer.append(status).append("\n");
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        resetCount();
    }
    private void resetCount()
    {
        touchCount = false;
        swipeCount = false;
        xCoordi = -1;
        yCoordi = -1;
        sideButtonCount = false;
        totalSentPackets = 0;
        totalReceivedPackets = 0;
        biometricAccessCount =0;
        dnsCount=0;
        wifiCount=0;
        cameraAccessCount=0;
        microphoneCount=0;
        galleryAccessCount=0;
        fileAccessCount=0;
        networkUsageCount=0;
        locationAccessCount=0;
        bluetoothCount=0;
        contactsAccessCount=0;
        sb.setLength(0);
        sb.append(" ");
    }

    private int getBatteryLevel() {
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        return batteryManager != null ? batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    private int getMemoryUsagePercentage() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        // Calculate the percentage of memory used
        long usedMemory = memoryInfo.totalMem - memoryInfo.availMem; // Used memory in bytes
        int memoryUsagePercentage = (int) ((usedMemory * 100) / memoryInfo.totalMem); // Integer percentage

        return memoryUsagePercentage;
    }
    private void startLogcatMonitoring() {
        Thread monitorer = new Thread(() -> {
            try {
                Process logcatProcess = Runtime.getRuntime().exec("su -c logcat");
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    String pidAdhoc = extractPIDFromLogLine(line);
                    if (line.contains("AudioFlinger")) {
                        sb.append(" Microphone: " + packageName);
                        microphoneCount++;
                    }
                    if (line.contains("Shutter")) {
                        sb.append(" Camera: " + packageName);
                        cameraAccessCount++;
                    }
                    if (line.contains("Slocation") || line.contains("NSLocationMonitor")) {
                        sb.append(" Location: " + packageName);
                        locationAccessCount++;
                    }
                    packageName = pidToAppNameMap.getOrDefault(pidAdhoc, "unknown");
                    if (line.contains("MediaProvider")) {
                        sb.append(" Gallery: " + packageName);
                        galleryAccessCount++;
                    }
                    else if (line.contains("open()") || line.contains("ContentProvider")) {
                        sb.append(" File: " + packageName);
                        fileAccessCount++;
                    }else if (line.contains("FingerprintService") || line.contains("BiometricsManager") || line.contains("FingerprintManager")) {
                        // Check for success indicators or successful biometric events
                        if (line.contains("handleMessage = 102")) {  // Success event
                            sb.append(" Biometric Success: " + packageName);
                            biometricAccessCount++;  // Increment count only for successful biometric events
                        } else {
                            sb.append(" Biometric Event: " + packageName);
                        }
                    }
                    else if (line.contains("DNS") || line.contains("getaddrinfo")) {
                        sb.append(" DNS: " + packageName);
                        dnsCount++;
                    }
                    if (line.contains("Http") || line.contains("wifi") || line.contains("Socket")) {
                        sb.append(" Internet: " + packageName);
                        networkUsageCount++;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        monitorer.setPriority(Thread.MAX_PRIORITY);
        monitorer.start();
    }
    private void getAppNameByPid() {
        Thread topThread = new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec("su -c ps");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
//                    Log.v("top line", line);
                    String[] splitLine = line.trim().split("\\s+");

                    if (splitLine.length > 1) {
                        String pid = splitLine[1]; // Extract PID
                        String appName = splitLine[splitLine.length - 1]; // Extract App Name
                        pidToAppNameMap.put(pid, appName); // Store in map
                    }
                }
                reader.close();
                process.waitFor();

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        topThread.setPriority(Thread.MIN_PRIORITY);
        topThread.start();
    }
    public String checkContactsAccess() {
        try {
            // Run the dumpsys appops command
            Process process = Runtime.getRuntime().exec("su -c dumpsys appops");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String currentPackageName = "";  // To store the current package name being processed
            String currentPermission = "";   // To track which permission block we are inside
            boolean insidePermissionBlock = false;  // Track if we are inside a permission block
            long currentTime = System.currentTimeMillis(); // Get current system time in milliseconds

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Look for lines with "Package <package_name>:" to extract package names
                if (line.startsWith("Package")) {
                    currentPackageName = line.split(" ")[1].replace(":", "").trim();  // Extract package name
                    Log.i("contact package", currentPackageName);
                    continue;
                }
                // Check for the start of a permission block such as "READ_CALL_LOG (allow):"
                if (line.matches("(READ_CONTACTS|WRITE_CONTACTS|READ_CALL_LOG|WRITE_CALL_LOG|READ_SMS|WRITE_SMS|RECEIVE_SMS|SEND_SMS|READ_PHONE_STATE|PROCESS_OUTGOING_CALLS|READ_PHONE_NUMBERS|MANAGE_ONGOING_CALLS).*\\(allow\\):")) {
//                    currentPermission = line.split(" ")[0].trim();  // Extract the permission name
                    insidePermissionBlock = true;  // Enter permission block
                    continue;
                }

                // If we're inside a permission block, check for Access timestamps
                if (insidePermissionBlock) {
                    if (line.startsWith("Access:")) {
//                         Extract the timestamp after "Access:"
                        String logTimestamp = line.split(" ")[2].trim() + " " + line.split(" ")[3].trim();
                        Log.i("contacts timestamp", logTimestamp);
                        SimpleDateFormat sdf10 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");  // Updated pattern to include milliseconds
                        Date logDate = sdf10.parse(logTimestamp);  // Parse the timestamp including milliseconds
                        long logTimeInMillis = logDate.getTime();

                        if ((currentTime - logTimeInMillis) <= 5000) {
//                            System.out.println("Permission " + currentPermission + " accessed by: " + currentPackageName);
                            Log.i("contact final package", currentPackageName);
                            sb.append(" Contacts: " + currentPackageName);
                            contactsAccessCount++;
                        }
                    }
                    // End of the permission block (when another permission starts or when we encounter a null or other break)
                    if (line.equals("]") || line.isEmpty() || line.startsWith("Package")) {
                        insidePermissionBlock = false;
                        currentPermission = "";  // Reset the permission tracking
                    }
                }
            }

            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    public String checkLocationLog() {
        try {
            Process process = Runtime.getRuntime().exec("su -c dumpsys location");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            long currentTime = System.currentTimeMillis();

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                if (line.contains("passive provider") || line.contains("network provider") || line.contains("gps provider")) {
                    try {
                        if (line.length() >= 23) {
                            String logTimestamp = line.substring(0, 22);
                            String currentYear = "2025-";
                            SimpleDateFormat sdf3 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                            Date logDate = sdf3.parse(currentYear + logTimestamp.trim());
                            long logTimeInMillis = logDate.getTime();

                            if ((currentTime - logTimeInMillis) <= 5000) {
                                String[] splitLine = line.split("/");
                                if (splitLine.length > 1) {
                                    packageName = splitLine[1].trim();
                                    sb.append(" Contacts: " + packageName);
                                    return packageName;
                                }
                            }
                        }
                    } catch (ParseException e) {
                        Log.e("Parsing Error", "Error parsing date: " + line);
                        e.printStackTrace();
                    }
                }
            }

            Log.e("Location Dump Output", output.toString());

            reader.close();
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    public String checkCameraLog() {
        try {
            Process process = Runtime.getRuntime().exec("su -c dumpsys media.camera | grep \"client\"");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            long currentTime = System.currentTimeMillis();
            Calendar currentCalendar = Calendar.getInstance();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

            while ((line = reader.readLine()) != null) {
                Log.e("camera dump", line);

                // Check if log contains camera activity
                if (line.contains("CONNECT") || line.contains("DISCONNECT") || line.contains("EVICT")) {

                    if (line.length() < 18) continue; // Prevent index errors

                    // Extract only the time part (HH:mm:ss.SSS)
                    String logTimeString = line.substring(6, 18).trim();

                    Date logTime;
                    try {
                        logTime = timeFormat.parse(logTimeString);
                    } catch (ParseException e) {
                        continue; // Skip this log entry if timestamp parsing fails
                    }

                    // Convert log time to milliseconds
                    Calendar logCalendar = Calendar.getInstance();
                    logCalendar.setTime(logTime);

                    // Ensure log time belongs to today or handle midnight rollover
                    logCalendar.set(Calendar.YEAR, currentCalendar.get(Calendar.YEAR));
                    logCalendar.set(Calendar.MONTH, currentCalendar.get(Calendar.MONTH));
                    logCalendar.set(Calendar.DAY_OF_MONTH, currentCalendar.get(Calendar.DAY_OF_MONTH));

                    long logTimeInMillis = logCalendar.getTimeInMillis();
                    long difference = currentTime - logTimeInMillis;

                    // Handle cases where log might be from the previous day
                    if (difference < 0) {
                        logCalendar.add(Calendar.DAY_OF_MONTH, -1);
                        logTimeInMillis = logCalendar.getTimeInMillis();
                        difference = currentTime - logTimeInMillis;
                    }

                    // Check if log entry is within the last 5 seconds
                    if (difference <= 5000) {
                        String[] splitLine = line.split("package ");
                        if (splitLine.length > 1) {
                            String packageName = splitLine[1].split(" ")[0].trim(); // Extract package name
                            System.out.println("camera: " + packageName);
                            sb.append(" Contacts: " + packageName);
                            //cameraAccessCount++;
                            return packageName;
                        }
                    }
                }
            }

            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    private void startMonitoringInputEvents() {
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec("su -c getevent");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;

                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, "Event: " + line);

                    String[] parts = line.split(" ");
                    String eventCode = parts[2];
                    Log.d(TAG, "EventCode: " + eventCode);

                    String eventValue = parts[3];
                    Log.d(TAG, "EventValue: " + eventValue);

                    if ("0035".equals(eventCode) || "0036".equals(eventCode)) {
                        if (!isTouching) {
                            touchCount = true;
                            isTouching = true;
                        }
                        if ("0035".equals(eventCode)) {
                            int xPoint = hexToDecimal(eventValue);
                            Log.w("xPoint", String.valueOf(xPoint));
//                            xCoordi = screenTouchPointsX(xPoint);
                            xCoordi = xPoint;
                            Log.e("xCoordi", String.valueOf(xCoordi));
                            if (xCoordi != -1) {
                                if (eventLineCount > SWIPE_THRESHOLD) {
                                    swipeCount = true;
                                } else {
                                    touchCount = true;
                                }
                            }

                        } else if ("0036".equals(eventCode)) {
                            int yPoint = hexToDecimal(eventValue);
                            Log.w("yPoint", String.valueOf(yPoint));
//                            yCoordi = screenTouchPointsY(yPoint);
                            yCoordi = yPoint;
                            if (yCoordi != -1) {
                                if (eventLineCount > SWIPE_THRESHOLD) {
                                    swipeCount = true;
                                } else {
                                    touchCount = true;
                                }
                            }
                            Log.e("yCoordi", String.valueOf(yCoordi));

                        }
                    } else if ("0039".equals(eventCode)) {
                        if (!isTouching) {
                            isTouching = true;
                            eventLineCount = 0;
                        } else {
                            isTouching = false;
                            if (eventLineCount > SWIPE_THRESHOLD) {
                                swipeCount = true;
                            } else {
                                touchCount = true;
                            }
                        }
                    } else if (isTouching) {
                        eventLineCount++;
                    }

                    if ("0072".equals(eventCode) || "0073".equals(eventCode) || "0074".equals(eventCode)) {
                        sideButtonCount = true;
                    }
                }
                reader.close();
            } catch (Exception e) {
                Log.e(TAG, "Error reading input events", e);
            }
        }).start();
    }

    private int hexToDecimal(String hexValue) {
        try {
            return Integer.parseInt(hexValue, 16);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error converting hex to decimal: " + hexValue, e);
            return -1; // Default value for invalid input
        }
    }

    public String checkBluetoothLog() {
        try {
            Process process = Runtime.getRuntime().exec("su -c dumpsys bluetooth_manager | grep \"BluetoothAdapter\"");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            long currentTime = System.currentTimeMillis();
            Calendar currentCalendar = Calendar.getInstance();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

            while ((line = reader.readLine()) != null) {
                Log.e("bluetooth dump", line); // Debug log

                if (line.length() < 18) continue; // Prevent index errors

                // Extract only the time part
                String logTimeString = line.substring(6, 18).trim(); // Extracts HH:mm:ss.SSS

                Date logTime;
                try {
                    logTime = timeFormat.parse(logTimeString);
                } catch (ParseException e) {
                    continue; // Skip this log entry if timestamp parsing fails
                }

                // Convert log time to milliseconds
                Calendar logCalendar = Calendar.getInstance();
                logCalendar.setTime(logTime);

                // Ensure log time belongs to today or handle midnight rollover
                logCalendar.set(Calendar.YEAR, currentCalendar.get(Calendar.YEAR));
                logCalendar.set(Calendar.MONTH, currentCalendar.get(Calendar.MONTH));
                logCalendar.set(Calendar.DAY_OF_MONTH, currentCalendar.get(Calendar.DAY_OF_MONTH));

                long logTimeInMillis = logCalendar.getTimeInMillis();
                long difference = currentTime - logTimeInMillis;

                // Handle cases where log might be from the previous day
                if (difference < 0) {
                    logCalendar.add(Calendar.DAY_OF_MONTH, -1);
                    logTimeInMillis = logCalendar.getTimeInMillis();
                    difference = currentTime - logTimeInMillis;
                }

                // Check if log entry is within the last 5 seconds
                if (difference <= 5000) {
                    String[] splitLine = line.split("@");
                    System.out.println("bluetooth line: " + splitLine.length);
                    String packageName = (splitLine.length > 1) ? splitLine[1].trim() : "Unknown";
                    sb.append(" Contacts: " + packageName);
                    bluetoothCount++;
                    return packageName; // Return the package name
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    public int checkWifiLog() {
        try {
            // Run the command
            Process process = Runtime.getRuntime().exec("su -c dumpsys wifi | grep \"startConnectivityScan\"");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            long currentTime = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                Log.e("wifi dump", line);
                String logTimestamp = line.substring(0, 26);
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
                Date logDate = sdf2.parse(logTimestamp); // Parse the timestamp
                long logTimeInMillis = logDate.getTime();

                if ((currentTime - logTimeInMillis) <=5000) {
                    String[] splitLine = line.split(" "); // Adjust split as needed based on actual log format
                    if (splitLine.length > 1) {
                        Log.d("wifi value", "1");
                        wifiCount = 1;
                        return 1; // You can modify this to return specific information if required
                    }
                }
            }

            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public  String checkAudioLog() {
        try {
            // Run the command
            Process process = Runtime.getRuntime().exec("su -c dumpsys audio | grep \"pack:\" ");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            long currentTime = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                Log.e("audio usage", line);
                String logTimestamp = line.substring(0, 18);
                Date logDate = sdf.parse("2025-" + logTimestamp); // Parse the timestamp
                long logTimeInMillis = logDate.getTime();
                System.out.println(logTimeInMillis);
                System.out.println(currentTime - logTimeInMillis);
                if ((currentTime - logTimeInMillis) <= 5000) {
                    String[] splitLine = line.split("pack:");
                    if (splitLine.length > 1) {
                        String packageName = splitLine[1].trim();
                        sb.append(" Contacts: " + packageName);
                        microphoneCount++;
                        return packageName;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    private void startForegroundService() {
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Logging Service")
                .setContentText("Logging sensor data in the background")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
        startForeground(1, notification);
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sensor Logging Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
    private void initializeSensors() {
        // Register sensors
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        if (lightSensor != null) sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (proximitySensor != null) sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.length);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            System.arraycopy(event.values, 0, lastGyroscope, 0, event.values.length);
        } else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lastLightValue = event.values[0];
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            lastProximityValue = event.values[0];
        }
    }
    private float round(float value) {
        return Math.round(value * 100) / 100.0f;
    }
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override
    public void onDestroy() {
        super.onDestroy();
        isMonitoring= false;
        timer.cancel();
        handler.removeCallbacksAndMessages(null);
        sensorManager.unregisterListener(this);
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
