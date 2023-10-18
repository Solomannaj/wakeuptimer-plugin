package org.cordova.alarmclockplugin;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.santen.actpack.MainActivity;
import com.santen.actpack.R;

import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.app.PendingIntent.getBroadcast;
import static android.provider.Telephony.Mms.Part.TEXT;

public class WakeupReceiver extends BroadcastReceiver {

	private static final String LOG_TAG = "WakeupReceiver";
	private PowerManager.WakeLock mWakeLock;
	private MediaPlayer mMediaPlayer;

	public boolean isRunning(Context ctx) {
		ActivityManager activityManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
		List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(Integer.MAX_VALUE);

		for (ActivityManager.RunningTaskInfo task : tasks) {
			if (ctx.getPackageName().equalsIgnoreCase(task.baseActivity.getPackageName())) {
				if (task.numRunning > 0) {
					return true;
				}
			}
		}

		return false;
	}

	@SuppressLint({ "SimpleDateFormat", "NewApi" })
	@Override
	public void onReceive(Context context, Intent intent) {
		//Toast.makeText(context, "Alaram on receive is received on boot...................", Toast.LENGTH_LONG).show();
		boolean awakeScreen = false;
		boolean nativeNotification = false;
		SimpleDateFormat sdf=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		Log.d(LOG_TAG, "wakeuptimer expired at " + sdf.format(new Date().getTime()));

		try {
			Bundle extrasBundle = intent.getExtras();

			if (extrasBundle != null && extrasBundle.get("skipOnAwake") != null) {
				if (extrasBundle.get("skipOnAwake").equals(true)) {
					PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
					boolean isScreenAwake = (Build.VERSION.SDK_INT < 20 ? powerManager.isScreenOn() : powerManager.isInteractive());

					if (isScreenAwake) {
						Log.d(LOG_TAG, "screen is awake. Postponing launch.");
						return;
					}
				}
			}

			if (extrasBundle != null && extrasBundle.get("skipOnRunning") != null) {
				if (extrasBundle.get("skipOnRunning").equals(true)) {
					if (isRunning(context)) {
						Log.d(LOG_TAG, "app is already running. No need to launch");
						return;
					}
				}
			}

			String packageName = context.getPackageName();
			Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
			launchIntent.putExtra("cdvStartInBackground", true);

			String className = launchIntent.getComponent().getClassName();
			Log.d(LOG_TAG, "launching activity for class " + className);
			Log.d(LOG_TAG, "wakeuptimer : classname " + className);

			@SuppressWarnings("rawtypes")
			Class c = Class.forName(className);

			Intent i = new Intent(context, c);
			i.putExtra("wakeup", true);

			if (extrasBundle != null && extrasBundle.get("startInBackground") != null) {
				if (extrasBundle.get("startInBackground").equals(true)) {
					Log.d(LOG_TAG, "starting app in background");
					i.putExtra("cdvStartInBackground", true);
				}
			}

			if (extrasBundle != null) {
				if (extrasBundle.get("awakeScreen") != null && extrasBundle.get("awakeScreen").equals(true)) {
					awakeScreen = true;
				}

				if (extrasBundle.get("nativeNotification") != null && extrasBundle.get("nativeNotification").equals(true)) {
					nativeNotification = true;
				}

			}

			if (awakeScreen) {
				checkAndAcquireWakeLock(context);
			}

			if (nativeNotification && WakeupPlugin.connectionCallbackContext == null) {
				// Raise the notification only when the connection callback is empty
				raisePriorityNotification(context, i, extrasBundle);
			}

			String extras=null;
			if (extrasBundle!=null && extrasBundle.get("extra")!=null) {
				extras = extrasBundle.get("extra").toString();
			}

			if (extras!=null) {
				i.putExtra("extra", extras);
			}
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

			if(WakeupPlugin.connectionCallbackContext!=null) {
				JSONObject o=new JSONObject();
				o.put("type", "wakeup");
				if (extras!=null) {
					o.put("extra", extras);
				}
				o.put("cdvStartInBackground", true);
				PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, o);
				pluginResult.setKeepCallback(true);
				WakeupPlugin.connectionCallbackContext.sendPluginResult(pluginResult);
			}

			if (extrasBundle!=null && extrasBundle.getString("type")!=null && extrasBundle.getString("type").equals("daylist")) {
				// repeat in one week
				Date next = new Date(new Date().getTime() + (7 * 24 * 60 * 60 * 1000));
				Log.d(LOG_TAG,"resetting alarm at " + sdf.format(next));

				Intent reschedule = new Intent(context, WakeupReceiver.class);
				if (extras!=null) {
					reschedule.putExtra("extra", intent.getExtras().get("extra").toString());
				}
				reschedule.putExtra("day", WakeupPlugin.daysOfWeek.get(intent.getExtras().get("day")));
				reschedule.putExtra("cdvStartInBackground", true);

				PendingIntent sender = getBroadcast(context, 19999 + WakeupPlugin.daysOfWeek.get(intent.getExtras().get("day")), intent, FLAG_UPDATE_CURRENT);
				AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
				if (Build.VERSION.SDK_INT>=19) {
					alarmManager.setExact(AlarmManager.RTC_WAKEUP, next.getTime(), sender);
				} else {
					alarmManager.set(AlarmManager.RTC_WAKEUP, next.getTime(), sender);
				}
			}

			if (WakeupPlugin.connectionCallbackContext != null || !nativeNotification) {
				context.startActivity(i);
			}

		} catch (JSONException e){
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}


	private void checkAndAcquireWakeLock(Context context) {
		PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
		boolean isScreenAwake = (Build.VERSION.SDK_INT < 20 ? powerManager.isScreenOn() : powerManager.isInteractive());

		if (!isScreenAwake) {
			mWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK |
					PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
					PowerManager.ACQUIRE_CAUSES_WAKEUP |
					PowerManager.ON_AFTER_RELEASE, "Wake:Log");
			mWakeLock.acquire(5*60*1000L /*5 minutes*/);

//			new Handler().postDelayed(() -> {
//				if (null != mWakeLock && mWakeLock.isHeld()) {
//					mWakeLock.release();
//				}
//			},5000);
		}
	}

	private void raisePriorityNotification(Context context, Intent intent, Bundle extrasBundle) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			String title = extrasBundle.getString("title", "Medicine");
			String message = extrasBundle.getString("message", "Medicine");
			String channelId = extrasBundle.getString("notificationChannelId", "wc_alarms");
			String channelName = extrasBundle.getString("notificationChannelTitle", "Alarms");

			NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
			createNotificationChannel(notificationManager, channelId, channelName);

//			Bundle extra = null;
//			if (extrasBundle.get("extra") != null) {
//				extra = new Bundle();
//				extra.putString("extra", extrasBundle.get("extra").toString());
//			}

			NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
					.setDefaults(Notification.DEFAULT_ALL)
					.setContentTitle(title)
					.setStyle(new NotificationCompat.BigTextStyle()
							.bigText(TEXT))
					.setContentText(message)
					.setSmallIcon(R.drawable.screen)
					//.setVibrate(new long[] {1000,500,1000,500,1000,500})
					//.setOngoing(true)
					.setAutoCancel(true)
					.setPriority(NotificationCompat.PRIORITY_HIGH)
					//.setTimeoutAfter(5*60*1000)
					.setFullScreenIntent(launchAlarmLandingPage(context, extrasBundle), true)
					.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

//			NotificationCompat.Action launchAction = new NotificationCompat.Action.Builder(
//					R.drawable.launch,
//					"Launch",
//					launchAlarmLandingPage(context, extra))
//					.build();
//
//			NotificationCompat.Action snoozeAction = new NotificationCompat.Action.Builder(
//					R.drawable.snooze,
//					"Dismiss",
//					startAlarmPendingIntent)
//					.build();
			//builder.addAction(launchAction);
			//builder.addAction(snoozeAction);
//			if (null != extra) {
//				builder.addExtras(extra);
//			}
			notificationManager.notify(146, builder.build());
		}
	}

	private PendingIntent launchAlarmLandingPage(Context context, Bundle extra) {
		final Intent alarmIntent = new Intent(context, LaunchScreenActivity.class);

		if (null != extra) {
            Log.e(LOG_TAG, "launchAlarmLandingPage: " + extra.get("extra"));
			alarmIntent.putExtras(extra);
		}
		alarmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		return PendingIntent.getActivity(context, 11, alarmIntent, FLAG_UPDATE_CURRENT);
	}

	public Intent launchIntent(Context context) {
		final Intent i = new Intent(context, MainActivity.class);
		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		return i;
	}

	private void createNotificationChannel(NotificationManager notificationManager, String channelId, String channelName) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			if (notificationManager.getNotificationChannel(channelId) == null) {
				NotificationChannel channel = new NotificationChannel(channelId,
						channelName,
						NotificationManager.IMPORTANCE_HIGH);
				channel.enableVibration(true);
				channel.setVibrationPattern(new long[] {1000,500,1000,500,1000,500});
				channel.setBypassDnd(true);
				notificationManager.createNotificationChannel(channel);
			}
		}
	}

}
