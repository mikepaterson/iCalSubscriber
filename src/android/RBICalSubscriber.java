package com.rosterbot.cordova.plugin;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Calendar;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Property;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

/**
 *
 */
public class RBICalSubscriber extends CordovaPlugin
{
    private GoogleAccountCredential credential      = null;
    private String                  calendarUrl     = null;
    private Context                 activityContext = null;
    private CallbackContext         callbackContext = null;

    private static final int REQUEST_CODE_PICK_ACCOUNT       = 0;
    private static final int REQUEST_PERMISSION_GET_ACCOUNTS = 1;

    private static final String PREF_ACCOUNT_NAME = "rb_ical_account_chosen_name";

    private static final String ACTION_SUBSCRIBE = "subscribe";

    /**
     *
     */
    @Override
    protected void pluginInitialize()
    {
        activityContext = this.cordova.getActivity().getApplicationContext();
        credential =
                GoogleAccountCredential.usingOAuth2(activityContext, Collections.singleton(CalendarScopes.CALENDAR))
                                       .setBackOff(new ExponentialBackOff());
    }

    /**
     * @param action          The action to execute.
     * @param args            The exec() arguments.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return Boolean result
     * @throws JSONException if the JSON isn't json, I assume
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException
    {
        if (action.equals(ACTION_SUBSCRIBE))
        {
            this.callbackContext = callbackContext;
            calendarUrl = args.getString(0);
            if (calendarUrl != null && calendarUrl.startsWith("webcal"))
            {
                calendarUrl = calendarUrl.replace("webcal", "https");
            }
            else if (calendarUrl == null || !calendarUrl.startsWith("https"))
            {
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Could not make sense of the calendar URL. Please contact RosterBot Support by e-mail at support@rosterbot.com"));
            }

            if (!isGooglePlayServicesAvailable())
            {
                acquireGooglePlayServices();
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Google play services don't seem to be available."));
            }
            else if (credential.getSelectedAccountName() == null)
            {
                gainCalendarAccess();
            }
            else
            {
                SharedPreferences settings     = this.cordova.getActivity().getPreferences(Context.MODE_PRIVATE);
                String            accountEmail = settings.getString(PREF_ACCOUNT_NAME, null);
                if (accountEmail != null)
                {
                    // With the account name acquired, go get the auth token
                    credential.setSelectedAccountName(accountEmail);
                    doAuth(accountEmail);
                }
            }
            return true;
        }
        return false;  // Returning false results in a "MethodNotFound" error.
    }

    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    private void gainCalendarAccess()
    {
        if (EasyPermissions.hasPermissions(activityContext, Manifest.permission.GET_ACCOUNTS))
        {
            String accountName = this.cordova.getActivity().getPreferences(Context.MODE_PRIVATE)
                                             .getString(PREF_ACCOUNT_NAME, null);
            if (accountName != null)
            {
                credential.setSelectedAccountName(accountName);
                doAuth(accountName);
            }
            else
            {
                Intent intent = credential.newChooseAccountIntent();
                this.cordova.startActivityForResult(this, intent, REQUEST_CODE_PICK_ACCOUNT);
            }
        }
        else
        {
            // Request the GET_ACCOUNTS permission via a user dialog
            EasyPermissions.requestPermissions(
                    this.cordova.getActivity(),
                    "RosterBot needs to access your Google Calendar.",
                    REQUEST_PERMISSION_GET_ACCOUNTS,
                    Manifest.permission.GET_ACCOUNTS);
        }
    }

    /**
     * @param requestCode The request code originally supplied to startActivityForResult(),
     *                    allowing you to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its setResult().
     * @param intent      An Intent, which can return result data to the caller (various data can be
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent)
    {
        if (resultCode == RESULT_OK)
        {
            if (requestCode == REQUEST_CODE_PICK_ACCOUNT)
            {
                String accountEmail = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (accountEmail != null)
                {
                    SharedPreferences        settings = this.cordova.getActivity().getPreferences(Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor   = settings.edit();
                    editor.putString(PREF_ACCOUNT_NAME, accountEmail);
                    editor.apply();

                    // With the account name acquired, go get the auth token
                    credential.setSelectedAccountName(accountEmail);
                    doAuth(accountEmail);
                }
            }
        }
        else if (resultCode == RESULT_CANCELED)
        {
            if (RBICalSubscriber.this.callbackContext != null)
            {
                RBICalSubscriber.this.callbackContext.error("an error occured");
                RBICalSubscriber.this.callbackContext = null;
            }
        }
    }

    /**
     *
     */
    private class importCalendarTask extends AsyncTask<String, String, String>
    {
        JsonBatchCallback<Event> eventJsonBatchCallback = new JsonBatchCallback<Event>()
        {
            public void onSuccess(Event event, HttpHeaders responseHeaders)
            {
                Log.e("ICalSubscriber", String.format("Saved event: %s", event.getICalUID()));
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders)
            {
                Log.e("ICalSubscriber", e.getMessage());
            }
        };

        JsonBatchCallback<Void> deleteJsonBatchCallback = new JsonBatchCallback<Void>()
        {
            public void onSuccess(Void v, HttpHeaders responseHeaders)
            {
                Log.e("ICalSubscriber", "Deleted event");
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders)
            {
                Log.e("ICalSubscriber", "Error deleting event.");
            }
        };

        /**
         * @param params String...
         * @return String
         */
        @Override
        protected String doInBackground(String... params)
        {
            RBICalSubscriber.this.cordova.getActivity().runOnUiThread(new Runnable()
            {
                public void run()
                {
                    Toast.makeText(RBICalSubscriber.this.cordova.getActivity(), "Beginning Calendar Sync...",
                                   Toast.LENGTH_SHORT).show();
                }
            });


            HttpsURLConnection urlConnection = null;
            try
            {
                URL url = new URL(params[0]);

                urlConnection = (HttpsURLConnection) url.openConnection();

                urlConnection.setInstanceFollowRedirects(true);

                InputStream calendarStream = new BufferedInputStream(urlConnection.getInputStream());

                //these two lines fix a crash that started appearing for no apparent reason.
                ClassLoader otherLoader = getClass().getClassLoader();
                Thread.currentThread().setContextClassLoader(otherLoader);

                net.fortuna.ical4j.data.CalendarBuilder builder      = new net.fortuna.ical4j.data.CalendarBuilder();
                net.fortuna.ical4j.model.Calendar       calendar     = builder.build(calendarStream);
                String                                  calendarName = calendar.getProperty("X-WR-CALNAME").getValue();
                HttpTransport                           transport    = new NetHttpTransport();

                JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

                com.google.api.services.calendar.Calendar calendarClient;

                calendarClient = new com.google.api.services.calendar
                        .Calendar.Builder(transport, jsonFactory, credential)
                        .setApplicationName("Rosterbot_Android/2.0").build();

                BatchRequest batchRequest = calendarClient.batch();

                Calendar thisCalendar = new Calendar();

                List<CalendarListEntry> list           = calendarClient.calendarList().list().execute().getItems();
                boolean                 calendarExists = false;
                for (CalendarListEntry entry : list)
                {
                    if (entry.getSummary().equals(calendarName))
                    {
                        thisCalendar.setId(entry.getId());
                        thisCalendar.setSummary(entry.getSummary());
                        calendarExists = true;
                        break;
                    }
                }

                List<Event> events = null;

                if (!calendarExists)
                {
                    thisCalendar.setSummary(calendarName);
                    thisCalendar = calendarClient
                            .calendars()
                            .insert(thisCalendar)
                            .setFields("id,summary")
                            .execute();
                    events = new ArrayList<Event>();
                }
                else
                {
                    events = calendarClient.events()
                                           .list(thisCalendar.getId())
                                           .execute()
                                           .getItems();
                }


                ComponentList comps = calendar.getComponents("VEVENT");

                for (Object comp : comps)
                {
                    Component c     = (Component) comp;
                    Event     event = new Event();

                    boolean isExistingEvent = false;
                    for (Event e : events)
                    {
                        if (e.getICalUID().equals(c.getProperty(Property.UID).getValue()))
                        {
                            event.setId(e.getId());
                            event.setSequence(e.getSequence() + 1);
                            isExistingEvent = true;
                            break;
                        }
                    }

                    event.setICalUID(c.getProperty(Property.UID)
                                      .getValue());


                    net.fortuna.ical4j.model.property.Url uri = (net.fortuna.ical4j.model.property.Url) c.getProperty(Property.URL);
                    event.setHtmlLink(uri.getUri().toString());
                    event.setSummary(c.getProperty(Property.SUMMARY).getValue());


                    DateTime sdt = new DateTime(fixTime(c.getProperty(Property.DTSTART)
                                                         .getValue()));


                    EventDateTime startDT = new EventDateTime();
                    startDT.setDateTime(sdt);
                    event.setStart(startDT);

                    DateTime edt = new DateTime(fixTime(c.getProperty(Property.DTEND)
                                                         .getValue()));
                    EventDateTime endDT = new EventDateTime();
                    endDT.setDateTime(edt);
                    event.setEnd(endDT);
                    try
                    {
                        if (isExistingEvent)
                        {
                            calendarClient.events().update(thisCalendar.getId(), event.getId(), event).queue(batchRequest, eventJsonBatchCallback);
                        }
                        else
                        {
                            calendarClient.events().insert(thisCalendar.getId(), event).queue(batchRequest, eventJsonBatchCallback);
                        }
                    }
                    catch (Exception e)
                    {
                        Log.e("ICalSubscriber", String.format("Error saving event: %s", event.getICalUID()));
                    }
                }

                if (batchRequest.size() > 0)
                {
                    batchRequest.execute();
                    events = calendarClient.events()
                                           .list(thisCalendar.getId())
                                           .execute()
                                           .getItems();
                }

                //find events to delete. probably ineffecient.
                for (Event e : events)
                {
                    boolean keep = false;
                    for (Object comp : comps)
                    {
                        Component c = (Component) comp;
                        if (e.getICalUID().equals(c.getProperty(Property.UID).getValue()))
                        {
                            keep = true;
                            break;
                        }
                    }
                    if (!keep)
                    {
                        calendarClient.events()
                                      .delete(thisCalendar.getId(), e.getId())
                                      .queue(batchRequest, deleteJsonBatchCallback);
                    }
                }

                if (batchRequest.size() > 0)
                {
                    batchRequest.execute();
                }

                if (RBICalSubscriber.this.callbackContext != null)
                {
                    RBICalSubscriber.this.callbackContext.success("Calendar Sync Complete");
                    RBICalSubscriber.this.callbackContext = null;
                }

                RBICalSubscriber.this.cordova.getActivity().runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        Toast.makeText(RBICalSubscriber.this.cordova.getActivity(), "...Calendar Sync Complete!",
                                       Toast.LENGTH_SHORT).show();
                    }
                });

                Log.i("ICalSubscriber", String.format("Calendar Sync Complete: %s", calendarName));
            }
            catch (IOException e)
            {
                Log.e("ICalSubscriber", e.toString());
            }
            catch (net.fortuna.ical4j.data.ParserException e)
            {
                Log.e("ICalSubscriber", e.toString());
            }
            catch (Exception e)
            {
                Log.e("ICalSubscriber", e.toString());
            }
            finally
            {
                if (urlConnection != null)
                {
                    urlConnection.disconnect();
                }
//                RBICalSubscriber.this.cordova.getActivity().runOnUiThread(new Runnable()
//                {
//                    public void run()
//                    {
//                        Toast.makeText(RBICalSubscriber.this.cordova.getActivity(), "...Calendar Sync Had Errors!",
//                                       Toast.LENGTH_SHORT).show();
//                    }
//                });
            }

            return "";
        }

        /**
         * @param time String
         * @return String
         */

        private String fixTime(String time)
        {
            return time.replaceAll("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})Z", "$1-$2-$3T$4:$5:$6Z");
        }
    }

    /**
     *
     */
    private class authTask extends AsyncTask<String, String, String>
    {
        private UserRecoverableAuthException userException;

        /**
         * @param params String...
         * @return String
         */
        @Override
        protected String doInBackground(String... params)
        {
            try
            {
                return credential.getToken();
            }
            catch (IOException e)
            {
                return "";
            }
            catch (UserRecoverableAuthException e)
            {
                userException = e;
                return "-1";
            }
            catch (Exception e)
            {
                return "";
            }
        }

        /**
         * @param result String
         */
        @Override
        protected void onPostExecute(String result)
        {
            if (result.length() > 0 && !result.equals("-1"))
            {
                importCalendar(calendarUrl);
            }
            else if (result.equals("-1"))
            {
                RBICalSubscriber.this.cordova.startActivityForResult(RBICalSubscriber.this, userException.getIntent(), REQUEST_CODE_PICK_ACCOUNT);
            }
            else
            {
                Toast.makeText(activityContext, "Unable To Obtain Google Access. Sorry.",
                               Toast.LENGTH_LONG).show();
                if (RBICalSubscriber.this.callbackContext != null)
                {
                    RBICalSubscriber.this.callbackContext.error("an error occured");
                    RBICalSubscriber.this.callbackContext = null;
                }
            }
        }
    }

    /**
     * @param email String
     */

    private void doAuth(String email)
    {
        if (email != null)
        {
            new authTask().executeOnExecutor(this.cordova.getThreadPool());
        }
        else
        {
            Toast.makeText(activityContext, "Network unavailable.", Toast.LENGTH_LONG).show();
            if (RBICalSubscriber.this.callbackContext != null)
            {
                RBICalSubscriber.this.callbackContext.error("an error occured");
                RBICalSubscriber.this.callbackContext = null;
            }
        }
    }

    /**
     * @param url String
     */
    private void importCalendar(String url)
    {
        if (isDeviceOnline())
        {
            new importCalendarTask().executeOnExecutor(this.cordova.getThreadPool(), url);
        }
        else
        {
            Toast.makeText(activityContext, "Network unavailable.", Toast.LENGTH_LONG).show();
            if (RBICalSubscriber.this.callbackContext != null)
            {
                RBICalSubscriber.this.callbackContext.error("an error occured");
                RBICalSubscriber.this.callbackContext = null;
            }
        }
    }

    /**
     * Checks whether the device currently has a network connection.
     *
     * @return true if the device has a network connection, false otherwise.
     */
    private boolean isDeviceOnline()
    {
        ConnectivityManager connMgr =
                (ConnectivityManager) activityContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     *
     * @return true if Google Play Services is available and up to
     * date on this device; false otherwise.
     */
    private boolean isGooglePlayServicesAvailable()
    {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(activityContext);
        return connectionStatusCode == ConnectionResult.SUCCESS;
    }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private void acquireGooglePlayServices()
    {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(activityContext);
        if (apiAvailability.isUserResolvableError(connectionStatusCode))
        {
            AlertDialog.Builder myAlertDialog = new AlertDialog.Builder(activityContext);
            myAlertDialog.setTitle("Google Account Required");
            myAlertDialog.setMessage("Sorry, you must have Google Play available.");

            myAlertDialog.setPositiveButton("OK",
                                            new DialogInterface.OnClickListener()
                                            {
                                                public void onClick(DialogInterface arg0, int arg1)
                                                {
                                                    arg0.dismiss();
                                                }
                                            });
        }
    }
}
