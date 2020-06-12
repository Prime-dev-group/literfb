package com.prime.superlitefb.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;

import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.Toast;


import androidx.core.app.NotificationCompat;

import com.prime.superlitefb.MyApplication;
import com.prime.superlitefb.R;
import com.prime.superlitefb.activity.MainActivity;
import com.prime.superlitefb.util.CheckConnection;
import com.prime.superlitefb.util.Misc;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;

public class NotificationsService extends Service {

    // Facebook URL constants
    private static final String BASE_URL = "https://mobile.facebook.com";
    private static final String NOTIFICATIONS_URL = "https://m.facebook.com/notifications.php";


    // number of trials during notifications or messages checking
    private static final int MAX_RETRY = 3;
    private static final int JSOUP_TIMEOUT = 10000;
    private static final String TAG;

    // HandlerThread, Handler (final to allow synchronization) and its runnable
    private final HandlerThread handlerThread;
    private final Handler handler;
    private static Runnable runnable;

    // volatile boolean to safely skip checking while service is being stopped
    private volatile boolean shouldContinue = true;
    private static String userAgent;
    private SharedPreferences preferences;

    private static final String MESSAGES_URL = "https://m.facebook.com/messages/?more";
    private static final String MESSAGES_URL_BACKUP = "https://mobile.facebook.com/messages";
    private static final String NOTIFICATION_OLD_MESSAGE_URL = "https://m.facebook.com/messages#";

    // static initializer
    static {
        TAG = NotificationsService.class.getSimpleName();
    }

    // class constructor, starts a new thread in which checkers are being run
    public NotificationsService() {
        handlerThread = new HandlerThread("Handler Thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {

        super.onCreate();

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        // create a runnable needed by a Handler
        runnable = new HandlerRunnable();

        // start a repeating checking, first run delay (3 seconds)
        handler.postDelayed(runnable, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        synchronized (handler) {
            shouldContinue = false;
            handler.notify();
        }

        handler.removeCallbacksAndMessages(null);
        handlerThread.quit();
    }

    /** A runnable used by the Handler to schedule checking. */
    private class HandlerRunnable implements Runnable {

        public void run() {
            try {
                // get time interval from tray preferences
                final int timeInterval = Integer.parseInt(preferences.getString("interval_pref", "1800000"));
                // time since last check = now - last check
                final long now = System.currentTimeMillis();
                final long sinceLastCheck = now - preferences.getLong("last_check", now);
                final boolean ntfLastStatus = preferences.getBoolean("ntf_last_status", false);
                final boolean msgLastStatus = preferences.getBoolean("msg_last_status", false);

                if ((sinceLastCheck < timeInterval) && ntfLastStatus && msgLastStatus) {
                    final long waitTime = timeInterval - sinceLastCheck;
                    if (waitTime >= 1000) {  // waiting less than a second is just stupid

                        synchronized (handler) {
                            try {
                                handler.wait(waitTime);
                            } catch (InterruptedException ex) {
//                               to be added
                            } finally {
//                                to be added
                            }
                        }

                    }
                }

                // when onDestroy() is run and lock is released, don't go on
                if (shouldContinue) {
                    // start AsyncTasks if there is internet connection
                    if (CheckConnection.isConnected(getApplicationContext())) {
                        if (CheckConnection.isConnectedMobile(getApplicationContext()))
                        userAgent = preferences.getString("webview_user_agent", System.getProperty("http.agent"));
                        if (preferences.getBoolean("notifications_activated", false))
                            new CheckNotificationsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
                        if (preferences.getBoolean("message_notifications", false))
                            new CheckMessagesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);

                        // save current time (last potentially successful checking)
                        preferences.edit().putLong("last_check", System.currentTimeMillis()).apply();
                    }

                    // set repeat time interval
                    handler.postDelayed(runnable, timeInterval);
                }

            } catch (RuntimeException re) {
                restartItself();
            }
        }

    }

    /** Notifications checker task: it checks Facebook notifications only. */
    private class CheckNotificationsTask extends AsyncTask<Void, Void, Element> {

        boolean syncProblemOccurred = false;

        private Element getElement(String connectUrl) {
            try {
                CookieManager cm = CookieManager.getInstance();
                if (preferences.getBoolean("use_tor", false)) {
                    return Jsoup.connect(connectUrl)
                            .userAgent(userAgent).timeout(JSOUP_TIMEOUT)
                            .proxy(Misc.getProxy(preferences))
                            .cookie("https://mobile.facebook.com", cm.getCookie("https://mobile.facebook.com"))
                            .cookie("https://m.facebookcorewwwi.onion", cm.getCookie("https://m.facebookcorewwwi.onion"))
                            .get()
                            .select("a.touchable")
                            .not("a._19no")
                            .not("a.button")
                            .first();
                } else {
                    return Jsoup.connect(connectUrl)
                            .userAgent(userAgent).timeout(JSOUP_TIMEOUT)
                            .cookie("https://mobile.facebook.com", cm.getCookie("https://mobile.facebook.com"))
                            .get()
                            .select("a.touchable")
                            .not("a._19no")
                            .not("a.button")
                            .first();
                }
            } catch (IllegalArgumentException | IOException ex) {
                if (ex instanceof IllegalArgumentException && !syncProblemOccurred) {
                    syncProblemToast();
                    syncProblemOccurred = true;
                }
            }
            return null;
        }

        @Override
        protected Element doInBackground(Void... params) {
            Element result = null;
            int tries = 0;

            syncCookies();

            while (tries++ < MAX_RETRY && result == null) {
                Element notification = getElement(NOTIFICATIONS_URL);
                if (notification != null)
                    result = notification;
            }

            return result;
        }

        @Override
        protected void onPostExecute(final Element result) {
            try {
                if (result == null)
                    return;
                if (result.text() == null)
                    return;

                final String time = result.select("span.mfss.fcg").text();
                final String text = result.text().replace(time, "");
                final String pictureStyle = result.select("i.img.l.profpic").attr("style");

                if (!preferences.getBoolean("activity_visible", false) || preferences.getBoolean("notifications_everywhere", true)) {
                    if (!preferences.getString("last_notification_text", "").equals(text)) {

                        // try to download a picture and send the notification
                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground (Void[] params){
                                Bitmap picture = Misc.getBitmapFromURL(Misc.extractUrl(pictureStyle));
                                String address = result.attr("href");
                                if (!address.contains("https"))
                                    address = BASE_URL + address;
                                notifier(text, address, false, picture);
                                return null;
                            }
                        }.execute();

                    }
                }

                // save as shown (or ignored) to avoid showing it again
                preferences.edit().putString("last_notification_text", text).apply();

                // save this check status
                preferences.edit().putBoolean("ntf_last_status", true).apply();

            } catch (Exception ex) {
                // save this check status
                preferences.edit().putBoolean("ntf_last_status", false).apply();
            }
        }

    }

    /** Messages checker task: it checks new messages only. */
    private class CheckMessagesTask extends AsyncTask<Void, Void, String> {

        boolean syncProblemOccurred = false;

        private String getNumber(String connectUrl) {
            try {
                CookieManager cm = CookieManager.getInstance();
                Elements message;
                if (preferences.getBoolean("use_tor", false)) {
                    message = Jsoup.connect(connectUrl)
                            .userAgent(userAgent)
                            .proxy(Misc.getProxy(preferences))
                            .timeout(JSOUP_TIMEOUT)
                            .cookie("https://m.facebook.com", cm.getCookie("https://m.facebook.com"))
                            .cookie("https://m.facebookcorewwwi.onion", cm.getCookie("https://m.facebookcorewwwi.onion"))
                            .get()
                            .select("div#viewport").select("div#page").select("div._129-")
                            .select("#messages_jewel").select("span._59tg");
                } else {
                    message = Jsoup.connect(connectUrl)
                            .userAgent(userAgent)
                            .timeout(JSOUP_TIMEOUT)
                            .cookie("https://m.facebook.com", cm.getCookie("https://m.facebook.com"))
                            .get()
                            .select("div#viewport").select("div#page").select("div._129-")
                            .select("#messages_jewel").select("span._59tg");
                }
                return message.html();
            } catch (IllegalArgumentException | IOException ex) {
                if (ex instanceof IllegalArgumentException && !syncProblemOccurred) {
                    syncProblemToast();
                    syncProblemOccurred = true;
                }
            }
            return "failure";
        }

        @Override
        protected String doInBackground(Void... params) {
            String result = null;
            int tries = 0;

            // sync cookies to get the right data
            syncCookies();

            while (tries++ < MAX_RETRY && result == null) {
                String number = getNumber(MESSAGES_URL);
                if (!number.matches("^[+-]?\\d+$")) {
                    number = getNumber(MESSAGES_URL_BACKUP);
                }
                if (number.matches("^[+-]?\\d+$"))
                    result = number;
            }

            return result;
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                // parse a number of unread messages
                int newMessages = Integer.parseInt(result);

                if (!preferences.getBoolean("activity_visible", false) || preferences.getBoolean("notifications_everywhere", true)) {
                    if (newMessages == 1)
                        notifier(getString(R.string.you_have_one_message), NOTIFICATION_OLD_MESSAGE_URL, true, null);
                    else if (newMessages > 1)
                        notifier(String.format(getString(R.string.you_have_n_messages), newMessages), NOTIFICATION_OLD_MESSAGE_URL, true, null);
                }

                // save this check status
                preferences.edit().putBoolean("msg_last_status", true).apply();
            } catch (NumberFormatException ex) {
                // save this check status
                preferences.edit().putBoolean("msg_last_status", false).apply();
            }
        }

    }

    /** CookieSyncManager was deprecated in API level 21.
     *  We need it for API level lower than 21 though.
     *  In API level >= 21 it's done automatically.
     */
    @SuppressWarnings("deprecation")
    private void syncCookies() {
        if (Build.VERSION.SDK_INT < 21) {
            CookieSyncManager.createInstance(getApplicationContext());
            CookieSyncManager.getInstance().sync();
        }
    }

    // show a Sync Problem Toast while not being on UI Thread
    private void syncProblemToast() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), getString(R.string.sync_problem),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // restart the service from inside the service
    private void restartItself() {
        final Context context = MyApplication.getContextOfApplication();
        final Intent intent = new Intent(context, NotificationsService.class);
        context.stopService(intent);
        context.startService(intent);
    }

    // create a notification and display it
    private void notifier(String title, String url, boolean isMessage, Bitmap picture) {
        // let's display a notification, dude!
        final String contentTitle;
        if (isMessage)
            contentTitle = getString(R.string.app_name) + ": " + getString(R.string.messages);
        else
            contentTitle = getString(R.string.app_name) + ": " + getString(R.string.notifications);


        // notification
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(title))
                        .setSmallIcon(R.mipmap.ic_stat_fs)
                        .setContentTitle(contentTitle)
                        .setContentText(title)
                        .setTicker(title)
                        .setWhen(System.currentTimeMillis())
                        .setAutoCancel(true);

        // picture is available
        if (picture != null)
            mBuilder.setLargeIcon(picture);

        // ringtone
        String ringtoneKey = "ringtone";
        if (isMessage)
            ringtoneKey = "ringtone_msg";

        Uri ringtoneUri = Uri.parse(preferences.getString(ringtoneKey, "content://settings/system/notification_sound"));
        mBuilder.setSound(ringtoneUri);

        // vibration
        if (preferences.getBoolean("vibrate", false))
            mBuilder.setVibrate(new long[] {500, 500});
        else
            mBuilder.setVibrate(new long[] {0L});

        // LED light
        if (preferences.getBoolean("led_light", false)) {
            Resources resources = getResources(), systemResources = Resources.getSystem();
            mBuilder.setLights(Color.CYAN,
                    resources.getInteger(systemResources.getIdentifier("config_defaultNotificationLedOn", "integer", "android")),
                    resources.getInteger(systemResources.getIdentifier("config_defaultNotificationLedOff", "integer", "android")));
        }

        // priority for Heads-up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            mBuilder.setPriority(Notification.PRIORITY_HIGH);

        // intent with notification url in extra
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("start_url", url);
        intent.setAction("NOTIFICATION_URL_ACTION");

        // final notification building
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(intent);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(resultPendingIntent);
        mBuilder.setOngoing(false);
        Notification note = mBuilder.build();

        // LED light flag
        if (preferences.getBoolean("led_light", false))
            note.flags |= Notification.FLAG_SHOW_LIGHTS;

        // display a notification
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // because message notifications are displayed separately
        if (isMessage)
            mNotificationManager.notify(1, note);
        else
            mNotificationManager.notify(0, note);
    }

    // cancel all the notifications which are visible at the moment
    public static void cancelAllNotifications() {
        NotificationManager notificationManager = (NotificationManager)
                MyApplication.getContextOfApplication().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

}
