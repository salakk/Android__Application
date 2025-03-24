package com.example.sensordatalogger;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private Button statusButton;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start the sensor logging service automatically
        Intent serviceIntent = new Intent(this, SensorLoggingService.class);
        startForegroundService(serviceIntent);

        // Initialize the button
        statusButton = findViewById(R.id.statusButton);
        statusButton.setText("Started");

        // Change to "Stopped" after 1 second
        handler.postDelayed(() -> statusButton.setText("Stopped"), 1000);
    }
}
