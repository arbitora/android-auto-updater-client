//
//	Copyright (c) 2012 lenik terenin
//
//	Licensed under the Apache License, Version 2.0 (the "License");
//	you may not use this file except in compliance with the License.
//	You may obtain a copy of the License at
//
//		http://www.apache.org/licenses/LICENSE-2.0
//
//	Unless required by applicable law or agreed to in writing, software
//	distributed under the License is distributed on an "AS IS" BASIS,
//	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//	See the License for the specific language governing permissions and
//	limitations under the License.

package com.autoupdateapk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;

import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.BuildConfig;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompatSideChannelService;
import android.support.v4.content.FileProvider;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class AutoUpdateApk extends Observable {

	/**
	 * This class is supposed to be instantiated in any of your activities or,
	 * better yet, in Application subclass. Something along the lines of:
	 *
	 * <pre>
	 * private AutoUpdateApk aua;	<-- you need to add this line of code
	 *
	 * public void onCreate(Bundle savedInstanceState) {
	 * 	super.onCreate(savedInstanceState);
	 * 	setContentView(R.layout.main);
	 *
	 * 	aua = new AutoUpdateApk(getApplicationContext());	<-- and add this line too
	 * </pre>
	 *
	 * @param ctx
	 *            parent activity context
	 * @param apiPath
	 *            server API path may be relative to server (eg. /myapi/updater)
	 *            or absolute, depending on server implementation : relative
	 *            path and server is mandatory if server's implementation
	 *            provides relative paths. (http://www.auto-update-apk.com/
	 *            provides an existing server at {@link #PUBLIC_API_URL} )
	 * @param server
	 *            server name and port (eg. myserver.domain.com:8123 ). Should
	 *            be null when using absolutes apiPath.
	 */
	public AutoUpdateApk(Context ctx, String apiPath, String server) {
		setupVariables(ctx);
		this.server = server;
		this.apiPath = apiPath;
	}

	/*
	public AutoUpdateApk(Context ctx, String apiURL) {
		setupVariables(ctx);
		this.server = null;
		this.apiPath = apiURL;
	}
	*/

	// set icon for notification popup (default = application icon)
	//
	public static void setIcon(int icon) {
		appIcon = icon;
	}

	// set name to display in notification popup (default = application label)
	//
	public static void setName(String name) {
		appName = name;
	}

	// set Notification flags (default = Notification.FLAG_AUTO_CANCEL |
	// Notification.FLAG_NO_CLEAR)
	//
	public static void setNotificationFlags(int flags) {
		NOTIFICATION_FLAGS = flags;
	}

	/**
	 * set update interval (in milliseconds).
	 *
	 * there are nice constants in this file: MINUTES, HOURS, DAYS you may use
	 * them to specify update interval like: 5 * DAYS
	 *
	 * please, don't specify update interval below 1 hour, this might be
	 * considered annoying behaviour and result in service suspension
	 */
	public void setUpdateInterval(long interval) {
		// if( interval > 60 * MINUTES ) {
		updateInterval = interval;
		// } else {
		// Log_e(TAG, "update interval is too short (less than 1 hour)");
		// }
	}

	// software updates will use WiFi/Ethernet only (default mode)
	//
	public static void disableMobileUpdates() {
		mobile_updates = false;
	}

	// software updates will use any internet connection, including mobile
	// might be a good idea to have 'unlimited' plan on your 3.75G connection
	//
	public static void enableMobileUpdates() {
		mobile_updates = true;
	}

	// call this if you want to perform update on demand
	// (checking for updates more often than once an hour is not recommended
	// and polling server every few minutes might be a reason for suspension)
	public void checkUpdatesManually() {checkUpdates(true);}

	public String checkUpdatingStatus(){
		if (updateManager != null){
			return updateManager.getDownloadStatus();
		}
		else{
			// Not downloading an update.
			if (preferences.contains(MD5_TIME)){
				Date updateDate = new Date(preferences.getLong(MD5_TIME, 0));
				SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
				return "Last update: " +  sdf.format(updateDate).toString();
			}

			else{
				return "No update checks performed.";
			}
		}
	}

	/*
		Should be called after unregistering the receiver.
	 */
	public void registerReceiver(){
		if (!receiverRegistered){
			if (haveInternetPermissions()) {
				receiverRegistered = true;
				context.registerReceiver(connectivity_receiver, new IntentFilter(
						ConnectivityManager.CONNECTIVITY_ACTION));
			}
		}
	}

	/*
		Unregisters receiver. Remember to re-register the receiver with registerReceiver()
	 */
	public void unRegisterReceived(){
		receiverRegistered = false;
		context.unregisterReceiver(connectivity_receiver);
	}

	private boolean receiverRegistered = false;
	private BroadcastReceiver connectivity_receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {

			NetworkInfo currentNetworkInfo;

			// Marshmallow (API 23=<
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
				ConnectivityManager connectivityManager = (ConnectivityManager)
						context.getSystemService(Context.CONNECTIVITY_SERVICE);
				if (connectivityManager != null)
					currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
				else
					currentNetworkInfo = null;
			}
			else{
				currentNetworkInfo = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
			}

			// do application-specific task(s) based on the current network
			// state, such
			// as enabling queuing of HTTP requests when currentNetworkInfo is
			// connected etc.
			if (currentNetworkInfo != null){
				boolean not_mobile = !currentNetworkInfo.getTypeName()
						.equalsIgnoreCase("MOBILE");
				if (currentNetworkInfo.isConnected()
						&& (mobile_updates || not_mobile)) {
					checkUpdates(false);
					updateHandler.postDelayed(periodicUpdate, updateInterval);
				} else {
					updateHandler.removeCallbacks(periodicUpdate); // no network
					// anyway
				}
			}
			updateHandler.removeCallbacks(periodicUpdate); // no network
			// anyway

		}
	};

	public static final String AUTOUPDATE_CHECKING = "autoupdate_checking";
	public static final String AUTOUPDATE_NO_UPDATE = "autoupdate_no_update";
	public static final String AUTOUPDATE_GOT_UPDATE = "autoupdate_got_update";
	public static final String AUTOUPDATE_HAVE_UPDATE = "autoupdate_have_update";

	public static final String PUBLIC_API_URL = "http://www.auto-update-apk.com/";
	public static final String NOTIFICATION_CHANNEL_ID = "Update_Channel";

	public void clearSchedule() {
		schedule.clear();
	}


	public void addSchedule(int start, int end) {
		schedule.add(new ScheduleEntry(start, end));
	}

	//
	// ---------- everything below this line is private and does not belong to
	// the public API ----------
	//
	protected final static String TAG = "AutoUpdateApk";

	private final static String ANDROID_PACKAGE = "application/vnd.android.package-archive";

	private final String server;
	private final String apiPath;

	private Context context = null;
	private static SharedPreferences preferences;
	private final static String LAST_UPDATE_KEY = "last_update";
	private static long last_update = 0;

	private static int appIcon = android.R.drawable.ic_popup_reminder;
	private static String packageName;
	private static String appName;
	private static int device_id;

	public static final long SECONDS = 1000;
	public static final long MINUTES = 60 * SECONDS;
	public static final long HOURS = 60 * MINUTES;
	public static final long DAYS = 24 * HOURS;

	// 3-4 hours in dev.mode, 1-2 days for stable releases
	private long updateInterval = 3 * HOURS; // how often to check

	private static boolean forced = false;
	private static boolean mobile_updates = false; // download updates over wifi
	// only

	private UpdateManager updateManager;
	private final static Handler updateHandler = new Handler();
	protected final static String UPDATE_FILE = "update_file";
	protected final static String SILENT_FAILED = "silent_failed";
	private final static String MD5_TIME = "md5_time";
	private final static String MD5_KEY = "md5";
	private final static String VERSION_KEY = "vKey";

	private static int NOTIFICATION_ID = 0xDEADBEEF;
	private static int NOTIFICATION_FLAGS = Notification.FLAG_AUTO_CANCEL
			| Notification.FLAG_NO_CLEAR;
	private static long WAKEUP_INTERVAL = 500;

	private class ScheduleEntry {
		private int start;
		private int end;

		private ScheduleEntry(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	private static List<ScheduleEntry> schedule = new ArrayList<ScheduleEntry>();

	private Runnable periodicUpdate = new Runnable() {
		@Override
		public void run() {
			checkUpdates(false);

			updateHandler.removeCallbacks(periodicUpdate); // remove whatever
			// others may have
			// posted
			updateHandler.postDelayed(this, WAKEUP_INTERVAL);
		}
	};



	private void setupVariables(Context ctx) {
		context = ctx;

		packageName = context.getPackageName();
		preferences = context.getSharedPreferences(packageName + "_" + TAG,
				Context.MODE_PRIVATE);
		device_id = crc32(Settings.Secure.getString(context.getContentResolver(),
				Settings.Secure.ANDROID_ID));
		last_update = preferences.getLong("last_update", 0);
		NOTIFICATION_ID += crc32(packageName);
		// schedule.add(new ScheduleEntry(0,24));

		ApplicationInfo appInfo = context.getApplicationInfo();
		if (appInfo.icon != 0) {
			appIcon = appInfo.icon;
		} else {
			Log_w(TAG, "unable to find application icon");
		}
		if (appInfo.labelRes != 0) {
			appName = context.getString(appInfo.labelRes);
		} else {
			Log_w(TAG, "unable to find application label");
		}

		String downloadFolderPath = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
				.getAbsolutePath();
		if (new File (downloadFolderPath).lastModified() > preferences.getLong(MD5_TIME, 0)){
			preferences.edit().putString(MD5_KEY,
						MD5Hex(appInfo.sourceDir))
						.apply();
			preferences.edit().putLong(MD5_TIME, System.currentTimeMillis())
					.apply();

			String update_file = preferences.getString(UPDATE_FILE, "");
			if (update_file.length() > 0) {
				File temporary = new File(downloadFolderPath + "/");
				if (temporary.delete()) {
					preferences.edit().remove(UPDATE_FILE)
							.remove(SILENT_FAILED).apply();
				}
			}
		}
		raise_notification();

		if (haveInternetPermissions()) {
			receiverRegistered = true;
			context.registerReceiver(connectivity_receiver, new IntentFilter(
					ConnectivityManager.CONNECTIVITY_ACTION));
		}


	}

	private boolean checkSchedule() {
		if (schedule.size() == 0)
			return true; // empty schedule always fits

		int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
		for (ScheduleEntry e : schedule) {
			if (now >= e.start && now < e.end)
				return true;
		}
		return false;
	}


	private void checkUpdates(boolean was_forced){
		forced = was_forced;

		long now = System.currentTimeMillis();
		if (forced || (last_update + updateInterval) < now && checkSchedule()){
			RequestQueue updateQueue = Volley.newRequestQueue(context);
			StringRequest updateRequest = new StringRequest(Request.Method.POST,
					(server + apiPath), new Response.Listener<String>() {
				@Override
				public void onResponse(String response) {

					if (response != null){
						Log_v(TAG, "Message from the server:\n" + response);

						String [] result = response.split("[\\n\\r]+");
						if (result[0].equalsIgnoreCase("Update found")){
							// UPDATE FOUND
							setChanged();
							notifyObservers(AUTOUPDATE_GOT_UPDATE);
							if (result.length > 2 && result[2] != null){
								try{
									preferences.edit().putInt(VERSION_KEY, Integer.parseInt(result[2])).apply();
								}catch(NumberFormatException nfe){
									Log_e(TAG, "Invalid version code", nfe);
								}
							}

							if (result.length > 3 && result[3] != null && result.length
									> 1 && result[1] != null){
								// Update last update time.
								preferences.edit()
										.putLong(MD5_TIME, System.currentTimeMillis())
										.apply();

								if (result[3].equalsIgnoreCase("true")){
									// Manual update
									// TODO Notify download
									//raise_notification();
									startDownload(server + result[1]);
								}
								else{
									// Auto update
									// TODO Notify download
									//raise_notification();
									startDownload(server + result[1]);
								}
							}

						}
						else if (result[0].equalsIgnoreCase("OK")){
							// LATEST UPDATE
							setChanged();
							notifyObservers(AUTOUPDATE_NO_UPDATE);
							Log_v(TAG, "No update available");
						}
						else{
							// INCORRECT RESPONSE
							setChanged();
							notifyObservers(AUTOUPDATE_NO_UPDATE);
							Log_e(TAG, "Error, incorrect response from server.");
						}
					}
					else{
						Log_v(TAG, "There was no reply from update server.");
						raise_notification();
					}



				}
			}, new Response.ErrorListener() {
				@Override
				public void onErrorResponse(VolleyError error) {

					setChanged();
					notifyObservers(AUTOUPDATE_NO_UPDATE);
					Log_v(TAG, "Error connecting to " + server + apiPath);

					Log_e("onErrorResponse:", error.toString());
				}
			}) {
				@Override
				protected Response<String> parseNetworkResponse(NetworkResponse response) {
					long elapsed = response.networkTimeMs;
					Log_v(TAG, "Update check finished in " + elapsed + "ms");

					return super.parseNetworkResponse(response);
				}

				@Override
				public String getBodyContentType() {
					return "application/x-www-form-urlencoded; charset=UTF-8";
				}

				@Override
				protected Map<String, String> getParams() throws AuthFailureError {
					Map<String, String> postMessage = new HashMap<>();
					/*
						Construct POST structure.
					 */
					postMessage.put("pkgname", packageName);
					postMessage.put("version", String.valueOf(preferences.getInt(VERSION_KEY, 0)));
					postMessage.put("md5", preferences.getString(MD5_KEY, "0"));
					postMessage.put("id", String.format("%08x", device_id));
					postMessage.put("forced", String.valueOf(forced));

					return postMessage;
				}
			};

			last_update = System.currentTimeMillis();
			preferences.edit().putLong(LAST_UPDATE_KEY, last_update).apply();

			this.setChanged();
			this.notifyObservers(AUTOUPDATE_CHECKING);

			// Set Volley request's retry policies.
			updateRequest.setRetryPolicy(new DefaultRetryPolicy(10 * (int) SECONDS,
					0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
			updateQueue.add(updateRequest);
		}
	}

	private void startDownload(String downloadUrl){
		if (updateManager == null){
			updateManager = new UpdateManager(context, downloadUrl, new UpdateManagerListener(){

				@Override
				public void updateManagerDownloadStarted(){
					// Download started
					Log_v(TAG, "UpdateManager: download has been started.");
				}

				@Override
				public void updateManagerError(String message){
					// Status messages can be read here.
					Log_e(TAG, "UpdateManagerError: " + message);
				}

				@Override
				public void updateManagerCancelled(){
					// Download was cancelled.
					Log_v(TAG, "UpdateManager: download has been cancelled.");
				}

				@Override
				public void updateManagerFinished(Uri uri, String mimeType){

					/*String update_file_path = context.getFilesDir()
							.getAbsolutePath() + "/" + result[1];*/
					String parsedUri = uri.toString();
					// Save preferences of the file
					String fileName = parsedUri.substring(parsedUri
							.lastIndexOf('/') + 1);
					preferences.edit().putString(UPDATE_FILE,
							fileName).apply();
					preferences.edit()
							.putString(MD5_KEY, MD5Hex(parsedUri))
							.apply();

					raise_notification();
				}
			});
			updateManager.startDownload();
		}
		else
			updateManager.startDownload();

	}


	/*
		Raises a notification, where user can start the installation of an update.
	 */
	protected void raise_notification() {

		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager nm = (NotificationManager) context
				.getSystemService(ns);

		// nm.cancel( NOTIFICATION_ID ); // tried this, but it just doesn't do
		// the trick =(
		nm.cancelAll();

		String update_file = preferences.getString(UPDATE_FILE, "");
		if (update_file.length() > 0) {
			setChanged();
			notifyObservers(AUTOUPDATE_HAVE_UPDATE);

			// Raise a notification installation notification

			// Create the intent to open the APK file.
			Intent notificationIntent;
			PendingIntent contentIntent;
			// Nougat (API 24=<), use File Provider method.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
				notificationIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);

				String temp = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
						.getAbsolutePath() + "/" + update_file;
				if (temp.substring(0,7).matches("file://")){
					temp = temp.substring(7);
				}

				File installationFile = new File(temp);


				Uri fileUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID
						+ ".provider", installationFile);

				notificationIntent = notificationIntent.setData(fileUri);

				notificationIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			}
			// Otherwise use old file:// path method to open the APK file.
			else{
				notificationIntent = new Intent(Intent.ACTION_VIEW);
				notificationIntent.setDataAndType(
						Uri.parse("file://"
								+ context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
								.getAbsolutePath() + "/"
								+ update_file), ANDROID_PACKAGE);

			}

			contentIntent = PendingIntent.getActivity(context, 0,
					notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

			//Notification.Builder builder = new Notification.Builder(context);
			NotificationCompat.Builder builder = new NotificationCompat.Builder(context);

			builder.setAutoCancel(false);
			builder.setTicker(appName + " " + context.getString(R.string.txt_notif_updateAvailable));
			builder.setContentTitle(appName +  " " + context.getString(R.string.txt_notif_update));
			builder.setContentText(appName +  " " + context.getString(R.string.txt_notif_updateAvailable));
			builder.setSmallIcon(appIcon);
			builder.setContentIntent(contentIntent);
			builder.setOngoing(true);
			builder.setSubText(context.getString(R.string.txt_notif_clickHereToInstall));   //API level 16
			builder.setWhen(System.currentTimeMillis());

			nm.notify(NOTIFICATION_ID, builder.build());
		} else {
			nm.cancel(NOTIFICATION_ID);
		}

	}

	private String MD5Hex(String filename) {
		final int BUFFER_SIZE = 8192;
		byte[] buf = new byte[BUFFER_SIZE];
		int length;
		try {

			FileInputStream fis = new FileInputStream(filename);
			BufferedInputStream bis = new BufferedInputStream(fis);
			MessageDigest md = java.security.MessageDigest.getInstance("MD5");
			while ((length = bis.read(buf)) != -1) {
				md.update(buf, 0, length);
			}
			bis.close();
			fis.close();

			byte[] array = md.digest();
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < array.length; ++i) {
				sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100)
						.substring(1, 3));
			}
			Log_v(TAG, "md5sum: " + sb.toString());
			return sb.toString();
		} catch (Exception e) {
			Log_e(TAG, e.getMessage());
		}
		return "md5bad";
	}

	private boolean haveInternetPermissions() {
		Set<String> required_perms = new HashSet<String>();
		required_perms.add("android.permission.INTERNET");
		required_perms.add("android.permission.ACCESS_WIFI_STATE");
		required_perms.add("android.permission.ACCESS_NETWORK_STATE");
		required_perms.add("android.permission.WRITE_EXTERNAL_STORAGE");

		PackageManager pm = context.getPackageManager();
		String packageName = context.getPackageName();
		int flags = PackageManager.GET_PERMISSIONS;
		PackageInfo packageInfo = null;

		int versionCode = 0;
		try {
			packageInfo = pm.getPackageInfo(packageName, flags);
			versionCode = packageInfo.versionCode;
		} catch (PackageManager.NameNotFoundException e) {
			Log_e(TAG, e.getMessage());
		}

		if (preferences != null)
			preferences.edit().putInt(VERSION_KEY, versionCode).apply();

		if (packageInfo != null) {
			for (String p : packageInfo.requestedPermissions) {
				// Log_v(TAG, "permission: " + p.toString());
				required_perms.remove(p);
			}
			if (required_perms.size() == 0) {
				return true; // permissions are in order
			}
			// something is missing
			for (String p : required_perms) {
				Log_e(TAG, "required permission missing: " + p);
			}
		}
		Log_e(TAG,
				"INTERNET/WIFI access required, but no permissions are found in Manifest.xml");
		return false;
	}

	private static int crc32(String str) {
		byte bytes[] = str.getBytes();
		Checksum checksum = new CRC32();
		checksum.update(bytes, 0, bytes.length);
		return (int) checksum.getValue();
	}

	// logging facilities to enable easy overriding. thanks, Dan!
	//
	protected void Log_v(String tag, String message) {
		Log_v(tag, message, null);
	}

	protected void Log_v(String tag, String message, Throwable e) {
		log("v", tag, message, e);
	}

	protected void Log_d(String tag, String message) {
		Log_d(tag, message, null);
	}

	protected void Log_d(String tag, String message, Throwable e) {
		log("d", tag, message, e);
	}

	protected void Log_i(String tag, String message) {
		Log_d(tag, message, null);
	}

	protected void Log_i(String tag, String message, Throwable e) {
		log("i", tag, message, e);
	}

	protected void Log_w(String tag, String message) {
		Log_w(tag, message, null);
	}

	protected void Log_w(String tag, String message, Throwable e) {
		log("w", tag, message, e);
	}

	protected void Log_e(String tag, String message) {
		Log_e(tag, message, null);
	}

	protected void Log_e(String tag, String message, Throwable e) {
		log("e", tag, message, e);
	}


	protected void log(String level, String tag, String message, Throwable e) {
		if (message == null) {
			return;
		}
		if (level.equalsIgnoreCase("v")) {
			if (e == null)
				android.util.Log.v(tag, message);
			else
				android.util.Log.v(tag, message, e);
		} else if (level.equalsIgnoreCase("d")) {
			if (e == null)
				android.util.Log.d(tag, message);
			else
				android.util.Log.d(tag, message, e);
		} else if (level.equalsIgnoreCase("i")) {
			if (e == null)
				android.util.Log.i(tag, message);
			else
				android.util.Log.i(tag, message, e);
		} else if (level.equalsIgnoreCase("w")) {
			if (e == null)
				android.util.Log.w(tag, message);
			else
				android.util.Log.w(tag, message, e);
		} else {
			if (e == null)
				android.util.Log.e(tag, message);
			else
				android.util.Log.e(tag, message, e);
		}
	}

}
