package com.autoupdateapk;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import static android.content.Context.DOWNLOAD_SERVICE;

/**
 * Created by Tommi Jokinen on 21/09/2017.
 *
 * This class handles currently the downloading of an APK file from the given uri (URL in this case).
 */

public class UpdateManager {

    private Uri updateURI; // Uri to download the APK from.
    private DownloadManager downloadManager; // Handles the downloading of our files.
    private BroadcastReceiver downloadReceiver; // Download Manager notifies when dl is complete.
    private long downloadRefID; // Unique ID of download, is different for each request.
    private Context context;


    private UpdateManagerListener listener;
    /*
        Constructor
     */
    public UpdateManager(Context cont, String uri, UpdateManagerListener listnr){
        this.updateURI = Uri.parse(uri);
        //this.updateURI = Uri.parse("http://localhost:5000/");
        this.context = cont;
        this.listener = listnr;

        this.initializeManager();
    }

    /*
        Initializes the following components for UpdateManager:
        - BroadcastReceiver downloadReceive.
     */
    private void initializeManager(){
        // Create BroadcastReceiver and define it's method.
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long referenceId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                if (referenceId == downloadRefID){
                    // By default, assume download was failed.
                    boolean downloadSuccess = false;
                    String downloadLocalUri = "null";
                    String downloadMimeType = "null";

                    // Send the query to download manager which returns a cursor.
                    DownloadManager.Query apkDownloadQuery = new DownloadManager.Query();
                    apkDownloadQuery.setFilterById(downloadRefID);
                    Cursor cursor = downloadManager.query(apkDownloadQuery);

                    if(cursor.moveToFirst()){
                        // Find the column index of the STATUS column, shows download status.
                        int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        // Find the column index of the REASON column, shows code if download failed or is paused.
                        int columnReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        int reason = cursor.getInt(columnReason);
                        downloadLocalUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                        downloadMimeType = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE));
                        int status = cursor.getInt(columnIndex);

                        // Check that the download status is successful.
                        if (status == DownloadManager.STATUS_SUCCESSFUL && downloadLocalUri != null){
                            // Allow updating
                            downloadSuccess = true;
                        }
                        else{
                            // Possible error in download. Call statusMessage for more information.
                            listener.updateManagerError("Download Status: " + statusMessage(status, reason));
                        }
                    }
                    else {
                        // Cursor empty, download was cancelled.
                        listener.updateManagerCancelled();
                    }
                    cursor.close(); // Close cursor to free resources.

                    // Start installation if update found and Uri and MimeType are not "null"
                    if (downloadSuccess && !downloadLocalUri.equals("null") && !downloadMimeType.equals("null")){
                        listener.updateManagerFinished(Uri.parse(downloadLocalUri), downloadMimeType);
                    }

                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        context.registerReceiver(downloadReceiver, filter);
    }

    /*
        Creates a request for DownloadManager to handle the download of the APK file.
     */
    public void startDownload(){

        boolean listenerSet = listener != null;
        boolean contextSet = context != null;
        boolean uriSet = updateURI != null;

        if (listenerSet && contextSet && uriSet){
            if (!isDownloading()){
                downloadManager = (DownloadManager) context.getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Request request = new DownloadManager.Request(updateURI);

                // Set DownloadManager notification's messages, load from string.xml for multilanguage support.
                request.setTitle(context.getString(R.string.dm_title) +
                        " " + context.getString(R.string.dm_title));
                request.setDescription(context.getString(R.string.dm_desc));

                // Set download path for the APK file and the downloaded file's name.
                String URL = updateURI.toString(); // Load URI temporarily to string to get filename.
                request.setDestinationInExternalFilesDir(context,
                        Environment.DIRECTORY_DOWNLOADS,
                        URL.substring(URL.lastIndexOf("/") + 1, URL.length()));

                downloadRefID = downloadManager.enqueue(request);

                listener.updateManagerDownloadStarted();
            }
            else{
                listener.updateManagerError("Download error: \n"
                        + "Download (ID:" + downloadRefID + ") has already been queued.");
            }

        }
        else{
            listener.updateManagerError("Download error: \n"
                    + "listenerSet = " + listenerSet + "\ncontextSet = " + contextSet + "\nuriSet = " + uriSet);
        }

    }

    public String getDownloadStatus(){
        if (downloadManager != null){
            Cursor cursor = downloadManager.query(new DownloadManager.Query()
                    .setFilterById(downloadRefID));

            int status = -9999;
            int reason = -9999;
            if (cursor.moveToFirst()){
                // Find the column index of the STATUS column, shows download status.
                int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                status = cursor.getInt(columnIndex);
                int columnReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                reason = cursor.getInt(columnReason);
            }
            cursor.close();
            if (status !=  -9999 && reason != -9999)
                return "Download ID:" + downloadRefID + "\n" + statusMessage(status, reason);
        }

        return "Download ID:" + downloadRefID + "\nDownload has not started.";
    }

    /*
        Return true if there is already download.
        Returns false if there is no download.
     */
    private boolean isDownloading(){
        if (downloadManager != null){
            Cursor cursor = downloadManager.query(new DownloadManager.Query()
                    .setFilterById(downloadRefID));

            int status = DownloadManager.STATUS_FAILED;
            if (cursor.moveToFirst()){
                // Find the column index of the STATUS column, shows download status.
                int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                status = cursor.getInt(columnIndex);
            }

            cursor.close();

            if (status == DownloadManager.STATUS_PAUSED
                    || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING){
                return true;
            }
        }

        return false;
    }

    /*
        Cancels the current on going download.
     */
    public void cancelDownload(){
        if (downloadManager != null)
            downloadManager.remove(downloadRefID);
    }

    /*
        Creates a message based on DownloadManager status and reason enum.

         INPUT:
         DownloadManager enum STATUS int, DownloadManager enum REASON int
         OUTPUT:
         String message, compiled from the DownloadManager codes.
     */
    private String statusMessage(int status, int reason){
        String statusText = "";
        String reasonText = "";

        // Switch case, message for each reason codes.
        switch(status){
            case DownloadManager.STATUS_FAILED:
                statusText = "STATUS_FAILED";
                switch(reason){
                    case DownloadManager.ERROR_CANNOT_RESUME:
                        reasonText = "ERROR_CANNOT_RESUME";
                        break;
                    case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                        reasonText = "ERROR_DEVICE_NOT_FOUND";
                        break;
                    case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                        reasonText = "ERROR_FILE_ALREADY_EXISTS";
                        break;
                    case DownloadManager.ERROR_FILE_ERROR:
                        reasonText = "ERROR_FILE_ERROR";
                        break;
                    case DownloadManager.ERROR_HTTP_DATA_ERROR:
                        reasonText = "ERROR_HTTP_DATA_ERROR";
                        break;
                    case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                        reasonText = "ERROR_INSUFFICIENT_SPACE";
                        break;
                    case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                        reasonText = "ERROR_TOO_MANY_REDIRECTS";
                        break;
                    case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                        reasonText = "ERROR_UNHANDLED_HTTP_CODE";
                        break;
                    case DownloadManager.ERROR_UNKNOWN:
                        reasonText = "ERROR_UNKNOWN";
                        break;
                    case 404:
                        reasonText = "ERROR_404";
                        break;
                }
                break;
            case DownloadManager.STATUS_PAUSED:
                statusText = "STATUS_PAUSED";
                switch(reason){
                    case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                        reasonText = "PAUSED_QUEUED_FOR_WIFI";
                        break;
                    case DownloadManager.PAUSED_UNKNOWN:
                        reasonText = "PAUSED_UNKNOWN";
                        break;
                    case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                        reasonText = "PAUSED_WAITING_FOR_NETWORK";
                        break;
                    case DownloadManager.PAUSED_WAITING_TO_RETRY:
                        reasonText = "PAUSED_WAITING_TO_RETRY";
                        break;
                    case 404:
                        reasonText = "URL_DOES_NOT_EXIST";
                        break;
                }
                break;
            case DownloadManager.STATUS_PENDING:
                statusText = "STATUS_PENDING";
                break;
            case DownloadManager.STATUS_RUNNING:
                statusText = "STATUS_RUNNING";
                break;
            case DownloadManager.STATUS_SUCCESSFUL:
                statusText = "STATUS_SUCCESSFUL";
                String URL = updateURI.toString();
                reasonText = "Filename: \n" + URL.substring(URL.lastIndexOf("/") + 1, URL.length());
                break;
        }

        return statusText + "\n" + reasonText;
    }


    /*
        If UpdateManager gets called down for any reason, cancel all downloads.
     */
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        cancelDownload();
    }
}
