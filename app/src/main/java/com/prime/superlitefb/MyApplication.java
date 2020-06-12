package com.prime.superlitefb;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;


public class MyApplication extends Application {

    @SuppressLint("StaticFieldLeak")
    private static Context mContext;


    @Override
    public void onCreate() {
        mContext = getApplicationContext();
        super.onCreate();
    }


    public static Context getContextOfApplication() {
        return mContext;
    }


}