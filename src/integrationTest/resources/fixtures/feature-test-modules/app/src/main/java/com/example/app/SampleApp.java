package com.example.app;

import android.app.Application;

public class SampleApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.d("SampleApp", "created");
    }
}
