package com.example.fixture;

import android.util.Log;

/** Would be rewritten by the Log and Thread instrumentations if they were applied. */
public class Foo implements Runnable {
    @Override
    public void run() {
        Log.d("Foo", "run");
    }
}
